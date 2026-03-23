package com.prepline.kitchen.inventory.service;

import com.prepline.kitchen.inventory.domain.InventoryItem;
import com.prepline.kitchen.inventory.dto.InventoryItemDto;
import com.prepline.kitchen.inventory.repository.InventoryItemRepository;
import com.prepline.kitchen.menu.domain.MenuItemRecipe;
import com.prepline.kitchen.menu.repository.MenuItemRecipeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class InventoryService {

    private final InventoryItemRepository  repo;
    private final MenuItemRecipeRepository recipeRepo;

    public InventoryService(InventoryItemRepository repo,
                            MenuItemRecipeRepository recipeRepo) {
        this.repo       = repo;
        this.recipeRepo = recipeRepo;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<InventoryItemDto> getAll() {
        return repo.findAll().stream().map(InventoryItemDto::from).toList();
    }

    @Transactional(readOnly = true)
    public InventoryItemDto getById(String id) {
        return repo.findById(id)
                .map(InventoryItemDto::from)
                .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));
    }

    // ── Update stock (absolute value) ─────────────────────────────────────────

    public InventoryItemDto updateStock(String id, double newStock) {
        InventoryItem item = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));
        item.setCurrentStock(Math.max(0, Math.min(newStock, item.getMaxCapacity())));
        return InventoryItemDto.from(repo.save(item));
    }

    // ── Restock (additive) ────────────────────────────────────────────────────

    public InventoryItemDto restock(String id, double quantity) {
        InventoryItem item = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));
        item.setCurrentStock(Math.min(item.getCurrentStock() + quantity, item.getMaxCapacity()));
        item.setLastRestocked(Instant.now());
        return InventoryItemDto.from(repo.save(item));
    }

    // ── Consume by inventory item ID (low-level) ──────────────────────────────

    public void consume(String itemId, double quantity) {
        repo.findById(itemId).ifPresent(item -> {
            double next = Math.max(0, item.getCurrentStock() - quantity);
            item.setCurrentStock(next);
            repo.save(item);
            log.debug("[Inventory] consume {} from '{}' → remaining: {}",
                    quantity, item.getName(), next);
        });
    }

    // ── Consume all ingredients for one menu item ─────────────────────────────
    //
    // Looks up MenuItemRecipe rows for this menuItemId and deducts
    // (recipe.quantity × orderQuantity) from each linked InventoryItem.
    // Silent per-ingredient no-op if no recipe row exists — never throws.

    public void consumeForMenuItem(UUID menuItemId, int orderQuantity) {
        List<MenuItemRecipe> recipes = recipeRepo.findByMenuItemId(menuItemId);
        if (recipes.isEmpty()) {
            log.debug("[Inventory] No recipe for menuItemId={} — skipping", menuItemId);
            return;
        }
        for (MenuItemRecipe recipe : recipes) {
            double toDeduct = recipe.getQuantity() * orderQuantity;
            consume(recipe.getInventoryItemId(), toDeduct);
            log.info("[Inventory] Deducted {} × '{}' for menuItem={}",
                    toDeduct, recipe.getInventoryItemName(), menuItemId);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void delete(String id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("Inventory item not found: " + id);
        }
        repo.deleteById(id);
    }
}