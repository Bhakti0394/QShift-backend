package com.prepline.kitchen.staff.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * ✅ NEW FILE — create at:
 * src/main/java/com/prepline/kitchen/staff/dto/AutoAssignResultDto.java
 *
 * Returned by StaffCapacityService.autoAssignOrders().
 *
 * updatedStaff        — live workload snapshot after assignments (for UI refresh)
 * promotedOrders      — IDs of orders that moved PENDING → COOKING this run
 * promotedCount       — how many orders were promoted this run
 * remainingQueueCount — how many PENDING orders are still waiting for a slot
 */
@Getter
@Builder
public class AutoAssignResultDto {

    private final List<StaffWorkloadDto> updatedStaff;
    private final List<UUID>             promotedOrders;
    private final int                    promotedCount;
    private final int                    remainingQueueCount;
}