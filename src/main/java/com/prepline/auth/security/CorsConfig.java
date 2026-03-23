package com.prepline.auth.security;

// FIX [CORS-KITCHEN-PORT]: added localhost:5174 to allowed origins.
//
// BEFORE: CorsConfig only listed ports 5173, 8081, and 3000.
//   When both customer and kitchen frontends run simultaneously in dev,
//   Vite starts the customer dashboard on 5173 (first started) and the
//   kitchen dashboard on 5174 (second Vite instance — automatic fallback).
//   The kitchen dashboard's origin "http://localhost:5174" was not in the
//   allowlist, so every kitchen API call was CORS-blocked with a preflight
//   rejection. All kitchen API calls silently failed with no JS error
//   (CORS blocks happen before the browser even sends the request body).
//
// AFTER: both Vite dev ports (5173, 5174) are explicitly listed.
//
// NOTE: if you run the kitchen dashboard on a different port (e.g. via
//   `vite --port 5175`), add that port here and restart the backend.
//
// NOTE: When deploying to production, replace these localhost origins with
//   your actual deployed frontend URLs and remove the localhost entries.

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);

        // FIX: added localhost:5174 for the kitchen dashboard Vite instance.
        // When two Vite apps run simultaneously, the second picks port 5174.
        // Without this the kitchen frontend gets CORS-blocked on every request.
        config.setAllowedOrigins(List.of(
                "http://localhost:5173",   // Vite default (customer dashboard — started first)
                "http://localhost:5174",   // FIX: Vite fallback (kitchen dashboard — started second)
                "http://localhost:8081",   // alternate frontend port
                "http://localhost:3000"    // CRA / storybook
        ));

        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        // These exposed headers are required:
        // Authorization         — JWT token in response (kitchen admin OTP flow)
        // X-Customer-Display-Name — backward-compat header for merged order lookup
        // Cache-Control         — SSE requires "no-cache" to be readable by EventSource
        // Content-Type          — browser must see "text/event-stream" for SSE to work;
        //                         without this exposed the EventSource stream closes immediately
        // Last-Event-ID         — allows SSE reconnection from the last received event ID
        config.setExposedHeaders(List.of(
                "Authorization",
                "X-Customer-Display-Name",
                "Cache-Control",
                "Content-Type",
                "Last-Event-ID"
        ));

        // Cache preflight result for 1 hour to prevent flickering on SSE reconnect.
        // Without this, every SSE reconnect triggers a new OPTIONS preflight
        // which can delay the stream by 100-300 ms.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}