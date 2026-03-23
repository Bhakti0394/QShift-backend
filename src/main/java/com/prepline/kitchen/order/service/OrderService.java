package com.prepline.kitchen.order.service;

import com.prepline.kitchen.inventory.service.InventoryService;
import com.prepline.kitchen.menu.domain.MenuItem;
import com.prepline.kitchen.menu.repository.MenuItemRepository;
import com.prepline.kitchen.order.controller.CustomerSseController;
import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderItem;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.repository.OrderRepository;
import com.prepline.kitchen.slot.domain.PickupSlot;
import com.prepline.kitchen.slot.repository.PickupSlotRepository;
import com.prepline.kitchen.staff.service.StaffCapacityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository      orderRepository;
    private final MenuItemRepository   menuItemRepository;
    private final StaffCapacityService staffCapacityService;
    private final PickupSlotRepository slotRepository;
    private final InventoryService     inventoryService;

    @Value("${kitchen.backup-activation-mode:SUGGEST}")
    private String backupActivationMode;

    // =========================================================================
    // ORDER CREATION
    // =========================================================================

    public Order createOrder(String orderRef, List<UUID> menuItemIds,
                             String customerName, UUID pickupSlotId) {

        PickupSlot pickupSlot = null;
        if (pickupSlotId != null) {
            pickupSlot = slotRepository.findById(pickupSlotId)
                    .orElseThrow(() -> new RuntimeException("Pickup slot not found: " + pickupSlotId));
            if (!pickupSlot.hasCapacity()) {
                throw new RuntimeException(
                        "Pickup slot at " + pickupSlot.getSlotTime() + " is fully booked");
            }
        }

        Order order = Order.builder()
                .orderRef(orderRef)
                .customerName(customerName != null ? customerName : orderRef)
                .status(OrderStatus.PENDING)
                .placedAt(Instant.now())
                .build();

        List<OrderItem> items = new ArrayList<>();
        int maxPrepTime = 0;

        for (UUID menuItemId : menuItemIds) {
            MenuItem menuItem = menuItemRepository.findById(menuItemId)
                    .orElseThrow(() -> new RuntimeException("MenuItem not found: " + menuItemId));
            items.add(OrderItem.builder()
                    .order(order)
                    .menuItem(menuItem)
                    .quantity(1)
                    .prepTimeMinutes(menuItem.getPrepTimeMinutes())
                    .build());
            maxPrepTime = Math.max(maxPrepTime, menuItem.getPrepTimeMinutes());
        }

        order.setItems(items);
        order.setTotalPrepTimeMinutes(maxPrepTime);

        if (pickupSlot != null) {
            pickupSlot.setCurrentBookings(pickupSlot.getCurrentBookings() + 1);
            slotRepository.save(pickupSlot);
            order.setPickupSlot(pickupSlot);
            log.info("[OrderIntake] {} linked to pickup slot {} ({})",
                    orderRef, pickupSlot.getId(), pickupSlot.getSlotTime());
        }

        int activeCapacity = staffCapacityService.getActiveCapacity();
        int maxQueueDepth  = staffCapacityService.getMaxQueueDepth();
        int currentCooking = orderRepository.countByStatus(OrderStatus.COOKING);
        int currentPending = orderRepository.countByStatus(OrderStatus.PENDING);

        if (activeCapacity == 0) {
            log.warn("[OrderIntake] No ACTIVE staff — queuing {} without chef assignment", orderRef);
            return orderRepository.save(order);
        }

        if (currentPending < maxQueueDepth || currentCooking < activeCapacity) {
            Order saved = orderRepository.save(order);
            log.info("[OrderIntake] {} → PENDING queue ({}/{})",
                    orderRef, currentPending + 1, maxQueueDepth);

            double queueFillRatio = (double)(currentPending + 1) / maxQueueDepth;
            if (queueFillRatio > 0.8 && staffCapacityService.hasBackupStaff()) {
                if ("AUTO".equalsIgnoreCase(backupActivationMode)) {
                    boolean activated = staffCapacityService.autoActivateOneBackupChef();
                    if (activated) {
                        log.info("[OrderIntake] AUTO mode: activated backup chef (queue %.0f%% full)"
                                .formatted(queueFillRatio * 100));
                    }
                } else {
                    log.info("[OrderIntake] Queue at %.0f%% — backup staff available (mode={})"
                            .formatted(queueFillRatio * 100), backupActivationMode);
                }
            }
            return saved;
        }

        if (pickupSlot != null) {
            pickupSlot.setCurrentBookings(pickupSlot.getCurrentBookings() - 1);
            slotRepository.save(pickupSlot);
        }

        throw new SlotUnavailableException(
                "Kitchen is at full capacity. " +
                        "Cooking: " + currentCooking + "/" + activeCapacity + ", " +
                        "Queue: " + currentPending + "/" + maxQueueDepth + ". " +
                        "Please select a later pickup slot.");
    }

    public Order createOrder(String orderRef, List<UUID> menuItemIds) {
        return createOrder(orderRef, menuItemIds, null, null);
    }

    public Order createOrder(String orderRef, List<UUID> menuItemIds, String customerName) {
        return createOrder(orderRef, menuItemIds, customerName, null);
    }

    // =========================================================================
    // ORDER TRANSITIONS
    // =========================================================================

    public Order transition(UUID orderId, OrderStatus targetStatus) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!isValidTransition(order.getStatus(), targetStatus)) {
            throw new IllegalStateException(
                    "Cannot transition from " + order.getStatus() + " to " + targetStatus);
        }

        if (targetStatus == OrderStatus.COOKING) {
            Order freshRead = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            if (freshRead.getAssignedChef() == null) {
                try {
                    staffCapacityService.autoAssignChef(orderId);
                } catch (RuntimeException ex) {
                    throw new IllegalStateException(
                            "Cannot start cooking — no chef assigned and none available for order " +
                                    order.getOrderRef() +
                                    ". Please assign a chef first or activate backup staff. " +
                                    "Reason: " + ex.getMessage());
                }
            }

            order = orderRepository.findByIdWithLock(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            if (order.getAssignedChef() == null) {
                throw new IllegalStateException(
                        "Cannot start cooking — no chef assigned for order " +
                                order.getOrderRef() + ". Please assign a chef first.");
            }

            // Deduct ingredients via recipe table. Non-fatal — inventory
            // failure must never block an order from starting to cook.
            deductInventoryForOrder(order);
        }

        OrderStatus previousStatus = order.getStatus();
        stampTimestamp(order, targetStatus);
        order.setStatus(targetStatus);
        orderRepository.save(order);

        // Push real-time status update to any customer watching this order via SSE.
        // Non-fatal — if no customer is subscribed the call is a no-op.
        CustomerSseController.pushStatusUpdate(order.getId().toString(), targetStatus);

        if (previousStatus == OrderStatus.COOKING && targetStatus == OrderStatus.READY) {
            staffCapacityService.promoteNextPendingOrder(false);
        }

        return order;
    }

    public void assignChef(UUID orderId, UUID chefId) {
        staffCapacityService.assignChef(orderId, chefId);
    }

    public void handleOrphanedOrders() {
        List<Order> unassigned = orderRepository.findByAssignedChefIsNullAndStatusIn(
                List.of(OrderStatus.COOKING, OrderStatus.PENDING));
        for (Order order : unassigned) {
            try {
                staffCapacityService.autoAssignChef(order.getId());
                log.info("[Orphan] Assigned chef to order {}", order.getOrderRef());
            } catch (RuntimeException ex) {
                log.warn("[Orphan] Order {} still unassigned — no capacity: {}",
                        order.getOrderRef(), ex.getMessage());
            }
        }
    }

    public void reserveSlot(UUID orderId, UUID slotId) {
        PickupSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found: " + slotId));
        if (!slot.hasCapacity()) {
            throw new RuntimeException("Slot at " + slot.getSlotTime() + " is fully booked");
        }
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        if (order.getPickupSlot() != null) {
            throw new RuntimeException(
                    "Order " + order.getOrderRef() + " already has a pickup slot reserved: " +
                            order.getPickupSlot().getSlotTime());
        }
        slot.setCurrentBookings(slot.getCurrentBookings() + 1);
        slotRepository.save(slot);
        order.setPickupSlot(slot);
        orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Order getById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private void deductInventoryForOrder(Order order) {
        for (OrderItem item : order.getItems()) {
            try {
                inventoryService.consumeForMenuItem(
                        item.getMenuItem().getId(),
                        item.getQuantity()
                );
            } catch (Exception ex) {
                log.warn("[Inventory] Failed to deduct stock for '{}' in order {}: {}",
                        item.getMenuItem().getName(), order.getOrderRef(), ex.getMessage());
            }
        }
    }

    private boolean isValidTransition(OrderStatus from, OrderStatus to) {
        if (to == OrderStatus.CANCELLED) return true;
        return switch (from) {
            case PENDING  -> to == OrderStatus.COOKING;
            case COOKING  -> to == OrderStatus.READY;
            case READY    -> to == OrderStatus.COMPLETED;
            default       -> false;
        };
    }

    private void stampTimestamp(Order order, OrderStatus target) {
        Instant now = Instant.now();
        switch (target) {
            case COOKING   -> order.setCookingStartedAt(now);
            case READY     -> order.setReadyAt(now);
            case COMPLETED -> order.setCompletedAt(now);
        }
    }

    public static class SlotUnavailableException extends RuntimeException {
        public SlotUnavailableException(String message) { super(message); }
    }
}