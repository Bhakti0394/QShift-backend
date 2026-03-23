package com.prepline.kitchen.order.repository;

import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import com.prepline.kitchen.staff.domain.KitchenStaff;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    // ── Used by KanbanBoard / MetricsService ──────────────────────────────────

    List<Order> findByStatus(OrderStatus status);

    int countByStatus(OrderStatus status);

    @Query("""
           SELECT o FROM Order o
           WHERE o.status = 'COMPLETED'
             AND o.completedAt >= :start
             AND o.completedAt < :end
           """)
    List<Order> findCompletedOnDate(
            @Param("start") Instant start,
            @Param("end")   Instant end);

    @Query("""
           SELECT COUNT(o) FROM Order o
           WHERE o.placedAt >= :start
             AND o.placedAt < :end
           """)
    int countPlacedOnDate(
            @Param("start") Instant start,
            @Param("end")   Instant end);

    @Query("""
           SELECT COUNT(o) FROM Order o
           WHERE o.status = com.prepline.kitchen.order.domain.OrderStatus.COOKING
             AND o.cookingStartedAt IS NOT NULL
             AND o.totalPrepTimeMinutes IS NOT NULL
             AND (CURRENT_TIMESTAMP - o.cookingStartedAt) by minute > o.totalPrepTimeMinutes
           """)
    long countLateOrdersCooking();

    // ── Used by CustomerOrderController /kitchen-summary ──────────────────────

    @Query("""
           SELECT o FROM Order o
           LEFT JOIN FETCH o.items i
           LEFT JOIN FETCH i.menuItem
           WHERE o.placedAt >= :start
             AND o.placedAt < :end
           """)
    List<Order> findPlacedBetween(
            @Param("start") Instant start,
            @Param("end")   Instant end);

    // ── Used by CustomerOrderController /orders ───────────────────────────────
    // Primary lookup: exact match on customerName (which stores the email
    // after the Authentication fix was applied).

    @Query("""
           SELECT o FROM Order o
           LEFT JOIN FETCH o.items i
           LEFT JOIN FETCH i.menuItem
           WHERE o.customerName = :customerName
           ORDER BY o.placedAt DESC
           """)
    List<Order> findByCustomerName(@Param("customerName") String customerName);

    // FIX: fallback lookup for legacy orders stored before the email-as-name
    // fix was applied. OrderQueryService calls this to surface old orders
    // where customerName was a display name instead of an email address.
    // Spring Data JPA derives the query automatically from the method name —
    // no @Query needed.
    List<Order> findByCustomerNameContainingIgnoreCase(String namePart);

    // ── Pessimistic lock — used by OrderService and StaffCapacityService ──────

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") UUID id);

    // ── Chef workload counts — used by StaffCapacityService ───────────────────

    @Query("""
           SELECT COUNT(o) FROM Order o
           WHERE o.assignedChef = :chef
             AND o.status IN :statuses
           """)
    int countByAssignedChefAndStatusIn(
            @Param("chef")     KitchenStaff chef,
            @Param("statuses") List<OrderStatus> statuses);

    @Query("""
           SELECT o FROM Order o
           WHERE o.assignedChef = :chef
             AND o.status IN :statuses
           """)
    List<Order> findByAssignedChefAndStatusIn(
            @Param("chef")     KitchenStaff chef,
            @Param("statuses") List<OrderStatus> statuses);

    @Query("""
           SELECT COUNT(o) FROM Order o
           WHERE o.assignedChef = :chef
             AND o.status = :status
             AND o.completedAt >= :since
           """)
    int countByAssignedChefAndStatusAndCompletedAtAfter(
            @Param("chef")   KitchenStaff chef,
            @Param("status") OrderStatus status,
            @Param("since")  Instant since);

    // ── Used by OrderQueryService.getBoardSnapshot() ──────────────────────────

    @Query("""
           SELECT DISTINCT o FROM Order o
           LEFT JOIN FETCH o.items i
           LEFT JOIN FETCH i.menuItem
           WHERE o.status = :status
           ORDER BY o.placedAt ASC
           """)
    List<Order> findByStatusWithItems(@Param("status") OrderStatus status);

    List<Order> findByStatusAndCompletedAtAfterOrderByCompletedAtDesc(
            OrderStatus status, Instant after);

    // ── Batch chef counts — used by OrderQueryService ─────────────────────────

    @Query("""
           SELECT o.assignedChef.id AS chefId, COUNT(o) AS cnt
           FROM Order o
           WHERE o.assignedChef IS NOT NULL
             AND o.status IN :statuses
           GROUP BY o.assignedChef.id
           """)
    List<Map<String, Object>> countCookingPerChefRaw(@Param("statuses") List<OrderStatus> statuses);

    default Map<UUID, Integer> countCookingPerChef(List<OrderStatus> statuses) {
        Map<UUID, Integer> result = new HashMap<>();
        countCookingPerChefRaw(statuses).forEach(row ->
                result.put((UUID) row.get("chefId"), ((Number) row.get("cnt")).intValue()));
        return result;
    }

    @Query("""
           SELECT o.assignedChef.id AS chefId, COUNT(o) AS cnt
           FROM Order o
           WHERE o.assignedChef IS NOT NULL
             AND o.status IN :statuses
           GROUP BY o.assignedChef.id
           """)
    List<Map<String, Object>> countActivePerChefRaw(@Param("statuses") List<OrderStatus> statuses);

    default Map<UUID, Integer> countActivePerChef(List<OrderStatus> statuses) {
        Map<UUID, Integer> result = new HashMap<>();
        countActivePerChefRaw(statuses).forEach(row ->
                result.put((UUID) row.get("chefId"), ((Number) row.get("cnt")).intValue()));
        return result;
    }

    @Query("""
           SELECT o.assignedChef.id AS chefId, COUNT(o) AS cnt
           FROM Order o
           WHERE o.assignedChef IS NOT NULL
             AND o.status = com.prepline.kitchen.order.domain.OrderStatus.COMPLETED
             AND o.completedAt >= :since
           GROUP BY o.assignedChef.id
           """)
    List<Map<String, Object>> countCompletedTodayPerChefRaw(@Param("since") Instant since);

    default Map<UUID, Integer> countCompletedTodayPerChef(Instant since) {
        Map<UUID, Integer> result = new HashMap<>();
        countCompletedTodayPerChefRaw(since).forEach(row ->
                result.put((UUID) row.get("chefId"), ((Number) row.get("cnt")).intValue()));
        return result;
    }

    // ── Used by OrderService.handleOrphanedOrders() ───────────────────────────

    @Query("""
           SELECT o FROM Order o
           WHERE o.assignedChef IS NULL
             AND o.status IN :statuses
           """)
    List<Order> findByAssignedChefIsNullAndStatusIn(@Param("statuses") List<OrderStatus> statuses);
}