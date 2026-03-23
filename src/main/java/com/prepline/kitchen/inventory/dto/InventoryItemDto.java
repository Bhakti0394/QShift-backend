package com.prepline.kitchen.inventory.dto;

import com.prepline.kitchen.inventory.domain.InventoryCategory;
import com.prepline.kitchen.inventory.domain.InventoryItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class InventoryItemDto {

    private String     id;
    private String     name;
    private String     category;
    private double     currentStock;
    private double     maxCapacity;
    private String     unit;
    private double     minThreshold;
    private double     criticalThreshold;
    private BigDecimal costPerUnit;
    private String     supplier;
    private Instant    lastRestocked;
    private Instant    expiryDate;
    private String     stockStatus;

    // ── Factory ───────────────────────────────────────────────────────────────

    public static InventoryItemDto from(InventoryItem item) {
        InventoryItemDto dto = new InventoryItemDto();
        dto.id                = item.getId();
        dto.name              = item.getName();
        dto.category          = item.getCategory().name().toLowerCase();
        dto.currentStock      = round2(item.getCurrentStock());
        dto.maxCapacity       = round2(item.getMaxCapacity());
        dto.unit              = item.getUnit();
        dto.minThreshold      = round2(item.getMinThreshold());
        dto.criticalThreshold = round2(item.getCriticalThreshold());
        dto.costPerUnit       = item.getCostPerUnit();
        dto.supplier          = item.getSupplier();
        dto.lastRestocked     = item.getLastRestocked();
        dto.expiryDate        = item.getExpiryDate();
        dto.stockStatus       = computeStatus(item);
        return dto;
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String computeStatus(InventoryItem item) {
        if (item.getCurrentStock() <= 0)                           return "out-of-stock";
        if (item.getCurrentStock() <= item.getCriticalThreshold()) return "critical";
        if (item.getCurrentStock() <= item.getMinThreshold())      return "low-stock";
        return "in-stock";
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String     getId()                { return id; }
    public String     getName()              { return name; }
    public String     getCategory()          { return category; }
    public double     getCurrentStock()      { return currentStock; }
    public double     getMaxCapacity()       { return maxCapacity; }
    public String     getUnit()              { return unit; }
    public double     getMinThreshold()      { return minThreshold; }
    public double     getCriticalThreshold() { return criticalThreshold; }
    public BigDecimal getCostPerUnit()       { return costPerUnit; }
    public String     getSupplier()          { return supplier; }
    public Instant    getLastRestocked()     { return lastRestocked; }
    public Instant    getExpiryDate()        { return expiryDate; }
    public String     getStockStatus()       { return stockStatus; }
}