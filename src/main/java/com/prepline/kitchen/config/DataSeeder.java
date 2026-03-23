package com.prepline.kitchen.config;

import com.prepline.kitchen.inventory.domain.InventoryCategory;
import com.prepline.kitchen.inventory.domain.InventoryItem;
import com.prepline.kitchen.inventory.repository.InventoryItemRepository;
import com.prepline.kitchen.menu.domain.MenuItem;
import com.prepline.kitchen.menu.domain.MenuItemRecipe;
import com.prepline.kitchen.menu.repository.MenuItemRecipeRepository;
import com.prepline.kitchen.menu.repository.MenuItemRepository;
import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderItem;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.order.repository.OrderRepository;
import com.prepline.kitchen.slot.domain.PickupSlot;
import com.prepline.kitchen.slot.repository.PickupSlotRepository;
import com.prepline.kitchen.staff.domain.KitchenStaff;
import com.prepline.kitchen.staff.repository.KitchenStaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final KitchenStaffRepository   staffRepository;
    private final MenuItemRepository       menuItemRepository;
    private final MenuItemRecipeRepository recipeRepository;
    private final PickupSlotRepository     slotRepository;
    private final OrderRepository          orderRepository;
    private final InventoryItemRepository  inventoryRepo;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        boolean hasStaff  = staffRepository.count()  > 0;
        boolean hasOrders = orderRepository.count()   > 0;

        // ── Slots: ALWAYS refresh on boot ─────────────────────────────────────
        List<Order> ordersWithSlot = orderRepository.findAll()
                .stream()
                .filter(o -> o.getPickupSlot() != null)
                .toList();
        if (!ordersWithSlot.isEmpty()) {
            ordersWithSlot.forEach(o -> o.setPickupSlot(null));
            orderRepository.saveAll(ordersWithSlot);
            orderRepository.flush();
            log.info("[DataSeeder] Detached pickup slot from {} order(s).", ordersWithSlot.size());
        }
        slotRepository.deleteAll();
        slotRepository.flush();

        LocalDateTime now  = LocalDateTime.now(IST);
        int           mins = now.getMinute() < 30 ? 30 : 60;
        LocalDateTime base = now.withMinute(0).withSecond(0).withNano(0).plusMinutes(mins);

        List<PickupSlot> freshSlots = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            freshSlots.add(PickupSlot.builder()
                    .slotTime(base.plusMinutes(i * 30L))
                    .maxCapacity(5)
                    .currentBookings(0)
                    .build());
        }
        LocalDateTime tomorrowBase = LocalDate.now(IST).plusDays(1).atTime(LocalTime.of(11, 0));
        for (int i = 0; i < 4; i++) {
            freshSlots.add(PickupSlot.builder()
                    .slotTime(tomorrowBase.plusHours(i))
                    .maxCapacity(5)
                    .currentBookings(0)
                    .build());
        }

        List<PickupSlot> savedSlots = slotRepository.saveAll(freshSlots);
        PickupSlot slot1 = savedSlots.get(0);
        PickupSlot slot2 = savedSlots.get(1);

        log.info("[DataSeeder] Refreshed {} pickup slots ({} today, 4 tomorrow).",
                savedSlots.size(), savedSlots.size() - 4);

        // ── Inventory: seed only once ──────────────────────────────────────────
        seedInventory();

        // ── Recipes: idempotent, runs every boot to backfill if needed ─────────
        seedRecipes();

        // ── Staff / Menu / Orders: seed only once ─────────────────────────────
        if (hasStaff && hasOrders) {
            log.info("[DataSeeder] Staff + orders already exist — skipping order seed.");
            return;
        }

        log.info("[DataSeeder] Seeding kitchen data...");

        // ── Kitchen Staff ──────────────────────────────────────────────────────
        KitchenStaff arjun, monika, rohit;

        if (!hasStaff) {
            arjun  = staffRepository.save(KitchenStaff.builder()
                    .name("Arjun Patel").maxConcurrentOrders(3)
                    .status(KitchenStaff.StaffStatus.ACTIVE).build());
            monika = staffRepository.save(KitchenStaff.builder()
                    .name("Monika Iyer").maxConcurrentOrders(3)
                    .status(KitchenStaff.StaffStatus.ACTIVE).build());
            rohit  = staffRepository.save(KitchenStaff.builder()
                    .name("Rohit Sharma").maxConcurrentOrders(2)
                    .status(KitchenStaff.StaffStatus.ACTIVE).build());
            staffRepository.save(KitchenStaff.builder()
                    .name("Kiran Rao").maxConcurrentOrders(2)
                    .status(KitchenStaff.StaffStatus.BACKUP).build());
            log.info("[DataSeeder] Seeded 4 staff members.");
        } else {
            List<KitchenStaff> existing = staffRepository.findAll();
            arjun  = existing.stream().filter(s -> s.getName().equals("Arjun Patel")).findFirst()
                    .orElse(existing.get(0));
            monika = existing.stream().filter(s -> s.getName().equals("Monika Iyer")).findFirst()
                    .orElse(existing.size() > 1 ? existing.get(1) : existing.get(0));
            rohit  = existing.stream().filter(s -> s.getName().equals("Rohit Sharma")).findFirst()
                    .orElse(existing.size() > 2 ? existing.get(2) : existing.get(0));
            log.info("[DataSeeder] Staff already exists — reusing for order seed.");
        }

        // ── Menu Items ─────────────────────────────────────────────────────────
        if (menuItemRepository.count() == 0) {
            menuItemRepository.saveAll(List.of(
                    MenuItem.builder().name("Butter Chicken")    .prepTimeMinutes(15).available(true).price(249).category("North Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Masala Dosa")       .prepTimeMinutes(10).available(true).price(129).category("South Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Hyderabadi Biryani").prepTimeMinutes(20).available(true).price(299).category("Biryani")     .isExpress(false).build(),
                    MenuItem.builder().name("Cheese Pizza")      .prepTimeMinutes(10).available(true).price(149).category("Pizza")       .isExpress(true) .build(),
                    MenuItem.builder().name("Paneer Tikka")      .prepTimeMinutes(12).available(true).price(199).category("North Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Chole Bhature")     .prepTimeMinutes(10).available(true).price(149).category("North Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Idli Sambar")       .prepTimeMinutes(8) .available(true).price(99) .category("South Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Vada Pav")          .prepTimeMinutes(5) .available(true).price(49) .category("Street Food") .isExpress(true) .build(),
                    MenuItem.builder().name("Dal Makhani")       .prepTimeMinutes(15).available(true).price(179).category("North Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Gulab Jamun")       .prepTimeMinutes(5) .available(true).price(89) .category("Desserts")    .isExpress(true) .build(),
                    MenuItem.builder().name("Rajasthani Thali")  .prepTimeMinutes(20).available(true).price(399).category("Thali")       .isExpress(false).build(),
                    MenuItem.builder().name("Lucknowi Biryani")  .prepTimeMinutes(25).available(true).price(329).category("Biryani")     .isExpress(false).build(),
                    MenuItem.builder().name("Samosa (2 pcs)")    .prepTimeMinutes(5) .available(true).price(40) .category("Street Food") .isExpress(true) .build(),
                    MenuItem.builder().name("Chocolate Donut")   .prepTimeMinutes(3) .available(true).price(45) .category("Desserts")    .isExpress(true) .build(),
                    MenuItem.builder().name("Poha")              .prepTimeMinutes(8) .available(true).price(60) .category("South Indian").isExpress(true) .build(),
                    MenuItem.builder().name("Palak Paneer")      .prepTimeMinutes(20).available(true).price(199).category("North Indian").isExpress(false).build(),
                    MenuItem.builder().name("Chicken Korma")     .prepTimeMinutes(22).available(true).price(269).category("North Indian").isExpress(false).build(),
                    MenuItem.builder().name("Kadai Paneer")      .prepTimeMinutes(20).available(true).price(219).category("North Indian").isExpress(false).build(),
                    MenuItem.builder().name("Prawn Masala")      .prepTimeMinutes(25).available(true).price(329).category("North Indian").isExpress(false).build(),
                    MenuItem.builder().name("Mutton Rogan Josh") .prepTimeMinutes(30).available(true).price(349).category("North Indian").isExpress(false).build(),
                    MenuItem.builder().name("Butter Garlic Naan").prepTimeMinutes(18).available(true).price(49) .category("North Indian").isExpress(false).build()
            ));
            log.info("[DataSeeder] Seeded 21 menu items.");
        }

        // ── Resolve items for demo orders ──────────────────────────────────────
        // Use name-based lookup so order doesn't depend on insertion index.
        List<MenuItem> allItems = menuItemRepository.findAll();
        Map<String, MenuItem> byName = allItems.stream()
                .collect(Collectors.toMap(MenuItem::getName, Function.identity(), (a, b) -> a));

        MenuItem butterChicken = byName.getOrDefault("Butter Chicken",     allItems.get(0));
        MenuItem dalMakhani    = byName.getOrDefault("Dal Makhani",        allItems.get(0));
        MenuItem vadaPav       = byName.getOrDefault("Vada Pav",           allItems.get(0));
        MenuItem palakPaneer   = byName.getOrDefault("Palak Paneer",       allItems.get(0));
        MenuItem prawnMasala   = byName.getOrDefault("Prawn Masala",       allItems.get(0));
        MenuItem kadaiPaneer   = byName.getOrDefault("Kadai Paneer",       allItems.get(0));

        // ── Demo Orders ────────────────────────────────────────────────────────
        Instant todayStart = LocalDate.now(IST)
                .atTime(8, 0)
                .atZone(IST)
                .toInstant();

        createOrder("#2847-NORMAL",  "Divya Reddy",  OrderStatus.PENDING, arjun,  slot1,
                List.of(palakPaneer, dalMakhani),    todayStart.plusSeconds(7200));
        createOrder("#2848-EXPRESS", "Ananya Nair",  OrderStatus.PENDING, null,   null,
                List.of(butterChicken),              todayStart.plusSeconds(7500));
        createOrder("#2849-NORMAL",  "Rahul Mehta",  OrderStatus.PENDING, monika, slot2,
                List.of(vadaPav, kadaiPaneer),       todayStart.plusSeconds(7800));

        Order cooking1 = createOrder("#2844-NORMAL", "Priya Singh", OrderStatus.COOKING, arjun, slot1,
                List.of(prawnMasala),                todayStart.plusSeconds(5400));
        cooking1.setCookingStartedAt(todayStart.plusSeconds(5700));
        orderRepository.save(cooking1);

        Order cooking2 = createOrder("#2845-EXPRESS", "Arjun Kumar", OrderStatus.COOKING, monika, null,
                List.of(butterChicken, dalMakhani),  todayStart.plusSeconds(6000));
        cooking2.setCookingStartedAt(todayStart.plusSeconds(6300));
        orderRepository.save(cooking2);

        Order ready1 = createOrder("#2843-NORMAL", "Sneha Patel", OrderStatus.READY, rohit, slot1,
                List.of(palakPaneer),                todayStart.plusSeconds(3600));
        ready1.setCookingStartedAt(todayStart.plusSeconds(3900));
        ready1.setReadyAt(todayStart.plusSeconds(4620));
        orderRepository.save(ready1);

        Order completed1 = createOrder("#2840-NORMAL", "Kavya Iyer", OrderStatus.COMPLETED, arjun, slot1,
                List.of(vadaPav),                    todayStart);
        completed1.setCookingStartedAt(todayStart.plusSeconds(300));
        completed1.setReadyAt(todayStart.plusSeconds(1140));
        completed1.setCompletedAt(todayStart.plusSeconds(1260));
        orderRepository.save(completed1);

        Order completed2 = createOrder("#2841-NORMAL", "Rohan Das", OrderStatus.COMPLETED, monika, slot2,
                List.of(butterChicken),              todayStart.plusSeconds(1800));
        completed2.setCookingStartedAt(todayStart.plusSeconds(2100));
        completed2.setReadyAt(todayStart.plusSeconds(3180));
        completed2.setCompletedAt(todayStart.plusSeconds(3300));
        orderRepository.save(completed2);

        Order completed3 = createOrder("#2842-NORMAL", "Meera Shah", OrderStatus.COMPLETED, rohit, slot1,
                List.of(palakPaneer),                todayStart.plusSeconds(3600));
        completed3.setCookingStartedAt(todayStart.plusSeconds(3900));
        completed3.setReadyAt(todayStart.plusSeconds(4620));
        completed3.setCompletedAt(todayStart.plusSeconds(4740));
        orderRepository.save(completed3);

        log.info("[DataSeeder] Seeding complete. Staff: 4, MenuItems: 21, Slots: {}, Orders: 10.",
                savedSlots.size());
    }

    // ── Recipe seed ────────────────────────────────────────────────────────────

    private void seedRecipes() {
        if (menuItemRepository.count() == 0 || inventoryRepo.count() == 0) {
            log.warn("[DataSeeder] Cannot seed recipes — menu items or inventory missing.");
            return;
        }

        Map<String, InventoryItem> invByName = inventoryRepo.findAll().stream()
                .collect(Collectors.toMap(
                        InventoryItem::getName, Function.identity(), (a, b) -> a));

        Map<String, MenuItem> menuByName = menuItemRepository.findAll().stream()
                .collect(Collectors.toMap(
                        MenuItem::getName, Function.identity(), (a, b) -> a));

        record Ing(String name, double qty) {}

        Map<String, List<Ing>> definitions = Map.of(
                "Butter Chicken", List.of(
                        new Ing("Chicken Tikka Pieces", 0.4),
                        new Ing("Butter Chicken Gravy", 0.1),
                        new Ing("Basmati Rice",         0.3),
                        new Ing("Butter",               0.05),
                        new Ing("Fresh Cream",          0.05),
                        new Ing("Garam Masala Paste",   0.03)
                ),
                "Dal Makhani", List.of(
                        new Ing("Dal Tadka Base",    0.3),
                        new Ing("Tomato",            0.3),
                        new Ing("Onion",             0.2),
                        new Ing("Butter",            0.03),
                        new Ing("Fresh Cream",       0.03),
                        new Ing("Garam Masala Paste",0.02),
                        new Ing("Coriander Leaves",  0.05)
                ),
                "Palak Paneer", List.of(
                        new Ing("Paneer Cubes",      0.4),
                        new Ing("Tomato",            0.3),
                        new Ing("Onion",             0.2),
                        new Ing("Ginger",            0.03),
                        new Ing("Garam Masala Paste",0.02),
                        new Ing("Fresh Cream",       0.03),
                        new Ing("Coriander Leaves",  0.05)
                ),
                "Kadai Paneer", List.of(
                        new Ing("Paneer Cubes",         0.4),
                        new Ing("Tomato",               0.5),
                        new Ing("Onion",                0.3),
                        new Ing("Butter Chicken Gravy", 0.1),
                        new Ing("Garam Masala Paste",   0.03),
                        new Ing("Coriander Leaves",     0.05)
                ),
                "Prawn Masala", List.of(
                        new Ing("Prawns",            0.3),
                        new Ing("Tomato",            0.4),
                        new Ing("Onion",             0.3),
                        new Ing("Ginger",            0.03),
                        new Ing("Garam Masala Paste",0.03),
                        new Ing("Tamarind Paste",    0.02),
                        new Ing("Coriander Leaves",  0.05)
                ),
                "Vada Pav", List.of(
                        new Ing("Tomato",            0.2),
                        new Ing("Mint Chutney",      0.05),
                        new Ing("Coriander Leaves",  0.05)
                )
        );

        int totalSaved = 0;
        for (Map.Entry<String, List<Ing>> entry : definitions.entrySet()) {
            MenuItem menuItem = menuByName.get(entry.getKey());
            if (menuItem == null) {
                log.warn("[DataSeeder] Recipe skipped — MenuItem '{}' not found", entry.getKey());
                continue;
            }
            if (!recipeRepository.findByMenuItemId(menuItem.getId()).isEmpty()) {
                log.debug("[DataSeeder] Recipes for '{}' already exist — skipping", entry.getKey());
                continue;
            }
            List<MenuItemRecipe> rows = new ArrayList<>();
            for (Ing ing : entry.getValue()) {
                InventoryItem inv = invByName.get(ing.name());
                if (inv == null) {
                    log.warn("[DataSeeder] Ingredient '{}' not found in inventory — skipping", ing.name());
                    continue;
                }
                rows.add(MenuItemRecipe.builder()
                        .menuItem(menuItem)
                        .inventoryItemId(inv.getId())
                        .inventoryItemName(inv.getName())
                        .quantity(ing.qty())
                        .build());
            }
            recipeRepository.saveAll(rows);
            totalSaved += rows.size();
            log.info("[DataSeeder] Seeded {} recipe rows for '{}'", rows.size(), entry.getKey());
        }
        log.info("[DataSeeder] Recipe seeding complete — {} total ingredient mappings.", totalSaved);
    }

    // ── Inventory seed ─────────────────────────────────────────────────────────

    private void seedInventory() {
        if (inventoryRepo.count() > 0) return;

        Instant now = Instant.now();
        List<InventoryItem> items = List.of(
                item("Paneer Cubes",         InventoryCategory.PROTEINS,   55, 100, "lbs",       20, 10, bd(12.50), "Amul Fresh",         now),
                item("Chicken Tikka Pieces", InventoryCategory.PROTEINS,   60, 120, "lbs",       25, 12, bd(5.50),  "Farm Fresh Poultry", now),
                item("Mutton Curry Cut",     InventoryCategory.PROTEINS,   30,  80, "lbs",       15,  8, bd(18.00), "Local Butcher",      now),
                item("Fish Fillet",          InventoryCategory.PROTEINS,   25,  50, "lbs",       12,  5, bd(22.00), "Coastal Catch",      now),
                item("Prawns",               InventoryCategory.PROTEINS,   30,  60, "lbs",       15,  8, bd(14.00), "Coastal Catch",      now),
                item("Chicken Mince",        InventoryCategory.PROTEINS,   20,  40, "lbs",       10,  5, bd(8.00),  "Farm Fresh Poultry", now),
                item("Tomato",               InventoryCategory.VEGETABLES, 40, 100, "pcs",       25, 10, bd(1.50),  "Green Mandai",       now),
                item("Onion",                InventoryCategory.VEGETABLES, 55,  80, "pcs",       20, 10, bd(0.75),  "Green Mandai",       now),
                item("Green Peas",           InventoryCategory.VEGETABLES, 25,  50, "lbs",       12,  5, bd(4.00),  "Green Mandai",       now),
                item("Coriander Leaves",     InventoryCategory.VEGETABLES, 35,  60, "bunches",   15,  8, bd(0.50),  "Green Mandai",       now),
                item("Mushrooms",            InventoryCategory.VEGETABLES, 20,  40, "lbs",       10,  5, bd(6.00),  "Green Mandai",       now),
                item("Mixed Pickle",         InventoryCategory.VEGETABLES, 25,  50, "lbs",       12,  5, bd(5.50),  "Homestyle Foods",    now),
                item("Basmati Rice",         InventoryCategory.GRAINS,     80, 200, "lbs",       40, 20, bd(2.00),  "India Grains",       now),
                item("Wheat Noodles",        InventoryCategory.GRAINS,     50, 100, "portions",  30, 15, bd(0.80),  "India Grains",       now),
                item("Chapati Flour",        InventoryCategory.GRAINS,     45,  80, "portions",  20, 10, bd(1.00),  "India Grains",       now),
                item("Garam Masala Paste",   InventoryCategory.SAUCES,     15,  30, "liters",     8,  4, bd(4.50),  "Spice House",        now),
                item("Butter Chicken Gravy", InventoryCategory.SAUCES,     12,  25, "liters",     6,  3, bd(6.00),  "House Made",         now),
                item("Mint Chutney",         InventoryCategory.SAUCES,     10,  20, "liters",     5,  2, bd(5.00),  "House Made",         now),
                item("Dal Tadka Base",       InventoryCategory.SAUCES,     20,  50, "liters",    12,  6, bd(3.00),  "House Made",         now),
                item("Tamarind Paste",       InventoryCategory.SAUCES,     10,  25, "lbs",        6,  3, bd(8.00),  "Spice House",        now),
                item("Butter",               InventoryCategory.DAIRY,      15,  30, "lbs",        8,  4, bd(5.00),  "Amul",               now),
                item("Fresh Cream",          InventoryCategory.DAIRY,      10,  20, "liters",     5,  2, bd(4.50),  "Amul",               now),
                item("Red Chilli Powder",    InventoryCategory.SPICES,      8,  15, "lbs",        4,  2, bd(25.00), "Spice House",        now),
                item("Cumin Seeds",          InventoryCategory.SPICES,      8,  20, "lbs",        5,  2, bd(8.00),  "Spice House",        now),
                item("Ginger",               InventoryCategory.SPICES,      6,  15, "lbs",        4,  2, bd(6.00),  "Green Mandai",       now),
                item("Masala Chai",          InventoryCategory.BEVERAGES,  50, 100, "portions",  25, 10, bd(0.30),  "Tea Board India",    now),
                item("Mango Lassi",          InventoryCategory.BEVERAGES,  20,  40, "bottles",   10,  5, bd(15.00), "House Made",         now)
        );
        inventoryRepo.saveAll(items);
        log.info("[DataSeeder] Seeded {} inventory items.", items.size());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private InventoryItem item(String name, InventoryCategory cat,
                               double stock, double max, String unit, double min, double critical,
                               BigDecimal cost, String supplier, Instant lastRestocked) {
        InventoryItem i = new InventoryItem();
        i.setName(name);                   i.setCategory(cat);
        i.setCurrentStock(stock);          i.setMaxCapacity(max);
        i.setUnit(unit);                   i.setMinThreshold(min);
        i.setCriticalThreshold(critical);
        i.setCostPerUnit(cost);            i.setSupplier(supplier);
        i.setLastRestocked(lastRestocked);
        return i;
    }

    private Order createOrder(String ref, String customerName, OrderStatus status,
                              KitchenStaff chef, PickupSlot slot,
                              List<MenuItem> menuItems, Instant placedAt) {
        Order order = Order.builder()
                .orderRef(ref).customerName(customerName).status(status)
                .assignedChef(chef).pickupSlot(slot).placedAt(placedAt)
                .build();

        List<OrderItem> items = new ArrayList<>();
        int maxPrep = 0;
        for (MenuItem m : menuItems) {
            items.add(OrderItem.builder()
                    .order(order).menuItem(m).quantity(1)
                    .prepTimeMinutes(m.getPrepTimeMinutes()).build());
            maxPrep = Math.max(maxPrep, m.getPrepTimeMinutes());
        }
        order.setItems(items);
        order.setTotalPrepTimeMinutes(maxPrep);
        return orderRepository.save(order);
    }
}