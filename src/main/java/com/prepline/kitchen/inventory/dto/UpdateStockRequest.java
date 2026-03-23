// ============================================================
// src/main/java/com/prepline/kitchen/inventory/dto/UpdateStockRequest.java
// ============================================================
package com.prepline.kitchen.inventory.dto;

public class UpdateStockRequest {
    private double newStock;
    public double getNewStock() { return newStock; }
    public void   setNewStock(double newStock) { this.newStock = newStock; }
}