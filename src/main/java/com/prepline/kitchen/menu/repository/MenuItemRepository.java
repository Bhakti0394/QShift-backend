package com.prepline.kitchen.menu.repository;

import com.prepline.kitchen.menu.domain.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    List<MenuItem> findByAvailableTrue();

    // FIX: used by CustomerOrderController GET /api/customer/stats
    // to return the real count of available menu items.
    // Spring Data JPA derives this query automatically from the method name.
    long countByAvailableTrue();
}