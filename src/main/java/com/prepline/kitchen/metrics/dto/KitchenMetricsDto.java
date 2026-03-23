package com.prepline.kitchen.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snapshot of kitchen performance metrics returned in the board response.
 *
 * Field names match EXACTLY what the frontend KitchenMetricsDto interface expects.
 * Previously had completedOrderCount — renamed to completedOrdersToday so the
 * frontend counts.completed stat card shows the real number instead of 0.
 */
public record KitchenMetricsDto(

        @JsonProperty("avgCookTimeMinutes")
        double avgCookTimeMinutes,

        @JsonProperty("efficiencyPercent")
        double efficiencyPercent,

        @JsonProperty("capacityUtilizationPercent")
        double capacityUtilizationPercent,

        // ── Renamed from completedOrderCount → completedOrdersToday ──────────
        // Frontend reads boardData.metrics.completedOrdersToday for the
        // "Completed Today" stat card. Old name caused it to always show 0.
        @JsonProperty("completedOrdersToday")
        int completedOrdersToday,

        // ── New fields consumed by the frontend ───────────────────────────────
        @JsonProperty("lateOrdersCount")
        int lateOrdersCount,

        @JsonProperty("activeChefCount")
        int activeChefCount,

        @JsonProperty("totalOrdersToday")
        int totalOrdersToday
) {}