package com.pqh.ms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;


@Entity
@Table(name = "trades")
public class Trade {
    /**
     * Id of the trade, used for persistence
     */
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Id of the trade, used in app
     */
    @Column(unique = true, nullable = false)
    private String tradeId;

    /**
     * Id of the order that bought
     */
    private String buyerOrderId;

    /**
     * Id of the order that sell
     */
    private String sellerOrderId;

    /**
     * Id of the asset being traded
     */
    private String instrumentId;

    /**
     * The price at which the trade occured
     */
    private Double tradedPrice;

    /**
     * Quantity has been done
     */
    private Double tradedQuantity;

    private Instant timestamp;

    public Trade(String tradeId, String buyerOrderId, String sellerOrderId, String instrumentId, Double tradedPrice, Double tradedQuantity, Instant timestamp) {
        this.tradeId = tradeId;
        this.buyerOrderId = buyerOrderId;
        this.sellerOrderId = sellerOrderId;
        this.instrumentId = instrumentId;
        this.tradedPrice = tradedPrice;
        this.tradedQuantity = tradedQuantity;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public String getTradeId() {
        return tradeId;
    }

    public String getBuyerOrderId() {
        return buyerOrderId;
    }

    public String getSellerOrderId() {
        return sellerOrderId;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public Double getTradedPrice() {
        return tradedPrice;
    }

    public Double getTradedQuantity() {
        return tradedQuantity;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }

    public void setBuyerOrderId(String buyerOrderId) {
        this.buyerOrderId = buyerOrderId;
    }

    public void setSellerOrderId(String sellerOrderId) {
        this.sellerOrderId = sellerOrderId;
    }

    public void setInstrumentId(String instrumentId) {
        this.instrumentId = instrumentId;
    }

    public void setTradedPrice(Double tradedPrice) {
        this.tradedPrice = tradedPrice;
    }

    public void setTradedQuantity(Double tradedQuantity) {
        this.tradedQuantity = tradedQuantity;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Trade() {
    }
}
