package com.pqh.ms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "orders")
public class Order {

    /**
     * Id of the Order, used for persistence
     */
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Id of the order
     */
    @Column(unique = true, nullable = false)
    private String orderId;

    /**
     * Id of whom placed the order
     */
    private String userId;

    /**
     * Id of asset being traded
     */
    private String instrumentId;

    /**
     * The price placed
     */
    private Double price;

    /**
     * The initial quantity of the order
     */
    private Double originalQuantity;

    /**
     * The quantity has been filled
     */
    private Double filledQuantity;

    /**
     * The quantity remain
     */
    private Double remainingQuantity;

    /**
     * BUY or SELL
     */
    @Enumerated(EnumType.STRING)
    private OrderSide side;

    /**
     * @see OrderType
     */
    @Enumerated(EnumType.STRING)
    private OrderType type;

    /**
     * @see OrderStatus
     */
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant timestamp;

    /**
     * When a trade was done, this function was called to update status and remaining quantity of the order
     * @param fillAmount
     */
    public void fill(double fillAmount) {
        if (fillAmount <= 0) return;
        this.filledQuantity += fillAmount;
        this.remainingQuantity -= fillAmount;
        if (this.remainingQuantity <= 0) {
            this.status = OrderStatus.FILLED;
            this.remainingQuantity = (double) 0;
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    public Order() {
    }

    public Order(String orderId, String userId, String instrumentId, Double price, Double originalQuantity, Double filledQuantity, Double remainingQuantity, OrderSide side, OrderType type, OrderStatus status, Instant timestamp) {
        this.orderId = orderId;
        this.userId = userId;
        this.instrumentId = instrumentId;
        this.price = price;
        this.originalQuantity = originalQuantity;
        this.filledQuantity = filledQuantity;
        this.remainingQuantity = remainingQuantity;
        this.side = side;
        this.type = type;
        this.status = status;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public OrderType getType() {
        return type;
    }

    public OrderSide getSide() {
        return side;
    }

    public Double getRemainingQuantity() {
        return remainingQuantity;
    }

    public Double getFilledQuantity() {
        return filledQuantity;
    }

    public Double getOriginalQuantity() {
        return originalQuantity;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public Double getPrice() {
        return price;
    }

    public String getUserId() {
        return userId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public void setType(OrderType type) {
        this.type = type;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public void setRemainingQuantity(Double remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public void setFilledQuantity(Double filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public void setOriginalQuantity(Double originalQuantity) {
        this.originalQuantity = originalQuantity;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public void setInstrumentId(String instrumentId) {
        this.instrumentId = instrumentId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
