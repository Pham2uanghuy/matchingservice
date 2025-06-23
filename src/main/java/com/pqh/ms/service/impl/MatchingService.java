package com.pqh.ms.service.impl;

import com.pqh.ms.entity.*;
import com.pqh.ms.repository.OrderRepository;
import com.pqh.ms.repository.TradeRepository;
import com.pqh.ms.service.engine.OrderBookEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class MatchingService implements TradeListener {

    private final OrderBookEngine orderBookEngine;
    private final OrderBook orderBook; // Reference to OrderBook
    private final OrderRepository orderRepository; // Inject OrderRepository
    private final TradeRepository tradeRepository; // Inject TradeRepository

    public MatchingService(OrderRepository orderRepository, TradeRepository tradeRepository) {
        this.orderBook = new OrderBook(); // Initialize OrderBook
        this.orderBookEngine = new OrderBookEngine(this.orderBook); // Pass OrderBook to OrderBookEngine
        this.orderBookEngine.addTradeListener(this); // Register this service as a trade listener
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;

        // Load open orders from DB into OrderBookEngine on startup (important for persistence)
        loadOpenOrdersIntoEngine();
    }

    // Method to load orders on startup
    private void loadOpenOrdersIntoEngine() {
        System.out.println("[MatchingService] Loading open orders from DB into OrderBookEngine...");
        List<Order> openOrders = orderRepository.findAll().stream()
                .filter(order -> order.getStatus() == OrderStatus.OPEN ||
                        order.getStatus() == OrderStatus.PARTIALLY_FILLED)
                .toList();
        for (Order order : openOrders) {
            orderBookEngine.addRestingOrder(order);
        }
        System.out.println("[MatchingService] Loaded " + openOrders.size() + " open orders.");
    }

    /**
     * Handles a new incoming order from a user.
     * @param order The order object received from the API.
     * @return The updated order object after processing (might be partially/fully filled).
     */
    @Transactional // Ensures all DB operations within this method are in the same transaction
    public Order placeOrder(Order order) {
        /**
         * filledQuantity, remainingQuantity, status, timestamp are set in custom Order constructor
         * Save the new order to DB before processing matching
         */
        order.setOrderId(UUID.randomUUID().toString());
        Order savedOrder = orderRepository.save(order);
        System.out.println("[MatchingService] Saved new order to DB: " + savedOrder.getOrderId());

        // Process the order through the order book engine
        List<Trade> trades = orderBookEngine.onNewOrder(savedOrder);

        // Save trades to DB
        tradeRepository.saveAll(trades);
        System.out.println("[MatchingService] Saved " + trades.size() + " trades to DB.");

        // Update the final status of the order in DB after matching
        return orderRepository.save(savedOrder); // Save the order again (its status might have changed)
    }

    /**
     * Retrieves an order by its orderId (UUID).
     * @param orderId The UUID of the order.
     * @return The Order object, or null if not found.
     */
    @Transactional(readOnly = true) // Read-only operation, no DB changes
    public Order getOrder(String orderId) {
        // Find order by orderId (UUID) instead of id (Long)
        Optional<Order> orderOptional = orderRepository.findByOrderId(orderId);
        return orderOptional.orElse(null);
    }

    /**
     * Cancels an existing order.
     * @param orderId The UUID of the order to cancel.
     */
    @Transactional
    public void cancelOrder(String orderId) {
        Optional<Order> orderOptional = orderRepository.findByOrderId(orderId);
        if (orderOptional.isPresent()) {
            Order orderToCancel = orderOptional.get();
            if (orderToCancel.getStatus() == OrderStatus.OPEN || orderToCancel.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                orderBookEngine.cancelOrder(orderToCancel.getOrderId()); // Request engine to cancel
                orderToCancel.setStatus(OrderStatus.CANCELED);
                orderRepository.save(orderToCancel); // Update cancellation status in DB
                System.out.println("[MatchingService] Canceled and updated order " + orderId + " in DB.");
            } else {
                System.out.println("[MatchingService] Order " + orderId + " cannot be canceled because its current status is " + orderToCancel.getStatus());
            }
        } else {
            System.out.println("[MatchingService] Order with orderId: " + orderId + " not found.");
        }
    }

    /**
     * Gets the current Best Bid and Offer (BBO) from the order book.
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
        // Retrieve recent trades from DB, e.g., the 100 latest trades
        return tradeRepository.findAll(); // findAll() might not be efficient with many trades
        // You should add pagination or limit the number in TradeRepository
    }

    /**
     * Callback for when a trade occurs in the OrderBookEngine.
     * This method will now primarily persist the trade.
     * @param trade The trade that occurred.
     */
    @Override
    public void onTrade(Trade trade) {
        // The trade was already created in OrderBookEngine
        System.out.println("[MatchingService - onTrade] TRADE REPORTED: " + trade);
        // This trade will be saved in the placeOrder() method when the order processing is complete.
        // If you want to save the trade immediately when it's created, you can uncomment the line below:
        // tradeRepository.save(trade); // Requires ensuring a transaction context if called here
    }

    public void printCurrentOrderBook() {
        orderBookEngine.printOrderBook();
    }
}