// ============================================================
// src/main/java/com/prepline/kitchen/inventory/controller/InventoryController.java
// ============================================================
package com.prepline.kitchen.inventory.controller;

import com.prepline.kitchen.inventory.dto.InventoryItemDto;
import com.prepline.kitchen.inventory.dto.RestockRequest;
import com.prepline.kitchen.inventory.dto.UpdateStockRequest;
import com.prepline.kitchen.inventory.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kitchen/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    // GET /api/kitchen/inventory
    @GetMapping
    public List<InventoryItemDto> getAll() {
        return service.getAll();
    }

    // GET /api/kitchen/inventory/:id
    @GetMapping("/{id}")
    public InventoryItemDto getById(@PathVariable String id) {
        return service.getById(id);
    }

    // PATCH /api/kitchen/inventory/:id/stock  { newStock: number }
    @PatchMapping("/{id}/stock")
    public InventoryItemDto updateStock(
            @PathVariable String id,
            @RequestBody UpdateStockRequest req) {
        return service.updateStock(id, req.getNewStock());
    }

    // PATCH /api/kitchen/inventory/:id/restock  { quantity: number }
    @PatchMapping("/{id}/restock")
    public InventoryItemDto restock(
            @PathVariable String id,
            @RequestBody RestockRequest req) {
        return service.restock(id, req.getQuantity());
    }

    // DELETE /api/kitchen/inventory/:id
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}