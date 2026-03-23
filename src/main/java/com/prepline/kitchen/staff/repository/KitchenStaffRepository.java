package com.prepline.kitchen.staff.repository;

import com.prepline.kitchen.staff.domain.KitchenStaff;
import com.prepline.kitchen.staff.domain.KitchenStaff.StaffStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KitchenStaffRepository extends JpaRepository<KitchenStaff, UUID> {

    // ── Primary queries (enum-based) ──────────────────────────────────────────

    /** All ACTIVE chefs — the only ones counted in kitchen capacity. */
    List<KitchenStaff> findByStatus(StaffStatus status);

    /** All chefs matching any of the given statuses — used for UI panels. */
    List<KitchenStaff> findByStatusIn(List<StaffStatus> statuses);

    /** First available BACKUP chef — used for auto-activation on queue pressure. */
    Optional<KitchenStaff> findFirstByStatus(StaffStatus status);

    /** Count by status — used in capacity utilisation and metrics. */
    long countByStatus(StaffStatus status);

    /**
     * Pessimistic write lock on a single chef row.
     *
     * Used in assignChef() and autoAssignChef() so the capacity read
     * (activeOrders vs maxConcurrentOrders) and the subsequent save
     * happen under the same row lock. Prevents two concurrent assignment
     * requests from both passing the capacity check before either commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM KitchenStaff s WHERE s.id = :id")
    Optional<KitchenStaff> findByIdWithLock(UUID id);

    // ── Convenience wrappers (keep callers readable) ──────────────────────────

    default List<KitchenStaff> findByActiveTodayTrueAndOnShiftTrue() {
        return findByStatus(StaffStatus.ACTIVE);
    }

    default List<KitchenStaff> findByActiveTodayTrue() {
        return findByStatusIn(List.of(StaffStatus.ACTIVE, StaffStatus.BACKUP));
    }

    default long countByActiveTodayTrue() {
        return countByStatus(StaffStatus.ACTIVE);
    }

    /**
     * Count chefs at max load — used by MetricsService for capacity utilisation %.
     * Uses enum simple name (not fully-qualified) — required by Hibernate 6 JPQL.
     */
    @Query("SELECT COUNT(s) FROM KitchenStaff s " +
            "WHERE s.status = 'ACTIVE' " +
            "AND (SELECT COUNT(o) FROM Order o " +
            "     WHERE o.assignedChef = s " +
            "     AND o.status IN ('PENDING', 'COOKING')) >= s.maxConcurrentOrders")
    int countChefsAtMaxLoad();
}