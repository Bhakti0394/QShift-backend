package com.prepline.kitchen.menu.domain;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "menu_item_recipes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItemRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(name = "inventory_item_id", nullable = false)
    private String inventoryItemId;

    @Column(name = "inventory_item_name")
    private String inventoryItemName;

    @Column(nullable = false)
    private double quantity;
}