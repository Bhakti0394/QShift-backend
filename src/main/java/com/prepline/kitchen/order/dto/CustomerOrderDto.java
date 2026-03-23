package com.prepline.kitchen.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderItem;
import com.prepline.kitchen.order.domain.OrderStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DTO returned to the customer-facing frontend.
 *
 * Key differences from OrderCardDto (kitchen):
 *   - status is serialised as LOWERCASE ("pending" not "PENDING")
 *     so SkipLineContext.dtoToOrder() statusMap lookup works correctly.
 *   - totalPrice is included so OrderHistory can show spend totals.
 *   - pickupSlotTime is an ISO-8601 Z-suffix string (UTC).
 *
 * FIX: status is lowercase — SkipLineContext maps 'pending'→'confirmed' etc.
 * FIX: totalPrice field added — OrderHistory was always showing "—".
 */
public record CustomerOrderDto(

        @JsonProperty("id")
        String id,

        @JsonProperty("orderRef")
        String orderRef,

        /**
         * FIX: lowercase status so frontend statusMap lookup works.
         * Values: "pending" | "cooking" | "ready" | "completed" | "cancelled"
         */
        @JsonProperty("status")
        String status,

        @JsonProperty("customerName")
        String customerName,

        @JsonProperty("itemSummary")
        List<String> itemSummary,

        /**
         * FIX: totalPrice added.
         * Computed as sum(menuItem.price * quantity) for each order item.
         * Null-safe: items without a price contribute 0.
         */
        @JsonProperty("totalPrice")
        int totalPrice,

        /** ISO-8601 with Z suffix. Null if no slot reserved. */
        @JsonProperty("pickupSlotTime")
        String pickupSlotTime,

        @JsonProperty("totalPrepMinutes")
        int totalPrepMinutes,

        @JsonProperty("placedAt")
        Instant placedAt,

        @JsonProperty("cookingStartedAt")
        Instant cookingStartedAt,

        @JsonProperty("readyAt")
        Instant readyAt,

        @JsonProperty("completedAt")
        Instant completedAt
) {

    private static final ZoneId KITCHEN_ZONE = ZoneId.of("Asia/Kolkata");

    /** Convert a domain Order to a CustomerOrderDto. */
    public static CustomerOrderDto from(Order order) {

        // Item summary: "2x Butter Chicken", "1x Vada Pav" etc.
        List<String> summary = order.getItems() == null ? List.of() :
                order.getItems().stream()
                        .map(i -> i.getQuantity() + "x " + i.getMenuItem().getName())
                        .collect(Collectors.toList());

        // FIX: compute total price from items
        int price = order.getItems() == null ? 0 :
                order.getItems().stream()
                        .mapToInt(i -> {
                            Integer unitPrice = i.getMenuItem().getPrice();
                            return (unitPrice != null ? unitPrice : 0) * i.getQuantity();
                        })
                        .sum();

        // FIX: lowercase status for frontend statusMap compatibility
        String statusLower = order.getStatus() != null
                ? order.getStatus().name().toLowerCase()
                : "pending";

        // Pickup slot: LocalDateTime → Instant using IST zone
        String slotTime = null;
        if (order.getPickupSlot() != null && order.getPickupSlot().getSlotTime() != null) {
            slotTime = order.getPickupSlot().getSlotTime()
                    .atZone(KITCHEN_ZONE)
                    .toInstant()
                    .toString(); // always "2026-03-15T12:30:00Z"
        }

        return new CustomerOrderDto(
                order.getId() != null ? order.getId().toString() : null,
                order.getOrderRef(),
                statusLower,
                order.getCustomerName(),
                summary,
                price,
                slotTime,
                order.getTotalPrepTimeMinutes() != null ? order.getTotalPrepTimeMinutes() : 0,
                order.getPlacedAt(),
                order.getCookingStartedAt(),
                order.getReadyAt(),
                order.getCompletedAt()
        );
    }

    // ── CustomerKitchenSummaryDto ─────────────────────────────────────────────
    // Added here instead of a new file — same package, no new file needed.
    // Powers GET /api/customer/kitchen-summary → KitchenGlance on the
    // customer dashboard.
    //
    // Field names match CustomerKitchenSummaryDto in kitchenApi.ts exactly:
    //   topDishName, topDishOrders
    //   busiestHourTime, busiestHourOrders
    //   avgPrepMinutes
    //   hasBottleneck, bottleneckReason
    public record CustomerKitchenSummaryDto(

            @JsonProperty("topDishName")
            String topDishName,

            @JsonProperty("topDishOrders")
            long topDishOrders,

            @JsonProperty("busiestHourTime")
            String busiestHourTime,

            @JsonProperty("busiestHourOrders")
            long busiestHourOrders,

            @JsonProperty("avgPrepMinutes")
            double avgPrepMinutes,

            @JsonProperty("hasBottleneck")
            boolean hasBottleneck,

            // null when hasBottleneck == false
            @JsonProperty("bottleneckReason")
            String bottleneckReason
    ) {}
}