// ============================================================
// src/main/java/com/prepline/kitchen/inventory/domain/InventoryItem.java
// ============================================================
package com.prepline.kitchen.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private InventoryCategory category;

    @Column(nullable = false)
    private double currentStock;

    @Column(nullable = false)
    private double maxCapacity;

    @Column(nullable = false)
    private String unit;

    @Column(nullable = false)
    private double minThreshold;

    @Column(nullable = false)
    private double criticalThreshold;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal costPerUnit;

    private String supplier;

    @Column(nullable = false)
    private Instant lastRestocked;

    private Instant expiryDate;

    // ── Getters & Setters ─────────────────────────────────────────────────

    public String getId()                        { return id; }
    public void   setId(String id)               { this.id = id; }

    public String getName()                      { return name; }
    public void   setName(String name)           { this.name = name; }

    public InventoryCategory getCategory()                         { return category; }
    public void              setCategory(InventoryCategory category) { this.category = category; }

    public double getCurrentStock()                  { return currentStock; }
    public void   setCurrentStock(double currentStock) { this.currentStock = currentStock; }

    public double getMaxCapacity()                   { return maxCapacity; }
    public void   setMaxCapacity(double maxCapacity) { this.maxCapacity = maxCapacity; }

    public String getUnit()                      { return unit; }
    public void   setUnit(String unit)           { this.unit = unit; }

    public double getMinThreshold()                    { return minThreshold; }
    public void   setMinThreshold(double minThreshold) { this.minThreshold = minThreshold; }

    public double getCriticalThreshold()                       { return criticalThreshold; }
    public void   setCriticalThreshold(double criticalThreshold) { this.criticalThreshold = criticalThreshold; }

    public BigDecimal getCostPerUnit()                       { return costPerUnit; }
    public void       setCostPerUnit(BigDecimal costPerUnit) { this.costPerUnit = costPerUnit; }

    public String getSupplier()                      { return supplier; }
    public void   setSupplier(String supplier)       { this.supplier = supplier; }

    public Instant getLastRestocked()                        { return lastRestocked; }
    public void    setLastRestocked(Instant lastRestocked)   { this.lastRestocked = lastRestocked; }

    public Instant getExpiryDate()                   { return expiryDate; }
    public void    setExpiryDate(Instant expiryDate) { this.expiryDate = expiryDate; }
}