package com.pqh.ms.service.impl;

import com.pqh.ms.entity.*;
import com.pqh.ms.repository.OrderRepository;
import com.pqh.ms.repository.TradeRepository;
import com.pqh.ms.service.engine.OrderBookEngine;
import com.pqh.ms.service.kafka.OrderProducer;
import com.pqh.ms.service.kafka.TradeEventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import jakarta.annotation.PreDestroy;


@Service
public class MatchingService implements TradeListener, OrderListener {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final OrderBookEngine orderBookEngine;
    private final OrderBook orderBook; // Reference to OrderBook (though interaction is mostly via engine)
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    //fields for redis
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String REDIS_ORDER_KEY_PREFIX = "order:"; // Redis key prefix for orders

    public MatchingService(OrderRepository orderRepository, TradeRepository tradeRepository, OrderProducer orderProducer, TradeEventProducer tradeEventProducer, RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.orderBook = new OrderBook(); // Initialize OrderBook
        this.orderBookEngine = new OrderBookEngine(this.orderBook, orderProducer, tradeEventProducer, redisTemplate); // Pass OrderBook to OrderBookEngine
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        // Load open orders from DB/Redis into OrderBookEngine on startup (important for persistence)
        loadOpenOrdersIntoEngine();
    }

    /**
     * Loads open and partially filled orders from Redis into the OrderBookEngine.
     * This method is called once on application startup.
     */
    private void loadOpenOrdersIntoEngine() {
        log.info("[MatchingService] Loading open orders from Redis into OrderBookEngine...");
        Set<String> orderKeys = redisTemplate.keys(REDIS_ORDER_KEY_PREFIX + "*");
        List<Order> openOrders = new ArrayList<>();

        if (orderKeys != null && !orderKeys.isEmpty()) {
            for (String key : orderKeys) {
                try {
                    Order order = (Order) redisTemplate.opsForValue().get(key);
                    if (order != null) {
                        if (order.getStatus() == OrderStatus.OPEN || order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                            openOrders.add(order);
                            log.debug("[MatchingService] Found open/partially filled order in Redis: {}", order.getOrderId());
                        } else {
                            // If an order in Redis is not OPEN or PARTIALLY_FILLED, it might be a stale entry. Remove it.
                            log.warn("[MatchingService] Removing stale order {} from Redis (Status: {}).", order.getOrderId(), order.getStatus());
                            redisTemplate.delete(key);
                        }
                    } else {
                        log.warn("[MatchingService] Redis key {} returned a null order object. Removing it.", key);
                        redisTemplate.delete(key); // Remove null entries
                    }
                } catch (Exception e) {
                    log.error("[MatchingService] Error retrieving or processing order from Redis key {}: {}", key, e.getMessage(), e);
                    // Optionally, you might want to delete the problematic key or handle it differently
                }
            }
        } else {
            log.info("[MatchingService] No existing order keys found in Redis with prefix {}.", REDIS_ORDER_KEY_PREFIX);
        }

        // Sort orders by timestamp to maintain processing order (oldest first)
        openOrders.sort(Comparator.comparing(Order::getTimestamp));
        log.debug("[MatchingService] Sorted {} open orders by timestamp for loading.", openOrders.size());

        for (Order order : openOrders) {
            orderBookEngine.addRestingOrder(order); // Add as resting order, directly to order book structure
        }
        log.info("[MatchingService] Loaded {} open orders from Redis into OrderBookEngine.", openOrders.size());
    }

    /**
     * Handles a new incoming order from a user.
     * The order is saved to DB and then enqueued for asynchronous processing by the engine.
     * @param order The order object received from the API.
     * @return The initial saved order object. Its final state will be updated via listeners.
     */
    @Transactional // Ensures initial save of the new order is transactional
    public Order placeOrder(Order order) {
        // Generate UUID for the order and set initial status
        order.setOrderId(UUID.randomUUID().toString());
        order.setTimestamp(Instant.now());
        order.setRemainingQuantity(order.getOriginalQuantity());
        order.setFilledQuantity(0.0);
        order.setStatus(OrderStatus.OPEN);

        // Save the new order to DB immediately. Its status will be updated later by onChange listener.
        Order savedOrder = orderRepository.save(order);
        log.info("[MatchingService] Saved new order to DB (initial state): {}", savedOrder.getOrderId());

        // Immediately persist the initial state of the open order to Redis as well
        redisTemplate.opsForValue().set(REDIS_ORDER_KEY_PREFIX + savedOrder.getOrderId(), savedOrder);
        log.debug("[MatchingService] Saved new order to Redis (initial state): {}", savedOrder.getOrderId());

        // Enqueue the order for asynchronous matching in the OrderBookEngine.
        // This call is non-blocking and returns quickly.
        orderBookEngine.addToOrderQueue(savedOrder);
        log.info("[MatchingService] Enqueued new order {} for matching.", savedOrder.getOrderId());

        // The actual trades and final order status updates will be handled by the
        // onTrade and onChange listeners, which operate in the matching engine's thread.
        return savedOrder;
    }

    /**
     * Retrieves an order by its orderId (UUID).
     * @param orderId The UUID of the order.
     * @return The Order object, or null if not found.
     */
    @Transactional(readOnly = true) // Read-only operation, no DB changes
    public Order getOrder(String orderId) {
        log.debug("[MatchingService] Attempting to retrieve order with ID: {}.", orderId);
        Optional<Order> orderOptional = orderRepository.findByOrderId(orderId);
        if (orderOptional.isPresent()) {
            log.debug("[MatchingService] Order {} found in DB.", orderId);
        } else {
            log.debug("[MatchingService] Order {} not found in DB.", orderId);
        }
        return orderOptional.orElse(null);
    }

    /**
     * Cancels an existing order.
     * The cancellation request is sent to the OrderBookEngine, which processes it asynchronously.
     * The DB update for cancellation will occur via the `onChange` listener.
     * @param orderId The UUID of the order to cancel.
     */
    public void cancelOrder(String orderId) {
        log.info("[MatchingService] Received cancellation request for order ID: {}.", orderId);
        // Find order in DB to check its current status before attempting to cancel
        Optional<Order> orderOptional = orderRepository.findByOrderId(orderId);
        if (orderOptional.isPresent()) {
            Order orderToCancel = orderOptional.get();
            // Only allow cancellation if order is open or partially filled
            if (orderToCancel.getStatus() == OrderStatus.OPEN || orderToCancel.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                // Request engine to cancel. Engine will enqueue and process this on its single thread.
                orderBookEngine.cancelOrder(orderToCancel.getOrderId());
                log.info("[MatchingService] Sent cancellation request for order {} to engine.", orderId);
            } else {
                log.warn("[MatchingService] Order {} cannot be canceled because its current status is {}. Only OPEN or PARTIALLY_FILLED orders can be canceled.",
                        orderId, orderToCancel.getStatus());
            }
        } else {
            log.warn("[MatchingService] Order with orderId: {} not found in DB, cannot send cancellation request.", orderId);
        }
    }

    /**
     * Gets the current Best Bid and Offer (BBO) from the order book.
     * This is a read-only operation on the OrderBook, which is safe for concurrent access.
     * @return The BBO object.
     */
    public BBO getBBO() {
        log.debug("[MatchingService] Requesting Best Bid Offer (BBO).");
        return orderBookEngine.getBBO();
    }

    /**
     * Returns a list of recent trades processed by the engine.
     * In a real system, you'd fetch this from a database.
     */
    @Transactional(readOnly = true)
    public List<Trade> getRecentTrades() {
        log.debug("[MatchingService] Retrieving all recent trades from database.");
        // Retrieve recent trades from DB. Consider adding pagination/limiting in a real app.
        return tradeRepository.findAll();
    }

    /**
     * Callback method called by OrderBookEngine when a trade occurs.
     * This method runs in the context of the OrderBookEngine's matching thread.
     * @param trade The trade that occurred.
     */
    @Override
    @Transactional // Ensures DB save for trade occurs within a transaction
    public void onTrade(Trade trade) {
        log.info("[MatchingService - onTrade] TRADE REPORTED: Buyer Order ID: {}, Seller Order ID: {}, Instrument: {}, Price: {}, Quantity: {}",
                trade.getBuyerOrderId(), trade.getSellerOrderId(), trade.getInstrumentId(), trade.getTradedPrice(), trade.getTradedQuantity());
        // Save the trade to the database. This is critical for persisting trade history.
        tradeRepository.save(trade);
        log.debug("[MatchingService - onTrade] Trade {} saved to database.", trade.getTradeId());
    }

    /**
     * Callback method called by OrderBookEngine when an order's status or quantity changes.
     * This method runs in the context of the OrderBookEngine's matching thread.
     * @param order The order whose state has changed.
     */
    @Override
    @Transactional // Ensures DB save for order status update occurs within a transaction
    public void onChange(Order order) {
        log.info("[MatchingService - onChange] ORDER STATUS CHANGED: Order ID: {} -> Status: {}, Remaining: {}, Filled: {}",
                order.getOrderId(), order.getStatus(), order.getRemainingQuantity(), order.getFilledQuantity());
        // Update the order status/quantities in the database.
        orderRepository.save(order);
        log.debug("[MatchingService - onChange] Order {} status updated in database.", order.getOrderId());
    }

    /**
     * Prints the current state of the order book for debugging/monitoring.
     */
    public void printCurrentOrderBook() {
        log.info("[MatchingService] Requesting OrderBookEngine to print current order book state.");
        orderBookEngine.printOrderBook();
    }

    /**
     * Lifecycle hook for Spring to perform cleanup before the application shuts down.
     * Ensures the OrderBookEngine's internal thread pool is gracefully terminated.
     */
    @PreDestroy
    public void onApplicationShutdown() {
        log.info("[MatchingService] Shutting down OrderBookEngine gracefully...");
        this.orderBookEngine.shutdown();
        log.info("[MatchingService] OrderBookEngine shutdown initiated.");
    }
}