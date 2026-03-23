package com.prepline.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@SuppressWarnings("JpaDataSourceORMInspection")
@Entity
@Table(name = "admin_otp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String otp;

    private boolean used;

    private LocalDateTime expiryTime;
}
