package com.pqh.ms.service.impl;

import com.pqh.ms.entity.*;
import com.pqh.ms.repository.OrderRepository;
import com.pqh.ms.repository.TradeRepository;
import com.pqh.ms.service.engine.OrderBookEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.annotation.PreDestroy;


@Service
public class MatchingService implements TradeListener, OrderListener {

    private final OrderBookEngine orderBookEngine;
    private final OrderBook orderBook; // Reference to OrderBook (though interaction is mostly via engine)
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    public MatchingService(OrderRepository orderRepository, TradeRepository tradeRepository) {
        this.orderBook = new OrderBook(); // Initialize OrderBook
        this.orderBookEngine = new OrderBookEngine(this.orderBook); // Pass OrderBook to OrderBookEngine
        this.orderBookEngine.addTradeListener(this); // Register this service as a trade listener
        this.orderBookEngine.addOrderListener(this); // Register this service as an order listener
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        // Load open orders from DB into OrderBookEngine on startup (important for persistence)
        loadOpenOrdersIntoEngine();
    }

    /**
     * Loads open and partially filled orders from the database into the OrderBookEngine.
     * This method is called once on application startup.
     */
    private void loadOpenOrdersIntoEngine() {
        System.out.println("[MatchingService] Loading open orders from DB into OrderBookEngine...");
        List<Order> openOrders = orderRepository.findAll().stream()
                .filter(order -> order.getStatus() == OrderStatus.OPEN ||
                        order.getStatus() == OrderStatus.PARTIALLY_FILLED)
                .sorted(Comparator.comparing(Order::getTimestamp)) // Order List was sorted from the oldest to the newest
                .toList();
        for (Order order : openOrders) {
            orderBookEngine.addToOrderQueue(order);
        }
        System.out.println("[MatchingService] Loaded " + openOrders.size() + " open orders.");
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
        System.out.println("[MatchingService] Saved new order to DB (initial state): " + savedOrder.getOrderId());

        // Enqueue the order for asynchronous matching in the OrderBookEngine.
        // This call is non-blocking and returns quickly.
        orderBookEngine.addToOrderQueue(savedOrder);

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
        Optional<Order> orderOptional = orderRepository.findByOrderId(orderId);
        return orderOptional.orElse(null);
    }

    /**
     * Cancels an existing order.
     * The cancellation request is sent to the OrderBookEngine, which processes it asynchronously.
     * The DB update for cancellation will occur via the `onChange` listener.
     * @param orderId The UUID of the order to cancel.
     */
    public void cancelOrder(String orderId) {
        // Find order in DB to check its current status before attempting to cancel
        Optional<Order> orderOptional = orderRepository.findByOrderId(orderId);
        if (orderOptional.isPresent()) {
            Order orderToCancel = orderOptional.get();
            // Only allow cancellation if order is open or partially filled
            if (orderToCancel.getStatus() == OrderStatus.OPEN || orderToCancel.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                // Request engine to cancel. Engine will enqueue and process this on its single thread.
                orderBookEngine.cancelOrder(orderToCancel.getOrderId());
                System.out.println("[MatchingService] Sent cancellation request for order " + orderId + " to engine.");
            } else {
                System.out.println("[MatchingService] Order " + orderId + " cannot be canceled because its current status is " + orderToCancel.getStatus() + ".");
            }
        } else {
            System.out.println("[MatchingService] Order with orderId: " + orderId + " not found in DB, cannot send cancellation request.");
        }
    }

    /**
     * Gets the current Best Bid and Offer (BBO) from the order book.
     * This is a read-only operation on the OrderBook, which is safe for concurrent access.
     * @return The BBO object.
     */
    public BBO getBBO() {
        return orderBookEngine.getBBO();
    }

    /**
     * Returns a list of recent trades processed by the engine.
     * In a real system, you'd fetch this from a database.
     */
    @Transactional(readOnly = true)
    public List<Trade> getRecentTrades() {
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
        System.out.println("[MatchingService - onTrade] TRADE REPORTED: " + trade);
        // Save the trade to the database. This is critical for persisting trade history.
        tradeRepository.save(trade);
    }

    /**
     * Callback method called by OrderBookEngine when an order's status or quantity changes.
     * This method runs in the context of the OrderBookEngine's matching thread.
     * @param order The order whose state has changed.
     */
    @Override
    @Transactional // Ensures DB save for order status update occurs within a transaction
    public void onChange(Order order) {
        System.out.println("[MatchingService - onChange] ORDER STATUS CHANGED: " + order.getOrderId() +
                " -> Status: " + order.getStatus() +
                ", Remaining: " + String.format("%.2f", order.getRemainingQuantity()));
        // Update the order status/quantities in the database.
        orderRepository.save(order);
    }

    /**
     * Prints the current state of the order book for debugging/monitoring.
     */
    public void printCurrentOrderBook() {
        orderBookEngine.printOrderBook();
    }

    /**
     * Lifecycle hook for Spring to perform cleanup before the application shuts down.
     * Ensures the OrderBookEngine's internal thread pool is gracefully terminated.
     */
    @PreDestroy
    public void onApplicationShutdown() {
        System.out.println("[MatchingService] Shutting down OrderBookEngine gracefully...");
        this.orderBookEngine.shutdown();
    }
}