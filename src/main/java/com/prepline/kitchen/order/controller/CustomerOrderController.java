package com.prepline.kitchen.order.controller;

import com.prepline.kitchen.menu.domain.MenuItem;
import com.prepline.kitchen.menu.repository.MenuItemRepository;
import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.dto.CreateOrderRequest;
import com.prepline.kitchen.order.dto.CustomerOrderDto;
import com.prepline.kitchen.order.dto.CustomerOrderDto.CustomerKitchenSummaryDto;
import com.prepline.kitchen.order.repository.OrderRepository;
import com.prepline.kitchen.order.service.OrderService;
import com.prepline.kitchen.order.service.OrderQueryService;
import com.prepline.kitchen.slot.domain.PickupSlot;
import com.prepline.kitchen.slot.repository.PickupSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerOrderController {

    private static final ZoneId  KITCHEN_ZONE  = ZoneId.of("Asia/Kolkata");
    private static final String  FALLBACK_DISH = "Butter Chicken";

    private final OrderRepository      orderRepository;
    private final OrderService         orderService;
    private final OrderQueryService    orderQueryService;
    private final MenuItemRepository   menuItemRepository;
    private final PickupSlotRepository pickupSlotRepository;

    // ── helper ────────────────────────────────────────────────────────────────
    private String callerEmail(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        return auth.getName();
    }

    // ── GET /api/customer/menu-items ──────────────────────────────────────────
    @GetMapping("/menu-items")
    public ResponseEntity<List<CustomerMenuItemDto>> getMenuItems() {
        List<CustomerMenuItemDto> items = menuItemRepository.findByAvailableTrue()
                .stream()
                .map(CustomerMenuItemDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(items);
    }

    // ── GET /api/customer/orders ──────────────────────────────────────────────
    @GetMapping("/orders")
    public ResponseEntity<?> getMyOrders(Authentication auth) {
        String email = callerEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
        return ResponseEntity.ok(orderQueryService.getOrdersForCustomer(email));
    }

    // ── GET /api/customer/orders/{orderId} ────────────────────────────────────
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId, Authentication auth) {
        String email = callerEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
        return ResponseEntity.ok(orderQueryService.getOrderForCustomer(orderId, email));
    }

    // ── POST /api/customer/orders ─────────────────────────────────────────────
    @PostMapping("/orders")
    public ResponseEntity<?> placeOrder(@RequestBody CreateOrderRequest req, Authentication auth) {
        String email = callerEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
        Order order = orderService.createOrder(
                req.orderRef(), req.menuItemIds(), email, req.pickupSlotId());
        return ResponseEntity.ok(CustomerOrderDto.from(order));
    }

    // ── GET /api/customer/metrics ─────────────────────────────────────────────
    @GetMapping("/metrics")
    public ResponseEntity<?> getCustomerMetrics(Authentication auth) {
        String email = callerEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));

        List<CustomerOrderDto> orders = orderQueryService.getOrdersForCustomer(email);
        LocalDate now = LocalDate.now(KITCHEN_ZONE);

        long ordersThisMonth = orders.stream()
                .filter(o -> {
                    Instant placed = o.placedAt();
                    if (placed == null) return false;
                    LocalDate d = placed.atZone(KITCHEN_ZONE).toLocalDate();
                    return d.getMonth() == now.getMonth() && d.getYear() == now.getYear();
                }).count();

        double timeSaved = orders.stream()
                .mapToDouble(o -> o.totalPrepMinutes() > 0
                        ? Math.floor(o.totalPrepMinutes() * 0.8) : 10)
                .sum();

        long loyaltyPoints = orders.stream()
                .mapToLong(o -> (long) Math.floor(o.totalPrice() / 10.0))
                .sum();

        double foodWasteReduced = Math.round(orders.size() * 0.15 * 100.0) / 100.0;

        return ResponseEntity.ok(Map.of(
                "ordersThisMonth",  ordersThisMonth,
                "timeSaved",        (long) timeSaved,
                "loyaltyPoints",    loyaltyPoints,
                "foodWasteReduced", foodWasteReduced
        ));
    }

    // ── GET /api/customer/streak ──────────────────────────────────────────────
    @GetMapping("/streak")
    public ResponseEntity<?> getCustomerStreak(Authentication auth) {
        String email = callerEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));

        List<CustomerOrderDto> orders = orderQueryService.getOrdersForCustomer(email);

        List<LocalDate> dates = orders.stream()
                .map(o -> {
                    Instant placed = o.placedAt();
                    if (placed == null) return null;
                    return placed.atZone(KITCHEN_ZONE).toLocalDate();
                })
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        if (dates.isEmpty()) return ResponseEntity.ok(Map.of("streak", 0));

        LocalDate today = LocalDate.now(KITCHEN_ZONE);
        if (dates.get(0).isBefore(today.minusDays(1)))
            return ResponseEntity.ok(Map.of("streak", 0));

        int streak = 0;
        LocalDate expected = dates.get(0);
        for (LocalDate date : dates) {
            if (date.equals(expected)) { streak++; expected = expected.minusDays(1); }
            else break;
        }
        return ResponseEntity.ok(Map.of("streak", streak));
    }

    // ── GET /api/customer/kitchen-summary ─────────────────────────────────────
    @GetMapping("/kitchen-summary")
    public ResponseEntity<CustomerKitchenSummaryDto> getKitchenSummary() {
        LocalDate today      = LocalDate.now(KITCHEN_ZONE);
        Instant   startOfDay = today.atStartOfDay(KITCHEN_ZONE).toInstant();
        Instant   endOfDay   = today.plusDays(1).atStartOfDay(KITCHEN_ZONE).toInstant();

        List<Order> todayOrders = orderRepository.findPlacedBetween(startOfDay, endOfDay);

        Map<String, Long> dishCounts = todayOrders.stream()
                .flatMap(o -> o.getItems().stream())
                .collect(Collectors.groupingBy(
                        item -> item.getMenuItem().getName(),
                        Collectors.summingLong(item -> item.getQuantity())));

        String topDishName = FALLBACK_DISH;
        long   topDishOrders = 0L;
        if (!dishCounts.isEmpty()) {
            Map.Entry<String, Long> top = Collections.max(
                    dishCounts.entrySet(), Map.Entry.comparingByValue());
            topDishName = top.getKey();
            topDishOrders = top.getValue();
        }

        Map<Integer, Long> hourCounts = todayOrders.stream()
                .collect(Collectors.groupingBy(
                        o -> o.getPlacedAt().atZone(KITCHEN_ZONE).getHour(),
                        Collectors.counting()));

        String busiestHourTime = "—";
        long   busiestHourOrders = 0L;
        if (!hourCounts.isEmpty()) {
            Map.Entry<Integer, Long> b = Collections.max(
                    hourCounts.entrySet(), Map.Entry.comparingByValue());
            int h = b.getKey();
            busiestHourOrders = b.getValue();
            busiestHourTime = String.format("%d:00 %s",
                    h == 0 ? 12 : (h > 12 ? h - 12 : h), h < 12 ? "AM" : "PM");
        }

        double avgPrepMinutes = todayOrders.stream()
                .filter(o -> o.getTotalPrepTimeMinutes() != null && o.getTotalPrepTimeMinutes() > 0)
                .mapToInt(Order::getTotalPrepTimeMinutes)
                .average().orElse(12.0);

        List<Order> cookingOrders = orderRepository.findByStatus(OrderStatus.COOKING);
        boolean hasBottleneck = false;
        String  bottleneckReason = null;
        if (cookingOrders.size() >= 3) {
            Instant now = Instant.now();
            long lateCount = cookingOrders.stream()
                    .filter(o -> o.getCookingStartedAt() != null
                            && o.getTotalPrepTimeMinutes() != null
                            && o.getTotalPrepTimeMinutes() > 0)
                    .filter(o -> {
                        long elapsedSec = now.getEpochSecond() - o.getCookingStartedAt().getEpochSecond();
                        long budgetSec  = (long) (o.getTotalPrepTimeMinutes() * 60 * 1.5);
                        return elapsedSec > budgetSec;
                    }).count();
            if (lateCount > 0) {
                hasBottleneck = true;
                bottleneckReason = lateCount == 1
                        ? "1 order is running behind schedule"
                        : lateCount + " orders are running behind schedule";
            }
        }

        return ResponseEntity.ok(new CustomerKitchenSummaryDto(
                topDishName, topDishOrders, busiestHourTime, busiestHourOrders,
                Math.round(avgPrepMinutes * 10.0) / 10.0, hasBottleneck, bottleneckReason));
    }

    // ── GET /api/customer/slots ───────────────────────────────────────────────
    @GetMapping("/slots")
    public ResponseEntity<List<CustomerSlotDto>> getTodaySlots() {
        LocalDateTime now = LocalDateTime.now(KITCHEN_ZONE).plusMinutes(10);
        List<CustomerSlotDto> slots = pickupSlotRepository
                .findBySlotTimeAfterOrderBySlotTimeAsc(now)
                .stream()
                .filter(s -> {
                    LocalDate slotDate = s.getSlotTime().toLocalDate();
                    LocalDate today    = LocalDate.now(KITCHEN_ZONE);
                    return slotDate.equals(today);
                })
                .map(CustomerSlotDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(slots);
    }

    // ── GET /api/customer/slots/tomorrow ─────────────────────────────────────
    @GetMapping("/slots/tomorrow")
    public ResponseEntity<List<CustomerSlotDto>> getTomorrowSlots() {
        LocalDate      tomorrow        = LocalDate.now(KITCHEN_ZONE).plusDays(1);
        LocalDateTime  startOfTomorrow = tomorrow.atStartOfDay();
        LocalDateTime  endOfTomorrow   = tomorrow.plusDays(1).atStartOfDay();

        List<CustomerSlotDto> slots = pickupSlotRepository
                .findBySlotTimeAfterOrderBySlotTimeAsc(startOfTomorrow)
                .stream()
                .filter(s -> s.getSlotTime().isBefore(endOfTomorrow))
                .map(CustomerSlotDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(slots);
    }

    // ── GET /api/customer/stats ───────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getPlatformStats() {
        long totalOrders    = orderRepository.count();
        long totalMenuItems = menuItemRepository.countByAvailableTrue();

        long totalCustomers = orderRepository.findAll().stream()
                .map(Order::getCustomerName)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return ResponseEntity.ok(Map.of(
                "totalOrdersDelivered", totalOrders,
                "totalCustomers",       totalCustomers,
                "totalMenuItems",       totalMenuItems,
                "avgRating",            "4.8"
        ));
    }

    // ── GET /api/customer/addons ──────────────────────────────────────────────
    // Returns the list of add-ons available for order customisation.
    // Used by OrderModal to replace the hardcoded ADDONS static array.
    //
    // Add-ons are currently static product configuration (not stored in DB).
    // When you need kitchen-managed add-ons (variable prices, availability),
    // create an Addon entity and replace this with a real repository lookup.
    //
    // No auth required — add-on list is public product information,
    // same as menu items.
    @GetMapping("/addons")
    public ResponseEntity<List<Map<String, Object>>> getAddons() {
        // Static list for now — matches the previous hardcoded ADDONS in OrderModal.
        // Move to a database table (e.g. addon_items) when needed.
        List<Map<String, Object>> addons = List.of(
                Map.of("id", "extra-cheese", "name", "Extra Cheese",  "price", 30, "icon", "🧀"),
                Map.of("id", "extra-spicy",  "name", "Extra Spicy",   "price",  0, "icon", "🌶️"),
                Map.of("id", "extra-butter", "name", "Extra Butter",  "price", 20, "icon", "🧈"),
                Map.of("id", "onion-rings",  "name", "Onion Rings",   "price", 40, "icon", "🧅"),
                Map.of("id", "raita",        "name", "Raita",         "price", 25, "icon", "🥛"),
                Map.of("id", "papad",        "name", "Papad (2 pcs)", "price", 20, "icon", "🫓")
        );
        return ResponseEntity.ok(addons);
    }

    // ── CustomerSlotDto ───────────────────────────────────────────────────────
    public record CustomerSlotDto(
            String  slotId,
            String  slotTime,
            String  displayTime,
            String  period,
            int     maxCapacity,
            int     currentBookings,
            int     remaining
    ) {
        public static CustomerSlotDto from(PickupSlot slot) {
            int hour = slot.getSlotTime().getHour();
            String period =
                    hour < 11  ? "Breakfast" :
                            hour < 15  ? "Lunch"     :
                                    hour < 18  ? "Afternoon" :
                                            "Dinner";

            String displayTime = slot.getSlotTime()
                    .atZone(ZoneId.of("Asia/Kolkata"))
                    .toInstant()
                    .atZone(ZoneId.of("Asia/Kolkata"))
                    .toLocalTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));

            String isoTime = slot.getSlotTime()
                    .atZone(ZoneId.of("Asia/Kolkata"))
                    .toInstant()
                    .toString();

            int remaining = slot.getMaxCapacity() - slot.getCurrentBookings();

            return new CustomerSlotDto(
                    slot.getId().toString(),
                    isoTime,
                    displayTime,
                    period,
                    slot.getMaxCapacity(),
                    slot.getCurrentBookings(),
                    Math.max(0, remaining)
            );
        }
    }

    // ── CustomerMenuItemDto ───────────────────────────────────────────────────
    public record CustomerMenuItemDto(
            String  id,
            String  name,
            int     prepTime,
            boolean available,
            Integer price,
            String  category,
            String  imageUrl,
            @com.fasterxml.jackson.annotation.JsonProperty("isExpress")
            boolean isExpress
    ) {
        public static CustomerMenuItemDto from(MenuItem item) {
            return new CustomerMenuItemDto(
                    item.getId().toString(),
                    item.getName(),
                    item.getPrepTimeMinutes(),
                    item.isAvailable(),
                    item.getPrice(),
                    item.getCategory(),
                    item.getImageUrl(),
                    item.isExpress()
            );
        }
    }
}