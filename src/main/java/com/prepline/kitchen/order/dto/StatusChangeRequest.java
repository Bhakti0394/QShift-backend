package com.prepline.kitchen.order.dto;

import com.prepline.kitchen.order.domain.OrderStatus;

public record StatusChangeRequest(
        OrderStatus targetStatus
) {}