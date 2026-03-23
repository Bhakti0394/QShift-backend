package com.prepline.auth.entity;

import jakarta.persistence.*;
import lombok.*;

@SuppressWarnings("JpaDataSourceORMInspection")
@Entity
@Table(name = "admins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    @Column(unique = true, nullable = false)
    private String email;

    private String organisation;

    private String kitchenName;

    private String password;

    private boolean active;
}
