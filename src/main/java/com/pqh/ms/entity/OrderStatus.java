package com.pqh.ms.entity;

/**
 * This is enum class define status of the order
 */
public enum OrderStatus {
    /**
     * The order is active and waiting to be filled
     */
    OPEN,
    /**
     * some quantity has been filled but some remain
     */
    PARTIALLY_FILLED,
    /**
     * Quantity has been filled entirely
     */
    FILLED,
    /**
     * The order was canceled by user or system
     */
    CANCELED
}
