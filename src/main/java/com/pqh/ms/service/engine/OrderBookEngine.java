package com.pqh.ms.service.engine;

import com.pqh.ms.entity.*;
import com.pqh.ms.service.impl.OrderListener;
import com.pqh.ms.service.impl.TradeListener;
import com.pqh.ms.service.kafka.OrderProducer;
import com.pqh.ms.service.kafka.TradeEventProducer;
import com.pqh.ms.service.kafka.msg.OrderEvent;
import com.pqh.ms.service.kafka.msg.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import  java.util.concurrent.atomic.AtomicBoolean;

public class OrderBookEngine {

    private static final Logger log = LoggerFactory.getLogger(OrderBookEngine.class);

    private final OrderBook orderBook;
    private final Map<String, Order> allOpenOrders; // Kept here for quick access and status management

    //field for kafka
    private final OrderProducer orderProducer;
    private final TradeEventProducer tradeEventProducer;

    //fields for multi-threading
    private final ExecutorService matchingExecutor; // For sequential matching of all orders
    private final ConcurrentLinkedQueue<Order> incomingOrderQueue; // Queue for new orders
    private final AtomicBoolean isRunning; // To control the matching loop


    //fields for redis
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String REDIS_ORDER_KEY_PREFIX = "order:"; // Redis key prefix for orders


    public OrderBookEngine(OrderBook orderBook, OrderProducer orderProducer, TradeEventProducer tradeEventProducer, RedisTemplate<String, Object> redisTemplate) {
        this.orderBook = orderBook;
        this.orderProducer = orderProducer;
        this.tradeEventProducer = tradeEventProducer;
        this.redisTemplate = redisTemplate;
        this.allOpenOrders = new ConcurrentHashMap<>();// Make listeners thread-safe

        // Use a single-threaded executor for sequential processing of orders
        this.matchingExecutor = Executors.newSingleThreadExecutor();
        this.incomingOrderQueue = new ConcurrentLinkedQueue<>();
        this.isRunning = new AtomicBoolean(true); // Start the engine as running
        startMatchingThread(); // Start the thread that processes the queue
    }

    /**
     * Starts the dedicated matching thread that processes orders from the queue.
     */
    private void startMatchingThread() {
        matchingExecutor.submit(() -> {
            log.info("[OrderBookEngine] Matching thread started.");
            while (isRunning.get() || !incomingOrderQueue.isEmpty()) { // Keep running if active or queue has orders
                try {
                    Order newOrder = incomingOrderQueue.poll(); // Get order from queue
                    if (newOrder != null) {
                        log.info("[OrderBookEngine] Processing order from queue: {}", newOrder.getOrderId());
                        processOrderInternal(newOrder); // Process the order
                    } else {
                        // If no orders, sleep briefly to avoid busy-waiting
                        TimeUnit.MILLISECONDS.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restore interrupt status
                    log.error("[OrderBookEngine] Matching thread interrupted, shutting down: {}", e.getMessage());
                    isRunning.set(false); // Signal to stop processing
                } catch (Exception e) {
                    log.error("[OrderBookEngine] Error processing order in matching thread: {}", e.getMessage(), e);
                }
            }
            log.info("[OrderBookEngine] Matching thread stopped gracefully.");
        });
    }

    /**
     * Shuts down the matching engine gracefully.
     * This method should be called when the application is shutting down.
     */
    public void shutdown() {
        log.info("[OrderBookEngine] Initiating shutdown...");
        isRunning.set(false); // Signal the matching thread to stop after processing current queue
        matchingExecutor.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks (orders in queue) to terminate
            if (!matchingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.error("[OrderBookEngine] Matching engine did not terminate gracefully within 30s. Forcing shutdown.");
                matchingExecutor.shutdownNow(); // Forcefully terminate if it takes too long
                // Wait a bit more for tasks to respond to being cancelled
                if (!matchingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("[OrderBookEngine] Matching engine did not terminate after forceful shutdown.");
                }
            }
            log.info("[OrderBookEngine] Matching engine shutdown complete.");
        } catch (InterruptedException ie) {
            matchingExecutor.shutdownNow();
            Thread.currentThread().interrupt(); // Preserve interrupt status
            log.error("[OrderBookEngine] Shutdown interrupted.");
        }
    }


    private void notifyTradeListeners(Trade trade) {
        TradeEvent tradeEvent = new TradeEvent();
        tradeEvent.setStatus("PENDING");
        tradeEvent.setMessage("save trade");
        tradeEvent.setTrade(trade);
        tradeEventProducer.sendTrade(tradeEvent);
    }



    public void notifyOrderListener(Order order) {
        OrderEvent orderEvent = new OrderEvent();
        orderEvent.setStatus("PENDING");
        orderEvent.setMessage("update order status");
        orderEvent.setOrder(order);
        orderProducer.sendOrderStatusUpdate(orderEvent);
    }

    /**
     * Adds a resting order to the order book. This is typically used for loading orders from DB on startup.
     * @param order The order to add.
     */
    public void addRestingOrder(Order order) {
        if (order.getRemainingQuantity() <= 0 &&
                order.getStatus() != OrderStatus.OPEN &&
                order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            log.warn("[OrderBookEngine] Attempted to add invalid resting order: {} (Qty: {}, Status: {})",
                    order.getOrderId(), order.getRemainingQuantity(), order.getStatus());
            return; // Only add valid open/partially filled orders as resting
        }
        orderBook.addOrder(order);
        allOpenOrders.put(order.getOrderId(), order);
        log.info("[OrderBookEngine] Added resting order: {} {} {}@{}",
                order.getOrderId(), order.getSide(), order.getRemainingQuantity(), order.getPrice());

        // Persist to Redis
        redisTemplate.opsForValue().set(REDIS_ORDER_KEY_PREFIX + order.getOrderId(), order);
        log.info("[OrderBookEngine] Added resting order: {} {} {}@{} and saved to Redis.",
                order.getOrderId(), order.getSide(), order.getRemainingQuantity(), order.getPrice());
    }

    /**
     * Enqueues a new incoming order for asynchronous processing by the single matching thread.
     * @param newOrder The new order to process.
     */
    public void addToOrderQueue(Order newOrder) {
        // Add order to the concurrent queue. This method returns immediately.
        incomingOrderQueue.offer(newOrder);
        // Save initial state to Redis when enqueued
        // New Order was already saved to DB, this is for the in-memory engine's perspective
        // We ensure it's in Redis *if* it's an open order.
        if (newOrder.getStatus() == OrderStatus.OPEN ||
                newOrder.getStatus() == OrderStatus.PARTIALLY_FILLED) {
            redisTemplate.opsForValue().set(
                    REDIS_ORDER_KEY_PREFIX + newOrder.getOrderId(),
                    newOrder);
            log.debug("[OrderBookEngine] Initial state of order {} saved to Redis.", newOrder.getOrderId());
        }
        log.info("[OrderBookEngine] Enqueued new order: {} (Side: {}, Qty: {}, Price: {})",
                newOrder.getOrderId(), newOrder.getSide(), newOrder.getOriginalQuantity(), newOrder.getPrice());
    }

    /**
     * Internal method to process an order, called ONLY by the single matching thread.
     * @param newOrder The new order to process.
     */
    private void processOrderInternal(Order newOrder) {
        List<Trade> trades = new ArrayList<>(); // Collect trades generated by this order
        log.info("[OrderBookEngine] Matching order: {} {} {}@{}",
                newOrder.getOrderId(), newOrder.getSide(), newOrder.getOriginalQuantity(), newOrder.getPrice());

        if (newOrder.getSide() == OrderSide.BUY) {
            // new order's final status was updated inside process function
            processBuyOrder(newOrder, trades);
        } else {
            processSellOrder(newOrder, trades);
        }
    }


    private void processBuyOrder(Order newOrder, List<Trade> trades) {
        // Iterator is safe with ConcurrentSkipListMap
        Iterator<Map.Entry<Double, List<Order>>> askIter = orderBook.getAskLevelsIterator();
        while (newOrder.getRemainingQuantity() > 0 && askIter.hasNext()) {
            Map.Entry<Double, List<Order>> askLevel = askIter.next();
            double askPrice = askLevel.getKey();
            List<Order> restingAsks = askLevel.getValue(); // This is a synchronizedList

            // Match if new buy order price >= best ask price
            if (newOrder.getPrice() >= askPrice) {
                log.debug("  Attempting to match buy order {} with ask level at price {}", newOrder.getOrderId(), askPrice);
                processLevel(newOrder, restingAsks, askPrice, trades, OrderSide.BUY);
                if (restingAsks.isEmpty()) {
                    askIter.remove(); // Remove price level if empty. Safe with Iterator.
                    log.debug("  Ask level at price {} is now empty and removed.", askPrice);
                }
            } else {
                log.debug("  Buy order {} price {} is lower than best ask price {}, breaking matching.", newOrder.getOrderId(), newOrder.getPrice(), askPrice);
                break; // No more matching opportunities at better prices
            }
        }
        if (newOrder.getRemainingQuantity() > 0) {
            addRestingOrder(newOrder); // Add remaining quantity as a resting order
            log.info("  New buy order {} has remaining quantity {} and added as resting order.", newOrder.getOrderId(), newOrder.getRemainingQuantity());
        }
    }

    private void processSellOrder(Order newOrder, List<Trade> trades) {
        // Iterator is safe with ConcurrentSkipListMap
        Iterator<Map.Entry<Double, List<Order>>> bidIter = orderBook.getBidLevelsIterator();
        while (newOrder.getRemainingQuantity() > 0 && bidIter.hasNext()) {
            Map.Entry<Double, List<Order>> bidLevel = bidIter.next();
            double bidPrice = bidLevel.getKey();
            List<Order> restingBids = bidLevel.getValue(); // This is a synchronizedList

            // Match if new sell order price <= best bid price
            if (newOrder.getPrice() <= bidPrice) {
                log.debug("  Attempting to match sell order {} with bid level at price {}", newOrder.getOrderId(), bidPrice);
                processLevel(newOrder, restingBids, bidPrice, trades, OrderSide.SELL);
                if (restingBids.isEmpty()) {
                    bidIter.remove(); // Remove price level if empty. Safe with Iterator.
                    log.debug("  Bid level at price {} is now empty and removed.", bidPrice);
                }
            } else {
                log.debug("  Sell order {} price {} is higher than best bid price {}, breaking matching.", newOrder.getOrderId(), newOrder.getPrice(), bidPrice);
                break; // No more matching opportunities at better prices
            }
        }
        if (newOrder.getRemainingQuantity() > 0) {
            addRestingOrder(newOrder); // Add remaining quantity as a resting order
            log.info("  New sell order {} has remaining quantity {} and added as resting order.", newOrder.getOrderId(), newOrder.getRemainingQuantity());
        }
    }

    private void processLevel(Order newOrder, List<Order> restingOrders, double tradePrice, List<Trade> trades, OrderSide side) {
        // The `restingOrders` list is a `Collections.synchronizedList`.
        // While the `matchingExecutor` ensures only one thread executes `processLevel` at any given time,
        // using `synchronized (restingOrders)` still protects against potential issues if `restingOrders`
        // could be directly modified by other parts of the application.
        synchronized (restingOrders) {
            Iterator<Order> restingIter = restingOrders.iterator();
            while (newOrder.getRemainingQuantity() > 0 && restingIter.hasNext()) {
                Order restingOrder = restingIter.next();
                double tradeQuantity = Math.min(newOrder.getRemainingQuantity(), restingOrder.getRemainingQuantity());

                if (tradeQuantity > 0) {
                    Trade trade = createTrade(newOrder, restingOrder, tradePrice, tradeQuantity, side);
                    trades.add(trade); // Add to local list of trades
                    notifyTradeListeners(trade); // **Notify listener immediately about the trade**
                    log.debug("  Trade created: quantity={}, price={}", tradeQuantity, tradePrice);

                    newOrder.fill(tradeQuantity); // update newOrder status
                    notifyOrderListener(newOrder); // send msg to kafka to save new order status for newOrder to DB
                    // Update newOrder in Redis
                    redisTemplate.opsForValue().set(
                            REDIS_ORDER_KEY_PREFIX + newOrder.getOrderId(),
                            newOrder);
                    log.debug("  New order {} filled by {} quantity, remaining {}. Status updated in Redis.",
                            newOrder.getOrderId(), tradeQuantity, newOrder.getRemainingQuantity());


                    restingOrder.fill(tradeQuantity); // update restingOrder status
                    notifyOrderListener(restingOrder); // send msg to kafka to save new status of restingOrder to DB
                    // Update restingOrder in Redis
                    redisTemplate.opsForValue().set(
                            REDIS_ORDER_KEY_PREFIX + restingOrder.getOrderId(),
                            restingOrder);
                    log.debug("  Resting order {} filled by {} quantity, remaining {}. Status updated in Redis.",
                            restingOrder.getOrderId(), tradeQuantity, restingOrder.getRemainingQuantity());


                    log.info("  Matched {} at {:.2f} (New {} Order: {:.2f} left, Resting {}: {:.2f} left)",
                            tradeQuantity, tradePrice, side, newOrder.getRemainingQuantity(),
                            (side == OrderSide.BUY ? "Ask" : "Bid"), restingOrder.getRemainingQuantity());

                    if (restingOrder.getStatus() == OrderStatus.FILLED) {
                        restingIter.remove();
                        allOpenOrders.remove(restingOrder.getOrderId()); // Remove from global map
                        // Remove filled order from Redis
                        redisTemplate.delete(REDIS_ORDER_KEY_PREFIX + restingOrder.getOrderId());
                        log.info("  Resting {} {} FILLED and removed from order book and Redis.",
                                (side == OrderSide.BUY ? "Ask" : "Bid"), restingOrder.getOrderId());
                    }
                }
            }
        }
    }

    private Trade createTrade(Order newOrder, Order restingOrder, double tradePrice, double tradeQuantity, OrderSide side) {
        String buyerOrderId = (side == OrderSide.BUY) ? newOrder.getOrderId() : restingOrder.getOrderId();
        String sellerOrderId = (side == OrderSide.BUY) ? restingOrder.getOrderId() : newOrder.getOrderId();
        String instrumentId = newOrder.getInstrumentId(); // Both orders must be for the same instrument

        return new Trade(
                UUID.randomUUID().toString(),
                buyerOrderId,
                sellerOrderId,
                instrumentId,
                tradePrice,
                tradeQuantity,
                Instant.now()
        );
    }

    /**
     * Cancels an order by its ID. The request is enqueued to be processed by the matching thread.
     * @param orderId The ID of the order to cancel.
     */
    public void cancelOrder(String orderId) {
        // Enqueue cancellation request to be processed by the single matching thread.
        // This ensures thread-safety and proper ordering with other matching operations.
        matchingExecutor.submit(() -> {
            Order orderToCancel = allOpenOrders.get(orderId); // Re-fetch to ensure latest state
            if (orderToCancel != null && orderToCancel.getStatus() != OrderStatus.FILLED && orderToCancel.getStatus() != OrderStatus.CANCELED) {
                orderBook.removeOrder(orderToCancel);
                orderToCancel.setStatus(OrderStatus.CANCELED);
                allOpenOrders.remove(orderId);
                // Update status in Redis to CANCELED, or remove it as it's no longer 'open'
                redisTemplate.delete(REDIS_ORDER_KEY_PREFIX + orderId);
                notifyOrderListener(orderToCancel); // Notify listeners about cancellation
                log.info("[OrderBookEngine] Order {} has been CANCELED by matching thread.", orderId);
            } else {
                log.warn("[OrderBookEngine] Order {} not found or cannot be canceled (current status: {}).",
                        orderId, (orderToCancel != null ? orderToCancel.getStatus() : "null"));
            }
        });
        log.info("[OrderBookEngine] Cancellation request for order {} enqueued.", orderId);
    }

    public Order getOrderById(String orderId) {
        // First try to get from in-memory map
        Order order = allOpenOrders.get(orderId);
        if (order != null) {
            log.debug("[OrderBookEngine] Order {} found in in-memory map.", orderId);
            return order;
        }
        // If not in memory (e.g., after a restart and before initial load completes), try Redis
        // This is primarily for resilience/debug and not main path for active orders
        log.debug("[OrderBookEngine] Order {} not found in in-memory map, attempting to retrieve from Redis.", orderId);
        return (Order) redisTemplate.opsForValue().get(REDIS_ORDER_KEY_PREFIX + orderId);
    }

    public List<Order> getAllOpenOrders() {
        // Return a copy to prevent ConcurrentModificationException if the underlying map changes
        // while an external thread is iterating over the returned list.
        log.debug("[OrderBookEngine] Retrieving all open orders from in-memory map.");
        return new ArrayList<>(allOpenOrders.values());
    }

    public BBO getBBO() {
        // BBO retrieval uses ConcurrentSkipListMap's firstEntry, which is thread-safe.
        log.debug("[OrderBookEngine] Retrieving Best Bid Offer (BBO).");
        return orderBook.getBBO();
    }

    public void printOrderBook() {
        // Printing iterates over ConcurrentSkipListMap, which provides weakly consistent iterators.
        // This is fine for a diagnostic print.
        log.info("[OrderBookEngine] Printing current state of the Order Book:");
        orderBook.printOrderBook(); // Assuming printOrderBook method in OrderBook class uses logging too
    }

    /**
     * Resets the order book, clearing both in-memory and Redis data.
     */
    public void reset() {
        log.warn("[OrderBookEngine] Resetting Order Book (clearing data, but matching thread continues).");
        incomingOrderQueue.clear();
        orderBook.clear();
        allOpenOrders.clear();
        // Clear all relevant order data from Redis as well
        // This can be slow if there are many keys. For a production system, consider a more targeted cleanup.
        Set<String> keys = redisTemplate.keys(REDIS_ORDER_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("[OrderBookEngine] Cleared {} orders from Redis.", keys.size());
        } else {
            log.info("[OrderBookEngine] No orders found in Redis to clear with prefix {}.", REDIS_ORDER_KEY_PREFIX);
        }
        log.warn("[OrderBookEngine] Order Book data cleared.");
    }
}