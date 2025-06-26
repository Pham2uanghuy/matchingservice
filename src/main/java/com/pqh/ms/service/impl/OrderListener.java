package com.pqh.ms.service.impl;

import com.pqh.ms.entity.Order;

public interface OrderListener {
    public void onChange(Order order);
}
