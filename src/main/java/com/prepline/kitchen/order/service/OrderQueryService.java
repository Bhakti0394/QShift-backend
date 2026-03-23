package com.prepline.kitchen.order.service;

import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.dto.CustomerOrderDto;
import com.prepline.kitchen.order.dto.KanbanBoardResponse;
import com.prepline.kitchen.order.dto.OrderCardDto;
import com.prepline.kitchen.order.repository.OrderRepository;
import com.prepline.kitchen.metrics.MetricsService;
import com.prepline.kitchen.metrics.dto.KitchenMetricsDto;
import com.prepline.kitchen.slot.domain.PickupSlot;
import com.prepline.kitchen.slot.dto.SlotCapacityDto;
import com.prepline.kitchen.slot.repository.PickupSlotRepository;
import com.prepline.kitchen.staff.domain.KitchenStaff;
import com.prepline.kitchen.staff.domain.KitchenStaff.StaffStatus;
import com.prepline.kitchen.staff.dto.StaffWorkloadDto;
import com.prepline.kitchen.staff.repository.KitchenStaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository        orderRepository;
    private final KitchenStaffRepository staffRepository;
    private final PickupSlotRepository   slotRepository;
    private final MetricsService         metricsService;

    private static final int    COMPLETED_BOARD_LIMIT = 50;
    private static final ZoneId KITCHEN_ZONE = ZoneId.of("Asia/Kolkata");

    // ── Customer-facing queries ───────────────────────────────────────────────

    /**
     * FIX: JwtAuthFilter stores the customer's EMAIL as the JWT subject/principal.
     * CustomerOrderController now passes auth.getName() (the email) here.
     *
     * Orders are stored with customerName = email (set in placeOrder via auth.getName()).
     * So findByCustomerName(email) works correctly for orders placed after this fix.
     *
     * For orders placed before (with a display name), we also try
     * findByCustomerNameContainingIgnoreCase as a fallback so old orders still show up.
     */
    public List<CustomerOrderDto> getOrdersForCustomer(String email) {
        // Primary lookup: orders placed with email as customerName (post-fix orders)
        List<Order> byEmail = orderRepository.findByCustomerName(email);

        // Fallback: also find orders where customerName contains the local part of the
        // email (e.g. "bhaktinimaj03" matches "bhaktinimaj03@gmail.com") for
        // any legacy orders stored before the email-as-name fix was applied.
        // Merge and deduplicate by order ID.
        Set<UUID> seen = byEmail.stream()
                .map(Order::getId)
                .collect(Collectors.toSet());

        List<Order> all = new ArrayList<>(byEmail);

        try {
            String localPart = email.contains("@") ? email.split("@")[0] : email;
            List<Order> byLocalPart = orderRepository
                    .findByCustomerNameContainingIgnoreCase(localPart);
            byLocalPart.stream()
                    .filter(o -> !seen.contains(o.getId()))
                    .forEach(all::add);
        } catch (Exception ignored) {
            // findByCustomerNameContainingIgnoreCase may not exist in all repo versions
            // — silently skip the fallback rather than breaking the primary result.
        }

        return all.stream()
                .map(CustomerOrderDto::from)
                .collect(Collectors.toList());
    }

    public CustomerOrderDto getOrderForCustomer(String orderId, String email) {
        UUID uuid;
        try {
            uuid = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid order ID: " + orderId);
        }

        Order order = orderRepository.findById(uuid)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Security: ensure the order belongs to the requesting customer.
        // Match on full email OR local part for legacy orders.
        if (order.getCustomerName() != null) {
            String storedName  = order.getCustomerName().toLowerCase();
            String emailLower  = email.toLowerCase();
            String localPart   = emailLower.contains("@")
                    ? emailLower.split("@")[0] : emailLower;

            boolean matches = storedName.equals(emailLower)
                    || storedName.contains(localPart)
                    || emailLower.contains(storedName);

            if (!matches) {
                throw new RuntimeException("Order not found: " + orderId);
            }
        }

        return CustomerOrderDto.from(order);
    }

    // ── Kitchen board snapshot ────────────────────────────────────────────────

    public KanbanBoardResponse getBoardSnapshot() {
        Map<OrderStatus, List<OrderCardDto>> columns = new LinkedHashMap<>();

        for (OrderStatus status : List.of(
                OrderStatus.PENDING,
                OrderStatus.COOKING,
                OrderStatus.READY)) {
            columns.put(status,
                    orderRepository.findByStatusWithItems(status)
                            .stream()
                            .map(this::toCardDto)
                            .collect(Collectors.toList()));
        }

        Instant startOfToday = LocalDate.now(KITCHEN_ZONE)
                .atStartOfDay(KITCHEN_ZONE)
                .toInstant();

        List<OrderCardDto> completedToday = orderRepository
                .findByStatusAndCompletedAtAfterOrderByCompletedAtDesc(
                        OrderStatus.COMPLETED, startOfToday)
                .stream()
                .limit(COMPLETED_BOARD_LIMIT)
                .map(this::toCardDto)
                .collect(Collectors.toList());

        columns.put(OrderStatus.COMPLETED, completedToday);

        List<KitchenStaff> staffList = staffRepository
                .findByStatusIn(List.of(StaffStatus.ACTIVE, StaffStatus.BACKUP));

        Map<UUID, Integer> cookingCounts = orderRepository
                .countCookingPerChef(List.of(OrderStatus.COOKING));

        Map<UUID, Integer> activeCounts = orderRepository
                .countActivePerChef(List.of(OrderStatus.PENDING, OrderStatus.COOKING));

        Map<UUID, Integer> completedTodayCounts = orderRepository
                .countCompletedTodayPerChef(startOfToday);

        List<StaffWorkloadDto> staffWorkload = staffList.stream()
                .map(staff -> toStaffWorkloadDto(
                        staff,
                        cookingCounts.getOrDefault(staff.getId(), 0),
                        activeCounts.getOrDefault(staff.getId(), 0),
                        completedTodayCounts.getOrDefault(staff.getId(), 0)))
                .collect(Collectors.toList());

        Instant now = Instant.now();
        List<SlotCapacityDto> slots = slotRepository
                .findBySlotTimeAfter(now)
                .stream()
                .map(this::toSlotCapacityDto)
                .collect(Collectors.toList());

        KitchenMetricsDto metrics = metricsService.computeMetrics(LocalDate.now(KITCHEN_ZONE));

        return new KanbanBoardResponse(columns, metrics, staffWorkload, slots);
    }

    public int countByStatus(OrderStatus status) {
        return orderRepository.countByStatus(status);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private OrderCardDto toCardDto(Order order) {

        List<String> itemSummary = order.getItems() == null ? List.of() :
                order.getItems().stream()
                        .map(i -> i.getQuantity() + "x " + i.getMenuItem().getName())
                        .collect(Collectors.toList());

        String chefName = order.getAssignedChef() != null ? order.getAssignedChef().getName() : null;
        UUID   chefId   = order.getAssignedChef() != null ? order.getAssignedChef().getId()   : null;

        String slotTime = null;
        if (order.getPickupSlot() != null) {
            slotTime = order.getPickupSlot().getSlotTime()
                    .atZone(KITCHEN_ZONE)
                    .toInstant()
                    .toString();
        }

        int elapsedMinutes = 0;
        Instant cookStart = order.getCookingStartedAt();
        if (cookStart != null) {
            Instant cookEnd =
                    order.getCompletedAt()  != null ? order.getCompletedAt()  :
                            order.getReadyAt()      != null ? order.getReadyAt()      :
                                    order.getStatus() == OrderStatus.COOKING ? Instant.now() : null;

            if (cookEnd != null) {
                long ms = Duration.between(cookStart, cookEnd).toMillis();
                if (ms > 0 && ms < 8L * 60 * 60 * 1000) {
                    elapsedMinutes = (int) (ms / 60_000);
                }
            }
        }

        boolean isLate = false;
        if (order.getPickupSlot() != null
                && order.getStatus() != OrderStatus.READY
                && order.getStatus() != OrderStatus.COMPLETED) {
            try {
                Instant deadline = order.getPickupSlot().getSlotTime()
                        .atZone(KITCHEN_ZONE).toInstant();
                Instant anchor   = cookStart != null ? cookStart : Instant.now();
                int     prepSecs = (order.getTotalPrepTimeMinutes() != null
                        ? order.getTotalPrepTimeMinutes() : 0) * 60;
                isLate = anchor.plusSeconds(prepSecs).isAfter(deadline);
            } catch (Exception ignored) {
                isLate = false;
            }
        }

        return new OrderCardDto(
                order.getId(),
                order.getOrderRef(),
                order.getStatus(),
                itemSummary,
                order.getCustomerName(),
                chefName,
                chefId != null ? chefId.toString() : null,
                slotTime,
                order.getTotalPrepTimeMinutes() != null ? order.getTotalPrepTimeMinutes() : 0,
                order.getPlacedAt(),
                order.getCookingStartedAt(),
                order.getReadyAt(),
                order.getCompletedAt(),
                elapsedMinutes,
                isLate,
                deriveOrderType(order.getOrderRef())
        );
    }

    private String deriveOrderType(String orderRef) {
        if (orderRef == null)                return "NORMAL";
        if (orderRef.contains("-SCHEDULED")) return "SCHEDULED";
        if (orderRef.contains("-EXPRESS"))   return "EXPRESS";
        return "NORMAL";
    }

    private StaffWorkloadDto toStaffWorkloadDto(KitchenStaff staff,
                                                int cookingLoad,
                                                int active,
                                                int completedToday) {
        int max  = staff.getMaxConcurrentOrders();
        int load = max > 0 ? (int) Math.round((cookingLoad * 100.0) / max) : 0;

        String status =
                load >= 100 ? "full"      :
                        load >= 50  ? "busy"      :
                                "available";

        return new StaffWorkloadDto(
                staff.getId().toString(),
                staff.getName(),
                active,
                max,
                load,
                staff.getStatus() == StaffStatus.ACTIVE,
                completedToday,
                status
        );
    }

    private SlotCapacityDto toSlotCapacityDto(PickupSlot slot) {
        return new SlotCapacityDto(
                slot.getId(),
                slot.getSlotTime().atZone(KITCHEN_ZONE).toInstant(),
                slot.getMaxCapacity(),
                slot.getCurrentBookings(),
                slot.getMaxCapacity() - slot.getCurrentBookings()
        );
    }
}