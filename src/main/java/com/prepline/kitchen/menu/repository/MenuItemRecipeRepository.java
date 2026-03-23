package com.prepline.kitchen.menu.repository;

import com.prepline.kitchen.menu.domain.MenuItemRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MenuItemRecipeRepository extends JpaRepository<MenuItemRecipe, UUID> {
    List<MenuItemRecipe> findByMenuItemId(UUID menuItemId);
}