package com.prepline.kitchen.order.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.prepline.kitchen.slot.domain.PickupSlot;
import com.prepline.kitchen.staff.domain.KitchenStaff;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// FIX [B4]: Added @Index on status and placedAt columns.
// findByStatus() is called on every board poll (every 10s) and on every
// order transition. Without an index this is a full table scan. At 200+
// orders/day over a week the table grows to ~1400 rows – still fast, but
// indexing now avoids a painful migration later and keeps query plans stable.
// placedAt is indexed for the simulate-advance sort (ORDER BY placedAt) and
// for the completed-today query (WHERE placedAt >= startOfToday).

@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_status",    columnList = "status"),
                @Index(name = "idx_orders_placed_at", columnList = "placedAt")
        }
)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String orderRef;

    @Column(name = "customer_name")
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    // EAGER: every order access needs chef name for the card – avoids N+1
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_chef_id")
    private KitchenStaff assignedChef;

    // EAGER: pickup slot time shown on every card – avoids N+1
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "pickup_slot_id")
    private PickupSlot pickupSlot;

    // Explicitly LAZY – items must ALWAYS be loaded via JOIN FETCH in repository
    // queries (findByStatusWithItems). Never call order.getItems() outside a
    // JOIN FETCH query or LazyInitializationException will be thrown once the
    // transaction closes.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items;

    private Instant placedAt;
    private Instant cookingStartedAt;
    private Instant readyAt;
    private Instant completedAt;

    private Integer totalPrepTimeMinutes;

    @Version
    private Long version;
}