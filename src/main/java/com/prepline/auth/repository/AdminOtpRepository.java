package com.prepline.auth.repository;

import com.prepline.auth.entity.AdminOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AdminOtpRepository extends JpaRepository<AdminOtp, Long> {

    Optional<AdminOtp> findByEmail(String email);

    // 🔥 add this
    Optional<AdminOtp> findTopByEmailOrderByIdDesc(String email);
}
