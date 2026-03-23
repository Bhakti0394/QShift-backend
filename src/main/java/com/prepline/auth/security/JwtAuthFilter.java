package com.prepline.auth.security;

// FIX [SSE-TOKEN-QUERY-PARAM]: JwtAuthFilter now reads the JWT from the
// ?token= query parameter as a fallback when the Authorization header is absent.
//
// WHY THIS IS NEEDED:
//   The browser's EventSource API does not support custom headers. When a
//   customer subscribes to SSE via:
//     new EventSource("/api/customer/sse/orders/{id}/stream?token=<jwt>")
//   the request arrives with NO Authorization header — only the query param.
//
//   Without this fix, JwtAuthFilter would see no Authorization header,
//   skip JWT parsing entirely, and the SecurityContext would contain
//   "anonymousUser". The SSE stream would then open successfully (the
//   controller sends the "connected" event) but pushStatusUpdate() would
//   have no authenticated principal to associate with, and — depending on
//   SecurityConfig — the stream may be closed by Spring Security immediately
//   after the first event with a 401, or the order-status pushes would
//   work but be attributed to the wrong user.
//
// SECURITY NOTE:
//   Passing a JWT in a URL query parameter is less secure than a header
//   because URLs can appear in server access logs, browser history, and
//   Referer headers. This is acceptable here because:
//     1. SSE is the only endpoint that does this.
//     2. The JWT is short-lived (24h per application.yaml).
//     3. The URL is only accessed from the customer's own browser session.
//     4. In production, use HTTPS to encrypt the URL in transit.
//   Do NOT use query-param tokens for any non-SSE endpoint.

import com.prepline.auth.security.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(
            HttpServletRequest  request,
            HttpServletResponse response,
            FilterChain         filterChain
    ) throws ServletException, IOException {

        String token = null;

        // 1. Try Authorization: Bearer header (standard path for all REST calls)
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }

        // 2. FIX: fall back to ?token= query param for SSE connections.
        //    EventSource cannot send custom headers, so the frontend appends
        //    the JWT as a query parameter for /api/customer/sse/** endpoints.
        if (token == null) {
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                token = queryToken;
                log.debug("[JwtAuthFilter] Token read from query param for SSE path: {}",
                        request.getRequestURI());
            }
        }

        // 3. Try HTTP-only cookie (set by AuthController on login — used by
        //    browsers that don't send the Authorization header, e.g. first-load)
        if (token == null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("auth_token".equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }

        // 4. If we have a token, validate it and set the SecurityContext
        if (token != null && !token.isBlank()) {
            try {
                String email = jwtUtil.extractEmail(token);
                String role  = jwtUtil.extractRole(token);

                if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    email,                  // principal — retrieved by auth.getName()
                                    null,                   // credentials (not stored)
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                            );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid or expired token — proceed as anonymous.
                // Do NOT send 401 here; Spring Security's access rules handle
                // the 401/403 response if the endpoint requires authentication.
                log.debug("[JwtAuthFilter] Invalid JWT: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}