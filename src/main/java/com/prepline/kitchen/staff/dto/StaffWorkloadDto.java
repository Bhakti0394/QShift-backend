package com.prepline.kitchen.staff.dto;

import lombok.*;

/**
 * Live workload snapshot for one chef.
 * Returned as part of KanbanBoardResponse and by GET /api/kitchen/staff.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffWorkloadDto {

    private String chefId;
    private String name;
    private int activeOrders;
    private int maxCapacity;

    /** 0–100 integer. Computed as Math.round((activeOrders / maxCapacity) * 100). */
    private int loadPercent;

    /**
     * False after ❌ is confirmed mid-shift.
     * Frontend hides off-shift chefs from assignment dropdowns
     * and shows them in a separate "Backup" section.
     */
    private boolean onShift;

    /**
     * Number of orders this chef completed today (since midnight local time).
     * Computed in StaffCapacityService.toWorkloadDto() via OrderRepository.
     * Frontend displays this directly — no local calculation.
     */
    private int completedToday;

    /**
     * Load status derived from loadPercent — computed once in toWorkloadDto(),
     * never re-derived in the frontend.
     *   available = loadPercent < 50
     *   busy      = loadPercent >= 50 && < 100
     *   full      = loadPercent >= 100
     */
    private String status;
}