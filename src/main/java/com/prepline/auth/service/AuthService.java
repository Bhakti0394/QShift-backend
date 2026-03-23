package com.prepline.auth.service;

import com.prepline.auth.dto.AuthRequest;
import com.prepline.auth.entity.Admin;
import com.prepline.auth.entity.AdminOtp;
import com.prepline.auth.entity.Customer;
import com.prepline.auth.repository.AdminOtpRepository;
import com.prepline.auth.repository.AdminRepository;
import com.prepline.auth.repository.CustomerRepository;
import com.prepline.auth.security.JwtUtil;
import com.prepline.auth.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CustomerRepository customerRepository;
    private final AdminRepository adminRepository;
    private final AdminOtpRepository otpRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    // --- Customer -------------------------------------------

    public Customer register(AuthRequest.Register request) {
        if (customerRepository.existsByEmail(request.email()))
            throw new RuntimeException("Email already registered");
        return customerRepository.save(Customer.builder()
                .fullName(request.fullName())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .active(true)
                .build());
    }

    public String login(AuthRequest.Login request) {
        Customer customer = customerRepository.findByEmail(request.email())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), customer.getPassword()))
            throw new RuntimeException("Invalid email or password");
        return jwtUtil.generateToken(customer.getEmail(), "CUSTOMER");
    }

    // --- Admin ----------------------------------------------

    public Admin registerAdmin(String fullName, String email, String organisation, String kitchenName) {
        return adminRepository.findByEmail(email)
                .orElseGet(() -> adminRepository.save(Admin.builder()
                        .fullName(fullName).email(email)
                        .organisation(organisation).kitchenName(kitchenName)
                        .active(true).build()));
    }

    public void sendOtp(String email) {
        String otp = String.valueOf(100000 + new Random().nextInt(900000));
        otpRepository.save(AdminOtp.builder()
                .email(email).otp(otp)
                .expiryTime(LocalDateTime.now().plusMinutes(5))
                .used(false).build());
        try {
            emailService.sendOtpEmail(email, otp);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send OTP email: " + e.getMessage());
        }
    }

    public Admin verifyOtp(String email, String otpInput) {
        AdminOtp latest = otpRepository.findTopByEmailOrderByIdDesc(email)
                .orElseThrow(() -> new RuntimeException("OTP not found"));
        if (latest.isUsed()) throw new RuntimeException("OTP already used");
        if (latest.getExpiryTime().isBefore(LocalDateTime.now())) throw new RuntimeException("OTP expired");
        if (!latest.getOtp().equals(otpInput)) throw new RuntimeException("Invalid OTP");
        latest.setUsed(true);
        otpRepository.save(latest);
        return adminRepository.findByEmail(email)
                .orElseGet(() -> adminRepository.save(Admin.builder().email(email).active(true).build()));
    }
}
