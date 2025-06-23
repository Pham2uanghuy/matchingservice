package com.pqh.ms.repository;

import com.pqh.ms.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    Optional<Trade> findByTradeId(String tradeId);
    List<Trade> findByBuyerOrderId(String buyerOrderId);
    List<Trade> findBySellerOrderId(String sellerOrderId);
    List<Trade> findByInstrumentId(String instrumentId);
}
