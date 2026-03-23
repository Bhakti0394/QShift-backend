package com.prepline.kitchen.metrics.repository;

import com.prepline.kitchen.order.domain.Order;
import com.prepline.kitchen.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MetricsProjectionRepository extends JpaRepository<Order, UUID> {

    @Query("SELECT o FROM Order o WHERE o.status = :status " +
            "AND o.cookingStartedAt IS NOT NULL AND o.readyAt IS NOT NULL")
    java.util.List<Order> findCompletedWithTimes(OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
    long countByStatus(OrderStatus status);
}