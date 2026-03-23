package com.prepline.kitchen.order.dto;

import java.util.List;
import java.util.UUID;

/**
 * FIX: Added pickupSlotId so scheduled orders can link a slot at creation time.
 *
 * Flow for SCHEDULED orders:
 *   1. Frontend creates a slot via GET /api/kitchen/board (upcomingSlots)
 *   2. User selects a slot from tomorrow's available windows
 *   3. Frontend sends pickupSlotId in this request
 *   4. OrderService.createOrder() links the slot immediately
 *   5. OrderCardDto.pickupSlotTime is populated → frontend shows real date/time
 *
 * pickupSlotId is nullable — normal and express orders don't use it.
 * Existing callers are unaffected.
 */
public record CreateOrderRequest(
        String     orderRef,
        List<UUID> menuItemIds,
        String     customerName,   // nullable — real customer orders set this from auth context
        UUID       pickupSlotId    // nullable — only set for SCHEDULED orders
) {}