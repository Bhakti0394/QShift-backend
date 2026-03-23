package com.prepline.kitchen.staff.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

/**
 * Returned by GET /api/kitchen/staff/{id}/validate-removal
 * BEFORE the admin confirms the ❌ action.
 *
 * The frontend uses this to render the confirmation modal with real impact numbers.
 * Only after the admin confirms does the frontend call PATCH /remove-from-shift.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffRemovalValidationDto {

    /** Whether the removal is permitted at all. */
    private boolean canRemove;

    /**
     * Hard-blocked: true only when this chef is the LAST one on shift
     * and orders are currently in COOKING status.
     * When blocked=true, the confirm button is hidden entirely.
     */
    private boolean blocked;

    /** Human-readable block reason. Non-null only when blocked=true. */
    private String blockReason;

    /** Number of COOKING orders that will be auto-reassigned to another chef. */
    private int ordersToReassign;

    /** Estimated additional minutes of delay caused by reassignment. */
    private int estimatedDelayMinutes;

    /** Kitchen capacity value AFTER this staff member is removed. */
    private int newCapacity;

    /** Number of PENDING orders that exceed the new queue buffer after removal. */
    private int ordersToThrottle;

    /** IDs of cooking orders that will be reassigned (used for card highlighting in UI). */
    private List<UUID> affectedOrderIds;
}