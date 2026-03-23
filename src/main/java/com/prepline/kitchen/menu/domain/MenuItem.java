package com.prepline.kitchen.menu.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "menu_items")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private int prepTimeMinutes;

    private boolean available;

    /** Price in rupees (INR). Null = not yet seeded. */
    private Integer price;

    /** UI category label e.g. "North Indian", "Biryani". Nullable. */
    private String category;

    /** Public URL or relative path to the dish image. Nullable. */
    @Column(name = "image_url")
    private String imageUrl;

    /**
     * FIX: @JsonProperty("isExpress") is required here.
     *
     * Java's Jackson ObjectMapper applies JavaBean naming conventions to
     * primitive boolean fields. A field named "isExpress" has a getter
     * "isExpress()" — Jackson strips the "is" prefix from the getter name
     * and serializes the JSON key as "express" not "isExpress".
     *
     * The frontend BackendMenuItem interface expects "isExpress". Without
     * this annotation the frontend always receives undefined for isExpress,
     * the fallback (prepTime <= 15) takes over, and meals with prepTime > 15
     * that are genuinely express (e.g. seeded with isExpress=true) lose their
     * EXPRESS badge and are excluded from the Express filter.
     *
     * @JsonProperty("isExpress") forces Jackson to use "isExpress" as the
     * JSON field name regardless of the getter convention.
     */
    @JsonProperty("isExpress")
    @Column(name = "is_express", columnDefinition = "boolean default false")
    private boolean isExpress;
}