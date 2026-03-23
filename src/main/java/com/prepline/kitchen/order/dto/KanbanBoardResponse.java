package com.prepline.kitchen.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prepline.kitchen.metrics.dto.KitchenMetricsDto;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.slot.dto.SlotCapacityDto;
import com.prepline.kitchen.staff.dto.StaffWorkloadDto;

import java.util.List;
import java.util.Map;

/**
 * Full board snapshot returned by GET /api/kitchen/board.
 *
 * FIX: The columns map uses OrderStatus enum as key.
 * Without @JsonSerialize, Jackson serializes enum keys as their ordinal index
 * (0, 1, 2, 3) instead of their name (PENDING, COOKING, READY, COMPLETED).
 * The frontend then can't find board.columns.PENDING — it's undefined.
 *
 * Solution: flatten the map into explicit named fields so there is zero
 * ambiguity regardless of Jackson version or configuration.
 */
public class KanbanBoardResponse {

    @JsonProperty("columns")
    private final Columns columns;

    @JsonProperty("metrics")
    private final KitchenMetricsDto metrics;

    @JsonProperty("staff")
    private final List<StaffWorkloadDto> staff;

    @JsonProperty("upcomingSlots")
    private final List<SlotCapacityDto> upcomingSlots;

    /**
     * Primary constructor — accepts the raw OrderStatus-keyed map from
     * OrderQueryService and converts it to the explicit Columns wrapper.
     */
    public KanbanBoardResponse(
            Map<OrderStatus, List<OrderCardDto>> columnMap,
            KitchenMetricsDto metrics,
            List<StaffWorkloadDto> staff,
            List<SlotCapacityDto> upcomingSlots) {

        this.columns = new Columns(
                columnMap.getOrDefault(OrderStatus.PENDING,   List.of()),
                columnMap.getOrDefault(OrderStatus.COOKING,   List.of()),
                columnMap.getOrDefault(OrderStatus.READY,     List.of()),
                columnMap.getOrDefault(OrderStatus.COMPLETED, List.of())
        );
        this.metrics      = metrics;
        this.staff        = staff;
        this.upcomingSlots = upcomingSlots;
    }

    public Columns getColumns()              { return columns;       }
    public KitchenMetricsDto getMetrics()    { return metrics;       }
    public List<StaffWorkloadDto> getStaff() { return staff;         }
    public List<SlotCapacityDto> getUpcomingSlots() { return upcomingSlots; }

    // ── Explicit columns wrapper ──────────────────────────────────────────────
    // Each field name matches exactly what the frontend expects:
    //   board.columns.PENDING, board.columns.COOKING, etc.

    public static class Columns {

        @JsonProperty("PENDING")
        private final List<OrderCardDto> PENDING;

        @JsonProperty("COOKING")
        private final List<OrderCardDto> COOKING;

        @JsonProperty("READY")
        private final List<OrderCardDto> READY;

        @JsonProperty("COMPLETED")
        private final List<OrderCardDto> COMPLETED;

        public Columns(
                List<OrderCardDto> pending,
                List<OrderCardDto> cooking,
                List<OrderCardDto> ready,
                List<OrderCardDto> completed) {
            this.PENDING   = pending;
            this.COOKING   = cooking;
            this.READY     = ready;
            this.COMPLETED = completed;
        }

        public List<OrderCardDto> getPENDING()   { return PENDING;   }
        public List<OrderCardDto> getCOOKING()   { return COOKING;   }
        public List<OrderCardDto> getREADY()     { return READY;     }
        public List<OrderCardDto> getCOMPLETED() { return COMPLETED; }
    }
}