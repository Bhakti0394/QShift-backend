package com.prepline.kitchen.order.controller;

import com.prepline.kitchen.menu.domain.MenuItem;
import com.prepline.kitchen.menu.repository.MenuItemRepository;
import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.dto.*;
import com.prepline.kitchen.order.service.OrderQueryService;
import com.prepline.kitchen.order.service.OrderService;
import com.prepline.kitchen.metrics.MetricsService;
import com.prepline.kitchen.metrics.dto.KitchenMetricsDto;
import com.prepline.kitchen.staff.service.StaffCapacityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/kitchen")
@RequiredArgsConstructor
public class OrderController {

    private final OrderQueryService    orderQueryService;
    private final OrderService         orderService;
    private final MetricsService       metricsService;
    private final MenuItemRepository   menuItemRepository;
    private final StaffCapacityService staffCapacityService;

    // ── Board ──────────────────────────────────────────────────────────────

    @GetMapping("/board")
    public ResponseEntity<KanbanBoardResponse> getBoard() {
        return ResponseEntity.ok(orderQueryService.getBoardSnapshot());
    }

    // ── Menu Items ─────────────────────────────────────────────────────────

    @GetMapping("/menu-items")
    public ResponseEntity<List<MenuItem>> getMenuItems() {
        return ResponseEntity.ok(menuItemRepository.findByAvailableTrue());
    }

    // ── Orders ─────────────────────────────────────────────────────────────

    /**
     * POST /api/kitchen/orders  (simulation / kitchen-side order creation)
     *
     * FIX: Now calls the 4-arg createOrder overload so pickupSlotId from
     * CreateOrderRequest is never silently dropped.
     *
     * Previously called the 3-arg overload:
     *   orderService.createOrder(ref, ids, name)   ← dropped pickupSlotId
     * Now calls:
     *   orderService.createOrder(ref, ids, name, slotId)   ← correct
     */
    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        try {
            Order order = orderService.createOrder(
                    request.orderRef(),
                    request.menuItemIds(),
                    request.customerName(),
                    request.pickupSlotId()   // FIX: was ignored — now passed through
            );
            return ResponseEntity.ok(order.getId());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<?> changeStatus(
            @PathVariable UUID id,
            @RequestBody StatusChangeRequest request) {
        try {
            return ResponseEntity.ok(
                    orderService.transition(id, request.targetStatus())
            );
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/orders/{id}/assign-chef")
    public ResponseEntity<?> assignChef(
            @PathVariable UUID id,
            @RequestBody AssignChefRequest request) {
        try {
            orderService.assignChef(id, request.chefId());
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/orders/{id}/reserve-slot")
    public ResponseEntity<?> reserveSlot(
            @PathVariable UUID id,
            @RequestParam UUID slotId) {
        orderService.reserveSlot(id, slotId);
        return ResponseEntity.ok().build();
    }

    // ── Simulate Advance ───────────────────────────────────────────────────

    @PostMapping("/simulate-advance")
    public ResponseEntity<Map<String, Integer>> simulateAdvance() {
        int promoted = 0;
        int capacity = staffCapacityService.getActiveCapacity();

        for (int i = 0; i < capacity; i++) {
            int cooking   = orderQueryService.countByStatus(OrderStatus.COOKING);
            int freeSlots = capacity - cooking;
            if (freeSlots <= 0) {
                log.debug("[SimAdvance] No free slots after {} promotions — stopping", promoted);
                break;
            }
            try {
                staffCapacityService.promoteNextPendingOrder();
                promoted++;
            } catch (Exception ex) {
                log.debug("[SimAdvance] Promotion {} stopped: {}", promoted + 1, ex.getMessage());
                break;
            }
        }

        log.info("[SimAdvance] Promoted {} order(s) to COOKING (capacity={})", promoted, capacity);
        return ResponseEntity.ok(Map.of("promoted", promoted));
    }

    // ── Metrics ────────────────────────────────────────────────────────────

    @GetMapping("/metrics")
    public ResponseEntity<KitchenMetricsDto> getMetrics(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(metricsService.computeMetrics(target));
    }

    // ── Server time sync ───────────────────────────────────────────────────

    @GetMapping("/server-time")
    public ResponseEntity<Map<String, Long>> serverTime() {
        return ResponseEntity.ok(Map.of("serverTimeMs", Instant.now().toEpochMilli()));
    }
}