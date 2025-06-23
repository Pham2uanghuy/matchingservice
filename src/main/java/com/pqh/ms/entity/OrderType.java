package com.pqh.ms.entity;

/**
 * This enum class define how order's matched
 */
public enum OrderType {
    /**
     * An order to buy or sell at a specific price or better
     */
    LIMIT,
    /**
     * An order to buy or sell immediately at the best available current price
     */
    MARKET
}
