package com.prepline.kitchen.staff.dto;

public record StaffRequestDto(
        String name,
        int maxConcurrentOrders,
        boolean activeToday
) {}