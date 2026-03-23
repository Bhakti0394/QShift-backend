package com.prepline.kitchen.staff.service;

import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.repository.OrderRepository;
import com.prepline.kitchen.staff.domain.KitchenStaff;
import com.prepline.kitchen.staff.domain.KitchenStaff.StaffStatus;
import com.prepline.kitchen.staff.dto.StaffRemovalValidationDto;
import com.prepline.kitchen.staff.dto.StaffWorkloadDto;
import com.prepline.kitchen.staff.repository.KitchenStaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StaffCapacityService {

    private final KitchenStaffRepository staffRepository;
    private final OrderRepository        orderRepository;

    // =========================================================================
    // CAPACITY READS
    // =========================================================================

    public int getActiveCapacity() {
        return staffRepository.findByStatus(StaffStatus.ACTIVE).stream()
                .mapToInt(KitchenStaff::getMaxConcurrentOrders)
                .sum();
    }

    public int getMaxQueueDepth() {
        return Math.max(1, getActiveCapacity() * 2);
    }

    public boolean hasBackupStaff() {
        return staffRepository.countByStatus(StaffStatus.BACKUP) > 0;
    }

    public boolean autoActivateOneBackupChef() {
        return staffRepository.findFirstByStatus(StaffStatus.BACKUP)
                .map(chef -> {
                    chef.setStatus(StaffStatus.ACTIVE);
                    staffRepository.save(chef);
                    log.info("[AutoActivate] Backup chef {} activated due to queue pressure", chef.getName());
                    return true;
                })
                .orElse(false);
    }

    // =========================================================================
    // CAPACITY COUNTING
    // =========================================================================

    private int cookingLoad(KitchenStaff chef) {
        return orderRepository.countByAssignedChefAndStatusIn(
                chef, List.of(OrderStatus.COOKING));
    }

    private int totalLoad(KitchenStaff chef) {
        return orderRepository.countByAssignedChefAndStatusIn(
                chef, List.of(OrderStatus.PENDING, OrderStatus.COOKING));
    }

    // =========================================================================
    // ASSIGNMENT
    // =========================================================================

    public void assignChef(UUID orderId, UUID chefId) {
        KitchenStaff chef = staffRepository.findByIdWithLock(chefId)
                .orElseThrow(() -> new RuntimeException("Chef not found: " + chefId));

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        int cooking = cookingLoad(chef);
        if (cooking >= chef.getMaxConcurrentOrders()) {
            throw new RuntimeException(
                    "Chef " + chef.getName() + " is at full cooking capacity (" +
                            cooking + "/" + chef.getMaxConcurrentOrders() + " cooking)");
        }

        order.setAssignedChef(chef);
        orderRepository.save(order);
        log.info("[Assign] Order {} assigned to chef {} (cooking {}/{})",
                order.getOrderRef(), chef.getName(), cooking + 1, chef.getMaxConcurrentOrders());
    }

    /**
     * autoAssignChef — assigns the least-loaded ACTIVE chef to an order.
     *
     * Selects the candidate first (unlocked read), then re-acquires the chef
     * row under a lock before writing. The caller is responsible for holding
     * a lock on the order row before calling this method to prevent
     * concurrent double-assignment.
     *
     * NOTE: this method does NOT call findByIdWithLock on the order — the
     * caller must have already locked it. This avoids the deadlock that
     * occurred when promoteNextPendingOrder (REQUIRES_NEW, holding order lock)
     * called autoAssignChef which tried to re-acquire the same order lock in
     * a nested context.
     */
    public void autoAssignChef(UUID orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        assignChefToOrder(order);
    }

    /**
     * assignChefToOrder — assigns the least-loaded ACTIVE chef to an already-
     * locked Order entity. Used internally by autoAssignChef and by
     * promoteNextPendingOrder to avoid acquiring a second lock on the same row.
     *
     * FIX (deadlock):
     * BEFORE: promoteNextPendingOrder called autoAssignChef(orderId) which
     * called orderRepository.findByIdWithLock(orderId) — attempting to acquire
     * a SELECT FOR UPDATE on an order row that promoteNextPendingOrder had
     * already locked in the same REQUIRES_NEW transaction. Two simulation ticks
     * firing close together both entered REQUIRES_NEW and tried to lock the same
     * candidate order → deadlock.
     *
     * AFTER: promoteNextPendingOrder passes the already-locked Order object here
     * directly. No second lock acquisition on the order row. Chef lock is still
     * acquired to prevent concurrent double-assignment.
     */
    private void assignChefToOrder(Order order) {
        List<KitchenStaff> activeChefs = staffRepository.findByStatus(StaffStatus.ACTIVE);

        KitchenStaff candidate = activeChefs.stream()
                .filter(c -> cookingLoad(c) < c.getMaxConcurrentOrders())
                .min(Comparator.comparingInt(this::cookingLoad))
                .orElseThrow(() -> new RuntimeException(
                        "No chef available — all at full cooking capacity"));

        // Re-acquire chef under lock before writing to prevent concurrent
        // double-assignment from two simultaneous simulation ticks
        KitchenStaff chef = staffRepository.findByIdWithLock(candidate.getId())
                .orElseThrow(() -> new RuntimeException("Chef not found after lock: " + candidate.getId()));

        int cookingUnderLock = cookingLoad(chef);
        if (cookingUnderLock >= chef.getMaxConcurrentOrders()) {
            throw new RuntimeException(
                    "Chef " + chef.getName() + " filled up before lock was acquired — retry");
        }

        order.setAssignedChef(chef);
        orderRepository.save(order);
        log.info("[AutoAssign] Order {} auto-assigned to chef {} (cooking {}/{})",
                order.getOrderRef(), chef.getName(),
                cookingUnderLock + 1, chef.getMaxConcurrentOrders());
    }

    // =========================================================================
    // TRANSITION SIDE-EFFECTS
    // =========================================================================

    // NOTE: tryAutoAssignChef() has been intentionally removed.
    //
    // It was a REQUIRES_NEW wrapper around autoAssignChef() that was called
    // by OrderService.transition() before the Simulation=OFF fix. Now that
    // transition() throws when no chef is assigned (enforcing manual control),
    // tryAutoAssignChef() is dead code. Keeping it would risk it being
    // accidentally re-introduced, restoring the auto-assign-on-manual-drag
    // behaviour that was explicitly removed.

    /**
     * promoteNextPendingOrder — called by /simulate-advance (simulation=ON path).
     *
     * Promotes the single highest-priority PENDING order into COOKING.
     *
     * Priority sort (express-first):
     *   1. Express orders (orderRef contains '-EXPRESS') → weight 0
     *   2. Earliest pickup slot time
     *   3. Oldest placedAt (FIFO tiebreak)
     *
     * This is the NO-ARG version used by OrderController.simulateAdvance().
     * It always runs the promotion — the controller is only called when
     * simulation is active.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void promoteNextPendingOrder() {
        promoteNextPendingOrder(true);
    }

    /**
     * promoteNextPendingOrder(boolean simulationActive)
     *
     * FIX — Simulation gate:
     *   simulationActive=true  → promotion runs (called from /simulate-advance)
     *   simulationActive=false → promotion is SKIPPED (called from manual transition)
     *
     * FIX — Deadlock eliminated:
     * BEFORE: called autoAssignChef(orderId) which called
     *   orderRepository.findByIdWithLock(orderId) — acquiring a second
     *   SELECT FOR UPDATE on the same order row this method had already locked.
     *   Two concurrent REQUIRES_NEW transactions on the same candidate order
     *   → deadlock at the DB level.
     *
     * AFTER: locks the order once via findByIdWithLock, then calls
     *   assignChefToOrder(next) — passing the already-locked entity directly.
     *   assignChefToOrder only locks the chef row, never the order row again.
     *   Zero duplicate lock acquisitions → deadlock impossible.
     *
     * FIX — Express-first sort:
     *   Express orders (orderRef suffix '-EXPRESS') always sort to the top
     *   regardless of their slot time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void promoteNextPendingOrder(boolean simulationActive) {
        if (!simulationActive) {
            log.debug("[Promote] Simulation=OFF — skipping auto-promotion");
            return;
        }

        int capacity = getActiveCapacity();
        int cooking  = orderRepository.countByStatus(OrderStatus.COOKING);

        if (cooking >= capacity) return;

        orderRepository.findByStatus(OrderStatus.PENDING).stream()
                .min(
                        Comparator
                                .comparingInt((Order o) -> expressWeight(o.getOrderRef()))
                                .thenComparing(this::slotTime)
                                .thenComparing(o -> o.getPlacedAt() != null ? o.getPlacedAt() : Instant.MAX)
                )
                .ifPresent(candidate -> {
                    // FIX: acquire the order lock ONCE here.
                    // Do NOT pass orderId into autoAssignChef — that would
                    // trigger a second findByIdWithLock on the same row.
                    Order next = orderRepository.findByIdWithLock(candidate.getId())
                            .orElse(null);

                    if (next == null) return;

                    if (next.getStatus() != OrderStatus.PENDING) {
                        log.info("[Promote] Order {} already moved to {} — skipping",
                                next.getOrderRef(), next.getStatus());
                        return;
                    }

                    int cookingNow = orderRepository.countByStatus(OrderStatus.COOKING);
                    if (cookingNow >= getActiveCapacity()) {
                        log.info("[Promote] Capacity full after lock — no promotion");
                        return;
                    }

                    if (next.getAssignedChef() == null) {
                        try {
                            // FIX: pass the already-locked Order object directly.
                            // assignChefToOrder() only locks the chef row —
                            // it never calls findByIdWithLock on the order row.
                            assignChefToOrder(next);
                        } catch (RuntimeException ex) {
                            log.warn("[Promote] Order {} has no chef and none available — staying PENDING: {}",
                                    next.getOrderRef(), ex.getMessage());
                            return;
                        }
                    }

                    if (next.getAssignedChef() == null) {
                        log.warn("[Promote] Order {} still has no chef after auto-assign — staying PENDING",
                                next.getOrderRef());
                        return;
                    }

                    next.setStatus(OrderStatus.COOKING);
                    next.setCookingStartedAt(Instant.now());
                    orderRepository.save(next);
                    log.info("[Promote] Order {} PENDING → COOKING (chef: {}, express={})",
                            next.getOrderRef(),
                            next.getAssignedChef().getName(),
                            isExpress(next.getOrderRef()));
                });
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private int expressWeight(String orderRef) {
        if (orderRef == null) return 1;
        return orderRef.contains("-EXPRESS") ? 0 : 1;
    }

    private boolean isExpress(String orderRef) {
        return orderRef != null && orderRef.contains("-EXPRESS");
    }

    private LocalDateTime slotTime(Order order) {
        if (order.getPickupSlot() == null) return LocalDateTime.MAX;
        LocalDateTime t = order.getPickupSlot().getSlotTime();
        return t != null ? t : LocalDateTime.MAX;
    }

    // =========================================================================
    // STAFF LIFECYCLE
    // =========================================================================

    public List<StaffWorkloadDto> removeFromShift(UUID chefId) {
        KitchenStaff chef = staffRepository.findById(chefId)
                .orElseThrow(() -> new RuntimeException("Chef not found: " + chefId));
        chef.setStatus(StaffStatus.BACKUP);
        staffRepository.save(chef);
        log.info("[Shift] Chef {} moved to BACKUP", chef.getName());
        return getWorkloadSnapshot();
    }

    public List<StaffWorkloadDto> activateChef(UUID chefId) {
        KitchenStaff chef = staffRepository.findById(chefId)
                .orElseThrow(() -> new RuntimeException("Chef not found: " + chefId));
        chef.setStatus(StaffStatus.ACTIVE);
        staffRepository.save(chef);
        log.info("[Shift] Chef {} activated to ACTIVE", chef.getName());
        return getWorkloadSnapshot();
    }

    public StaffRemovalValidationDto validateRemoval(UUID chefId) {
        KitchenStaff chef = staffRepository.findById(chefId)
                .orElseThrow(() -> new RuntimeException("Chef not found: " + chefId));

        List<KitchenStaff> activeChefs = staffRepository.findByStatus(StaffStatus.ACTIVE);
        long remainingActive = activeChefs.stream()
                .filter(c -> !c.getId().equals(chefId))
                .count();

        List<Order> cookingOrders = orderRepository.findByAssignedChefAndStatusIn(
                chef, List.of(OrderStatus.COOKING));
        List<Order> pendingOrders = orderRepository.findByAssignedChefAndStatusIn(
                chef, List.of(OrderStatus.PENDING));

        int ordersToReassign = cookingOrders.size() + pendingOrders.size();

        boolean blocked = remainingActive == 0 && !cookingOrders.isEmpty();
        String blockReason = blocked
                ? chef.getName() + " is the last active chef and has " + cookingOrders.size()
                + " order(s) cooking. Activate a backup chef first."
                : null;

        int currentCapacity  = getActiveCapacity();
        int newCapacity      = Math.max(0, currentCapacity - chef.getMaxConcurrentOrders());
        int totalPending     = orderRepository.countByStatus(OrderStatus.PENDING);
        int ordersToThrottle = Math.max(0, totalPending - newCapacity);

        List<UUID> affectedOrderIds = cookingOrders.stream()
                .map(Order::getId)
                .toList();

        return StaffRemovalValidationDto.builder()
                .canRemove(!blocked)
                .blocked(blocked)
                .blockReason(blockReason)
                .ordersToReassign(ordersToReassign)
                .estimatedDelayMinutes(ordersToReassign * 5)
                .newCapacity(newCapacity)
                .ordersToThrottle(ordersToThrottle)
                .affectedOrderIds(affectedOrderIds)
                .build();
    }

    // =========================================================================
    // WORKLOAD SNAPSHOT
    // =========================================================================

    public List<StaffWorkloadDto> getWorkloadSnapshot() {
        return staffRepository
                .findByStatusIn(List.of(StaffStatus.ACTIVE, StaffStatus.BACKUP))
                .stream()
                .map(this::toWorkloadDto)
                .toList();
    }

    public StaffWorkloadDto toWorkloadDto(KitchenStaff chef) {
        int total   = totalLoad(chef);
        int cooking = cookingLoad(chef);
        int max     = chef.getMaxConcurrentOrders();

        int loadPercent = max > 0 ? Math.min(100, Math.round((float) cooking / max * 100)) : 0;

        String status = loadPercent >= 100 ? "full"
                : loadPercent >= 50        ? "busy"
                :                            "available";

        Instant startOfDay = Instant.now()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();

        int completedToday = orderRepository.countByAssignedChefAndStatusAndCompletedAtAfter(
                chef, OrderStatus.COMPLETED, startOfDay);

        return StaffWorkloadDto.builder()
                .chefId(chef.getId().toString())
                .name(chef.getName())
                .activeOrders(total)
                .maxCapacity(max)
                .loadPercent(loadPercent)
                .onShift(chef.getStatus() == StaffStatus.ACTIVE)
                .status(status)
                .completedToday(completedToday)
                .build();
    }
}