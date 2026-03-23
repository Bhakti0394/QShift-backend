package com.prepline.kitchen.slot.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pickup_slots")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickupSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private LocalDateTime slotTime;

    private int maxCapacity;

    private int currentBookings;

    public boolean hasCapacity() {
        return currentBookings < maxCapacity;
    }
}