package com.pqh.ms.service.kafka.msg;

import com.pqh.ms.entity.Order;

public class OrderEvent {
    private String message;
    private String status;
    private Order order;

    public OrderEvent() {
    }

    public OrderEvent(String message, Order order, String status) {
        this.message = message;
        this.order = order;
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
