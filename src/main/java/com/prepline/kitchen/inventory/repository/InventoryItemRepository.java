// ============================================================
// src/main/java/com/prepline/kitchen/inventory/repository/InventoryItemRepository.java
// ============================================================
package com.prepline.kitchen.inventory.repository;

import com.prepline.kitchen.inventory.domain.InventoryCategory;
import com.prepline.kitchen.inventory.domain.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    List<InventoryItem> findByCategory(InventoryCategory category);

    // Items below their minThreshold
    @Query("SELECT i FROM InventoryItem i WHERE i.currentStock <= i.minThreshold")
    List<InventoryItem> findLowStockItems();

    // Items at or below criticalThreshold (includes out-of-stock)
    @Query("SELECT i FROM InventoryItem i WHERE i.currentStock <= i.criticalThreshold")
    List<InventoryItem> findCriticalItems();
}