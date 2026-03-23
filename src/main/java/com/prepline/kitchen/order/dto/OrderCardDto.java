package com.prepline.kitchen.order.dto;

import com.prepline.kitchen.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO sent to the frontend for every order card on the Kanban board.
 *
 * TIMESTAMP FIELDS — all sent as raw Instant (ISO-8601 string in JSON):
 *   placedAt         → when order was created (PENDING entry)
 *   cookingStartedAt → when chef started cooking (COOKING entry)
 *   readyAt          → when order was marked ready (READY entry)
 *   completedAt      → when order was completed (COMPLETED entry)
 *
 * The frontend uses these 4 timestamps to compute all timers:
 *   - Pending countdown  : pickupSlotTime - now
 *   - Cooking elapsed    : now - cookingStartedAt
 *   - Cooking ETA        : estimatedPrepTime - (now - cookingStartedAt)
 *   - Ready elapsed      : now - readyAt
 *   - Completed cookTime : completedAt - cookingStartedAt
 *
 * elapsedMinutes: cooking duration in MINUTES (cookingStartedAt → readyAt/completedAt).
 * Sent as a pre-computed convenience value for completed orders.
 * Active orders should use raw timestamps for live countdown accuracy.
 *
 * orderType: derived from the orderRef suffix at query time.
 *   '-EXPRESS'   → "EXPRESS"
 *   '-SCHEDULED' → "SCHEDULED"
 *   anything else → "NORMAL"
 * The frontend resolveOrderType() reads this field first so it never falls
 * back to the hash table — express priority is always authoritative.
 */
public record OrderCardDto(
        UUID         id,
        String       orderRef,
        OrderStatus  status,
        List<String> itemSummary,
        String       customerName,
        String       assignedChefName,
        String       assignedChefId,
        String       pickupSlotTime,      // ISO-8601 instant string, null if no slot
        int          totalPrepMinutes,
        Instant      placedAt,
        Instant      cookingStartedAt,    // null until chef starts cooking
        Instant      readyAt,             // null until marked ready
        Instant      completedAt,         // null until completed
        int          elapsedMinutes,      // cooking duration in MINUTES
        boolean      isLate,
        String       orderType            // "EXPRESS" | "NORMAL" | "SCHEDULED" — derived from orderRef
) {}