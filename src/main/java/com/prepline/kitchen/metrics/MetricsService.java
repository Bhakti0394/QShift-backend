package com.prepline.kitchen.metrics;

import com.prepline.kitchen.metrics.dto.KitchenMetricsDto;
import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.repository.OrderRepository;
import com.prepline.kitchen.staff.domain.KitchenStaff;
import com.prepline.kitchen.staff.domain.KitchenStaff.StaffStatus;
import com.prepline.kitchen.staff.repository.KitchenStaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MetricsService {

    private final OrderRepository        orderRepository;
    private final KitchenStaffRepository staffRepository;

    // FIX: Use IST explicitly so startOfDay is always midnight Pune/Kolkata time,
    // not midnight UTC (which is 05:30 IST — causing metrics to show 0 all day).
    private static final ZoneId KITCHEN_ZONE     = ZoneId.of("Asia/Kolkata");
    private static final int    STALE_MULTIPLIER = 3;

    public KitchenMetricsDto computeMetrics(LocalDate date) {

        Instant startOfDay = date.atStartOfDay(KITCHEN_ZONE).toInstant();
        Instant endOfDay   = date.plusDays(1).atStartOfDay(KITCHEN_ZONE).toInstant();

        List<Order> completed = orderRepository.findCompletedOnDate(startOfDay, endOfDay);

        // ── Avg cook time ────────────────────────────────────────────────────
        // FIX: extracted to cookMinutes() method — lambda body had a local
        // variable 'actualMins' inside a nested if, which IntelliJ couldn't
        // resolve as effectively final for use in the mapToDouble stream.
        double avgCookTimeMinutes = completed.stream()
                .mapToDouble(this::cookMinutes)
                .filter(mins -> mins >= 0)
                .average()
                .orElse(0.0);

        // ── Efficiency (on-time rate) ────────────────────────────────────────
        double efficiencyPercent = completed.isEmpty() ? 0.0
                : Math.round(completed.stream().filter(this::wasOnTime).count() * 100.0
                / completed.size());

        // ── Capacity utilisation ─────────────────────────────────────────────
        List<KitchenStaff> activeStaff = staffRepository.findByStatus(StaffStatus.ACTIVE);
        int totalSlots = activeStaff.stream().mapToInt(KitchenStaff::getMaxConcurrentOrders).sum();
        int cookingNow = orderRepository.countByStatus(OrderStatus.COOKING);
        int pendingNow = orderRepository.countByStatus(OrderStatus.PENDING);
        int activeLoad = cookingNow + pendingNow;

        double capacityUtilizationPercent = totalSlots == 0 ? 0.0
                : Math.min(Math.round((activeLoad * 100.0) / totalSlots), 100.0);

        // ── Late orders count ────────────────────────────────────────────────
        Instant now = Instant.now();
        int lateOrdersCount;
        try {
            lateOrdersCount = (int) orderRepository.countLateOrdersCooking();
        } catch (Exception e) {
            lateOrdersCount = (int) orderRepository.findByStatus(OrderStatus.COOKING).stream()
                    .filter(o -> o.getCookingStartedAt() != null
                            && o.getTotalPrepTimeMinutes() != null
                            && Duration.between(o.getCookingStartedAt(), now).toMinutes()
                            > o.getTotalPrepTimeMinutes())
                    .count();
        }

        int totalOrdersToday = orderRepository.countPlacedOnDate(startOfDay, endOfDay);

        return new KitchenMetricsDto(
                avgCookTimeMinutes,
                efficiencyPercent,
                capacityUtilizationPercent,
                completed.size(),
                lateOrdersCount,
                activeStaff.size(),
                totalOrdersToday
        );
    }

    // ── cookMinutes: extracted from lambda for correct variable scoping ──────
    private double cookMinutes(Order o) {
        if (o.getCookingStartedAt() != null && o.getReadyAt() != null) {
            long actualMins = Duration.between(o.getCookingStartedAt(), o.getReadyAt()).toMinutes();
            if (actualMins >= 0) {
                long staleThreshold = (o.getTotalPrepTimeMinutes() != null
                        && o.getTotalPrepTimeMinutes() > 0)
                        ? (long) o.getTotalPrepTimeMinutes() * STALE_MULTIPLIER
                        : 120L;
                if (actualMins <= staleThreshold) {
                    return actualMins;
                }
            }
        }
        if (o.getTotalPrepTimeMinutes() != null && o.getTotalPrepTimeMinutes() > 0) {
            return o.getTotalPrepTimeMinutes().doubleValue();
        }
        return -1.0;
    }

    // ── wasOnTime ────────────────────────────────────────────────────────────
    private boolean wasOnTime(Order order) {
        if (order.getPickupSlot() != null) {
            if (order.getReadyAt() == null) return false;
            Instant deadline = order.getPickupSlot().getSlotTime()
                    .atZone(KITCHEN_ZONE).toInstant();
            return order.getReadyAt().isBefore(deadline);
        }

        if (order.getTotalPrepTimeMinutes() == null || order.getTotalPrepTimeMinutes() <= 0) {
            return true;
        }

        Instant cookStart = order.getCookingStartedAt() != null
                ? order.getCookingStartedAt() : order.getPlacedAt();
        Instant cookEnd   = order.getReadyAt() != null
                ? order.getReadyAt() : order.getCompletedAt();

        if (cookStart == null || cookEnd == null) return true;

        long actualMins     = Duration.between(cookStart, cookEnd).toMinutes();
        long staleThreshold = (long) order.getTotalPrepTimeMinutes() * STALE_MULTIPLIER;

        if (actualMins > staleThreshold) return true; // zombie — count as on-time
        return actualMins <= order.getTotalPrepTimeMinutes();
    }
}