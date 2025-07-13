package com.pqh.ms.service.engine;

import com.pqh.ms.entity.*;
import com.pqh.ms.service.impl.OrderListener;
import com.pqh.ms.service.impl.TradeListener;
import com.pqh.ms.service.kafka.OrderProducer;
import com.pqh.ms.service.kafka.TradeEventProducer;
import com.pqh.ms.service.kafka.msg.OrderEvent;
import com.pqh.ms.service.kafka.msg.TradeEvent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import  java.util.concurrent.atomic.AtomicBoolean;

public class OrderBookEngine {

    private final OrderBook orderBook;
    private final Map<String, Order> allOpenOrders; // Kept here for quick access and status management
    private final List<TradeListener> tradeListeners;
    private final OrderProducer orderProducer;
    private final TradeEventProducer tradeEventProducer;

    //fields for multi-threading
    private final ExecutorService matchingExecutor; // For sequential matching of all orders
    private final ConcurrentLinkedQueue<Order> incomingOrderQueue; // Queue for new orders
    private final AtomicBoolean isRunning; // To control the matching loop


    public OrderBookEngine(OrderBook orderBook, OrderProducer orderProducer, TradeEventProducer tradeEventProducer) {
        this.orderBook = orderBook;
        this.orderProducer = orderProducer;
        this.tradeEventProducer = tradeEventProducer;
        this.allOpenOrders = new ConcurrentHashMap<>();
        this.tradeListeners = Collections.synchronizedList(new ArrayList<>()); // Make listeners thread-safe

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
            System.out.println("[OrderBookEngine] Matching thread started.");
            while (isRunning.get() || !incomingOrderQueue.isEmpty()) { // Keep running if active or queue has orders
                try {
                    Order newOrder = incomingOrderQueue.poll(); // Get order from queue
                    if (newOrder != null) {
                        System.out.println("[OrderBookEngine] Processing order from queue: " + newOrder.getOrderId());
                        processOrderInternal(newOrder); // Process the order
                    } else {
                        // If no orders, sleep briefly to avoid busy-waiting
                        TimeUnit.MILLISECONDS.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restore interrupt status
                    System.err.println("[OrderBookEngine] Matching thread interrupted, shutting down: " + e.getMessage());
                    isRunning.set(false); // Signal to stop processing
                } catch (Exception e) {
                    System.err.println("[OrderBookEngine] Error processing order in matching thread: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            System.out.println("[OrderBookEngine] Matching thread stopped gracefully.");
        });
    }

    /**
     * Shuts down the matching engine gracefully.
     * This method should be called when the application is shutting down.
     */
    public void shutdown() {
        System.out.println("[OrderBookEngine] Initiating shutdown...");
        isRunning.set(false); // Signal the matching thread to stop after processing current queue
        matchingExecutor.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks (orders in queue) to terminate
            if (!matchingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                System.err.println("[OrderBookEngine] Matching engine did not terminate gracefully within 30s. Forcing shutdown.");
                matchingExecutor.shutdownNow(); // Forcefully terminate if it takes too long
                // Wait a bit more for tasks to respond to being cancelled
                if (!matchingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    System.err.println("[OrderBookEngine] Matching engine did not terminate after forceful shutdown.");
                }
            }
            System.out.println("[OrderBookEngine] Matching engine shutdown complete.");
        } catch (InterruptedException ie) {
            matchingExecutor.shutdownNow();
            Thread.currentThread().interrupt(); // Preserve interrupt status
            System.err.println("[OrderBookEngine] Shutdown interrupted.");
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
            return; // Only add valid open/partially filled orders as resting
        }
        orderBook.addOrder(order);
        allOpenOrders.put(order.getOrderId(), order);
        System.out.println("[OrderBookEngine] Added resting order: " + order.getOrderId() + " " + order.getSide() + " " + order.getRemainingQuantity() + "@" + order.getPrice());
    }

    /**
     * Enqueues a new incoming order for asynchronous processing by the single matching thread.
     * @param newOrder The new order to process.
     */
    public void addToOrderQueue(Order newOrder) {
        // Add order to the concurrent queue. This method returns immediately.
        incomingOrderQueue.offer(newOrder);
        System.out.println("[OrderBookEngine] Enqueued new order: " + newOrder.getOrderId() + " (Side: " + newOrder.getSide() + ", Qty: " + newOrder.getOriginalQuantity() + ", Price: " + newOrder.getPrice() + ")");
    }

    /**
     * Internal method to process an order, called ONLY by the single matching thread.
     * @param newOrder The new order to process.
     */
    private void processOrderInternal(Order newOrder) {
        List<Trade> trades = new ArrayList<>(); // Collect trades generated by this order
        System.out.println("[OrderBookEngine] Matching order: " + newOrder.getOrderId() + " " + newOrder.getSide() + " " + newOrder.getOriginalQuantity() + "@" + newOrder.getPrice());

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
                processLevel(newOrder, restingAsks, askPrice, trades, OrderSide.BUY);
                if (restingAsks.isEmpty()) {
                    askIter.remove(); // Remove price level if empty. Safe with Iterator.
                }
            } else {
                break; // No more matching opportunities at better prices
            }
        }
        if (newOrder.getRemainingQuantity() > 0) {
            addRestingOrder(newOrder); // Add remaining quantity as a resting order
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
                processLevel(newOrder, restingBids, bidPrice, trades, OrderSide.SELL);
                if (restingBids.isEmpty()) {
                    bidIter.remove(); // Remove price level if empty. Safe with Iterator.
                }
            } else {
                break; // No more matching opportunities at better prices
            }
        }
        if (newOrder.getRemainingQuantity() > 0) {
            addRestingOrder(newOrder); // Add remaining quantity as a resting order
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

                    newOrder.fill(tradeQuantity); // update newOrder status
                    notifyOrderListener(newOrder); // **Notify listener to save new order status for newOrder to DB

                    restingOrder.fill(tradeQuantity); // update restingOrder status
                    notifyOrderListener(restingOrder); // **Notify listener to save new status of restingOrder to DB

                    System.out.println("  Matched " + tradeQuantity + " at " + String.format("%.2f", tradePrice) +
                            " (New " + side + " Order: " + String.format("%.2f", newOrder.getRemainingQuantity()) + " left, Resting " +
                            (side == OrderSide.BUY ? "Ask" : "Bid") + ": " + String.format("%.2f", restingOrder.getRemainingQuantity()) + " left)");

                    if (restingOrder.getStatus() == OrderStatus.FILLED) {
                        restingIter.remove();
                        allOpenOrders.remove(restingOrder.getOrderId()); // Remove from global map
                        System.out.println("  Resting " + (side == OrderSide.BUY ? "Ask" : "Bid") + " " + restingOrder.getOrderId() + " FILLED.");
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
                notifyOrderListener(orderToCancel); // Notify listeners about cancellation
                System.out.println("[OrderBookEngine] Order " + orderId + " has been CANCELED by matching thread.");
            } else {
                System.out.println("[OrderBookEngine] Order " + orderId + " not found or cannot be canceled (current status: " + (orderToCancel != null ? orderToCancel.getStatus() : "null") + ").");
            }
        });
        System.out.println("[OrderBookEngine] Cancellation request for order " + orderId + " enqueued.");
    }

    public Order getOrderById(String orderId) {
        // This is a read operation on ConcurrentHashMap, which is thread-safe.
        return allOpenOrders.get(orderId);
    }

    public List<Order> getAllOpenOrders() {
        // Return a copy to prevent ConcurrentModificationException if the underlying map changes
        // while an external thread is iterating over the returned list.
        return new ArrayList<>(allOpenOrders.values());
    }

    public BBO getBBO() {
        // BBO retrieval uses ConcurrentSkipListMap's firstEntry, which is thread-safe.
        return orderBook.getBBO();
    }

    public void printOrderBook() {
        // Printing iterates over ConcurrentSkipListMap, which provides weakly consistent iterators.
        // This is fine for a diagnostic print.
        orderBook.printOrderBook();
    }

    public void reset() {
        // To reset, we should stop the processing thread, clear data, and then potentially restart.
        System.out.println("[OrderBookEngine] Resetting Order Book (clearing data, but matching thread continues).");
        // Clear the queue first to discard pending orders
        incomingOrderQueue.clear();
        orderBook.clear();
        allOpenOrders.clear();
        System.out.println("[OrderBookEngine] Order Book data cleared.");
    }
}