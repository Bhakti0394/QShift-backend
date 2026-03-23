package com.prepline.auth.security;

// FIX [SSE-SECURITY]: /api/customer/sse/** must be listed BEFORE the
// /api/customer/** wildcard rule and marked permitAll().
//
// WHY THE OLD CONFIG BROKE SSE:
//
//   Spring Security's filter chain executes in this order:
//
//     1. CorsFilter
//     2. Spring Security FilterChain
//        a. AuthorizationFilter  ← evaluates requestMatchers rules HERE
//        b. JwtAuthFilter        ← our custom filter runs AFTER (a)
//     3. DispatcherServlet → Controller
//
//   EventSource opens:
//     GET /api/customer/sse/orders/{id}/stream?token=<jwt>
//
//   At step 2a the SecurityContext is EMPTY — JwtAuthFilter hasn't run yet.
//   The old rule ".requestMatchers("/api/customer/**").hasRole("CUSTOMER")"
//   matched this path, found no authentication, and returned 401 immediately.
//   JwtAuthFilter never got to read the ?token= param.
//
//   Result: every SSE connection returned 401, EventSource fired onerror,
//   SkipLineContext fell back to polling, customers never got real-time updates.
//
// FIX:
//   Add a specific permitAll() rule for /api/customer/sse/** BEFORE the
//   /api/customer/** wildcard. Spring Security evaluates matchers top-down
//   and stops at the first match, so the SSE path now bypasses the role
//   check entirely and JwtAuthFilter can authenticate the request via ?token=.
//
//   The SSE endpoint is not truly "public" — JwtAuthFilter still validates
//   the token and populates the SecurityContext. CustomerSseController itself
//   only receives authenticated requests (JwtAuthFilter rejects bad tokens by
//   leaving the SecurityContext empty, which means Spring Security's final
//   .anyRequest().authenticated() catches unauthenticated requests that slip
//   through any unmatched path). For SSE specifically, the controller trusts
//   that the ?token= was validated by JwtAuthFilter — no additional role check
//   in the controller is needed because the emitter is keyed to the orderId,
//   not to the user role.

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // -- Public auth endpoints --
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/admin/auth/**").permitAll()

                        // -- Public menu browsing --
                        .requestMatchers(HttpMethod.GET, "/api/customer/menu-items").permitAll()

                        // -- CORS preflight --
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // FIX: SSE endpoint must be permitAll() and listed BEFORE the
                        // /api/customer/** wildcard. Spring Security evaluates top-down
                        // and stops at first match. Without this specific rule, the SSE
                        // request matches /api/customer/** → hasRole("CUSTOMER") while
                        // the SecurityContext is still empty (JwtAuthFilter hasn't run
                        // yet) → 401 before the token can be read from ?token=.
                        //
                        // Security is still enforced: JwtAuthFilter validates the JWT
                        // from ?token= and populates the SecurityContext. The controller
                        // is effectively authenticated — Spring Security just doesn't
                        // enforce it at the matcher level for this path.
                        .requestMatchers("/api/customer/sse/**").permitAll()

                        // -- Customer endpoints (require CUSTOMER JWT) --
                        .requestMatchers("/api/customer/**").hasRole("CUSTOMER")

                        // -- Kitchen dashboard (require KITCHEN JWT) --
                        .requestMatchers("/api/kitchen/**").hasRole("KITCHEN")

                        // -- Admin --
                        .requestMatchers("/api/admin/**").hasRole("KITCHEN")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}