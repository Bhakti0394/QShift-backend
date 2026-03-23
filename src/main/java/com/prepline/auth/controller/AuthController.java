package com.prepline.auth.controller;

import com.prepline.auth.dto.AuthRequest;
import com.prepline.auth.entity.Admin;
import com.prepline.auth.entity.Customer;
import com.prepline.auth.repository.AdminRepository;
import com.prepline.auth.repository.CustomerRepository;
import com.prepline.auth.security.JwtUtil;
import com.prepline.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService      authService;
    private final JwtUtil          jwtUtil;
    private final AdminRepository  adminRepository;
    private final CustomerRepository customerRepository;

    private void setAuthCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie("auth_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(7 * 24 * 60 * 60);
        response.addCookie(cookie);
    }

    // ── Customer ──────────────────────────────────────────────────────────────

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest.Register request) {
        try {
            Customer customer = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    Map.of("message", "Registration successful", "email", customer.getEmail()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * FIX: Customer login now returns the JWT token in the response body
     * in addition to setting it as a cookie.
     *
     * This mirrors the kitchen admin login fix — the cookie alone is
     * unreliable for PATCH/POST due to SameSite browser restrictions.
     * The frontend stores the token in localStorage and sends it as
     * Authorization: Bearer on all subsequent requests.
     *
     * Also returns fullName so the frontend can display the customer's
     * name and use it as customerName when placing orders.
     */
    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest.Login request,
                                   HttpServletResponse response) {
        try {
            String token = authService.login(request);
            setAuthCookie(response, token);

            // Fetch full name for the frontend to store
            String fullName = customerRepository.findByEmail(request.email())
                    .map(c -> c.getFullName())
                    .orElse(request.email());

            return ResponseEntity.ok(Map.of(
                    "message",  "Login successful",
                    "email",    request.email(),
                    "fullName", fullName,
                    "role",     "CUSTOMER",
                    "token",    token          // FIX: return token in body
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
        String role = auth.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("UNKNOWN");
        return ResponseEntity.ok(Map.of("email", auth.getName(), "role", role));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("auth_token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    @PostMapping("/api/admin/auth/register")
    public ResponseEntity<Map<String, Object>> registerAdmin(@RequestBody Map<String, String> body) {
        String email        = body.get("email");
        String fullName     = body.get("fullName");
        String organisation = body.get("organisation");
        String kitchenName  = body.get("kitchenName");

        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Email is required"));
        if (fullName == null || fullName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Full name is required"));
        if (organisation == null || organisation.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Organisation is required"));
        if (kitchenName == null || kitchenName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Kitchen name is required"));

        try {
            Admin admin = authService.registerAdmin(fullName, email, organisation, kitchenName);
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    Map.of("message", "Admin registered. Please login via OTP.",
                            "email", admin.getEmail()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/api/admin/auth/start-login")
    public ResponseEntity<Map<String, Object>> startLogin(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Email is required"));
        if (adminRepository.findByEmail(email).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Email not registered as admin"));
        try {
            authService.sendOtp(email);
            return ResponseEntity.ok(Map.of("message", "OTP sent successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to send OTP: " + e.getMessage()));
        }
    }

    @PostMapping("/api/admin/auth/verify-otp")
    public ResponseEntity<Map<String, Object>> verifyOtp(@RequestBody Map<String, String> body,
                                                         HttpServletResponse response) {
        String email = body.get("email");
        String otp   = body.get("otp");
        if (email == null || email.isBlank() || otp == null || otp.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Email and OTP required"));
        try {
            Admin  admin = authService.verifyOtp(email, otp);
            String token = jwtUtil.generateToken(admin.getEmail(), "KITCHEN");
            setAuthCookie(response, token);
            return ResponseEntity.ok(Map.of(
                    "message", "Login successful",
                    "email",   admin.getEmail(),
                    "role",    "KITCHEN",
                    "token",   token
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}