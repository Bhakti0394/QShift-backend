package com.prepline.kitchen.order.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events controller for real-time order status updates.
 *
 * Base path: /api/customer/sse — avoids route conflict with
 * CustomerOrderController's /api/customer/orders/{id}.
 *
 * FIX: EventSource (browser API) cannot send custom Authorization headers.
 * The JWT token is accepted via ?token= query parameter for this endpoint only.
 * SecurityConfig must permit this URL pattern and the JwtAuthFilter must
 * extract the token from the query param when the Authorization header is absent.
 *
 * See JwtAuthFilter for the corresponding fix:
 *   String token = request.getHeader("Authorization");
 *   if (token == null) token = request.getParameter("token"); // SSE fallback
 *
 * pushStatusUpdate() is called by OrderService.transition() — unchanged.
 */
@Slf4j
@RestController
@RequestMapping("/api/customer/sse")
public class CustomerSseController {

    private static final Map<String, SseEmitter> EMITTERS      = new ConcurrentHashMap<>();
    private static final long                    TIMEOUT_MS     = 30 * 60 * 1000L; // 30 min

    /**
     * GET /api/customer/sse/orders/{orderId}/stream
     *
     * Opens an SSE stream for the given orderId.
     * Authentication is enforced by SecurityConfig — the JWT token must be
     * present either in the Authorization header or the ?token= query parameter.
     */
    @GetMapping(value = "/orders/{orderId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String orderId) {

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        Runnable cleanup = () -> {
            EMITTERS.remove(orderId, emitter);
            log.debug("[SSE] Emitter removed for order {}", orderId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        EMITTERS.put(orderId, emitter);
        log.info("[SSE] Customer subscribed to order {}", orderId);

        // Send initial "connected" event so the frontend knows the stream is live
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"orderId\":\"" + orderId + "\"}"));
        } catch (IOException e) {
            log.warn("[SSE] Could not send connected event for {}: {}", orderId, e.getMessage());
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * Called by OrderService.transition() whenever an order status changes.
     * Sends a "status_update" SSE event to the subscribed customer (if any).
     *
     * Event payload: {"orderId":"<uuid>","status":"cooking"}
     * Status is lowercase to match frontend statusMap keys in SkipLineContext.tsx.
     */
    public static void pushStatusUpdate(String orderId, Object status) {
        SseEmitter emitter = EMITTERS.get(orderId);
        if (emitter == null) return;

        String statusStr = status.toString().toLowerCase();
        String payload   = "{\"orderId\":\"" + orderId + "\",\"status\":\"" + statusStr + "\"}";

        try {
            emitter.send(SseEmitter.event()
                    .name("status_update")
                    .data(payload));
            log.debug("[SSE] Pushed status '{}' to order {}", statusStr, orderId);
        } catch (IOException e) {
            log.warn("[SSE] Push failed for order {} — removing emitter: {}", orderId, e.getMessage());
            EMITTERS.remove(orderId, emitter);
            emitter.completeWithError(e);
        }
    }
}