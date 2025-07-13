package com.pqh.ms.service.kafka.msg;

import com.pqh.ms.entity.Trade;

public class TradeEvent {
        private String message;
        private String status;
        private Trade trade;

    public TradeEvent(String message, Trade trade, String status) {
        this.message = message;
        this.trade = trade;
        this.status = status;
    }

    public TradeEvent() {
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Trade getTrade() {
        return trade;
    }

    public void setTrade(Trade trade) {
        this.trade = trade;
    }
}
