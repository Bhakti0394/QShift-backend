package com.prepline.kitchen.staff.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "kitchen_staff")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KitchenStaff {

    public enum StaffStatus {
        ACTIVE,
        BACKUP,
        OFF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "integer default 5")
    private int maxConcurrentOrders;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10) default 'BACKUP'")
    @Builder.Default
    private StaffStatus status = StaffStatus.BACKUP;

    @Column(length = 255)
    private String specializations;

    public boolean isOnShift() {
        return status == StaffStatus.ACTIVE;
    }

    public boolean isAvailable() {
        return status == StaffStatus.ACTIVE;
    }

    public boolean isBackup() {
        return status == StaffStatus.BACKUP;
    }

    public boolean specializes(String category) {
        if (specializations == null || category == null) return false;
        for (String tag : specializations.split(",")) {
            if (tag.trim().equalsIgnoreCase(category)) return true;
        }
        return false;
    }
}