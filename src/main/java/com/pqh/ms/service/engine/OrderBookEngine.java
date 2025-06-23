package com.pqh.ms.service.engine;

import com.pqh.ms.entity.*;
import com.pqh.ms.service.impl.TradeListener;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OrderBookEngine {

    private final OrderBook orderBook;
    private final Map<String, Order> allOpenOrders; // Kept here for quick access and status management
    private final List<TradeListener> tradeListeners;

    public OrderBookEngine(OrderBook orderBook) {
        this.orderBook = orderBook;
        this.allOpenOrders = new ConcurrentHashMap<>();
        this.tradeListeners = new ArrayList<>();
    }

    public void addTradeListener(TradeListener listener) {
        tradeListeners.add(listener);
    }

    private void notifyTradeListeners(Trade trade) {
        tradeListeners.forEach(listener -> listener.onTrade(trade));
    }

    /**
     * Adds a resting order to the order book.
     * @param order The order to add.
     */
    public void addRestingOrder(Order order) {
        if (order.getRemainingQuantity() <= 0) {
            return;
        }
        orderBook.addOrder(order);
        allOpenOrders.put(order.getOrderId(), order);
        System.out.println("Added resting order: " + order.getOrderId() + " " + order.getSide() + " " + order.getRemainingQuantity() + "@" + order.getPrice());
    }

    /**
     * Processes a new incoming order, attempting to match it against existing orders in the book.
     * @param newOrder The new order to process.
     * @return A list of trades generated from this new order.
     */
    public List<Trade> onNewOrder(Order newOrder) {
        List<Trade> trades = new ArrayList<>();
        initializeNewOrder(newOrder);

        System.out.println("\nProcessing new order: " + newOrder.getOrderId() + " " + newOrder.getSide() + " " + newOrder.getOriginalQuantity() + "@" + newOrder.getPrice());

        if (newOrder.getSide() == OrderSide.BUY) {
            processBuyOrder(newOrder, trades);
        } else {
            processSellOrder(newOrder, trades);
        }

        updateNewOrderStatus(newOrder);
        return trades;
    }

    private void initializeNewOrder(Order newOrder) {
        newOrder.setRemainingQuantity(newOrder.getOriginalQuantity());
        newOrder.setStatus(OrderStatus.OPEN);
        newOrder.setFilledQuantity(0.0);
        newOrder.setTimestamp(Instant.now());
    }

    private void processBuyOrder(Order newOrder, List<Trade> trades) {
        Iterator<Map.Entry<Double, List<Order>>> askIter = orderBook.getAskLevelsIterator();
        while (newOrder.getRemainingQuantity() > 0 && askIter.hasNext()) {
            Map.Entry<Double, List<Order>> askLevel = askIter.next();
            double askPrice = askLevel.getKey();
            List<Order> restingAsks = askLevel.getValue();

            if (newOrder.getPrice() >= askPrice) {
                processLevel(newOrder, restingAsks, askPrice, trades, OrderSide.BUY);
                if (restingAsks.isEmpty()) {
                    askIter.remove();
                }
            } else {
                break;
            }
        }
        if (newOrder.getRemainingQuantity() > 0) {
            addRestingOrder(newOrder);
        }
    }

    private void processSellOrder(Order newOrder, List<Trade> trades) {
        Iterator<Map.Entry<Double, List<Order>>> bidIter = orderBook.getBidLevelsIterator();
        while (newOrder.getRemainingQuantity() > 0 && bidIter.hasNext()) {
            Map.Entry<Double, List<Order>> bidLevel = bidIter.next();
            double bidPrice = bidLevel.getKey();
            List<Order> restingBids = bidLevel.getValue();

            if (newOrder.getPrice() <= bidPrice) {
                processLevel(newOrder, restingBids, bidPrice, trades, OrderSide.SELL);
                if (restingBids.isEmpty()) {
                    bidIter.remove();
                }
            } else {
                break;
            }
        }
        if (newOrder.getRemainingQuantity() > 0) {
            addRestingOrder(newOrder);
        }
    }

    private void processLevel(Order newOrder, List<Order> restingOrders, double tradePrice, List<Trade> trades, OrderSide side) {
        Iterator<Order> restingIter = restingOrders.iterator();
        while (newOrder.getRemainingQuantity() > 0 && restingIter.hasNext()) {
            Order restingOrder = restingIter.next();
            double tradeQuantity = Math.min(newOrder.getRemainingQuantity(), restingOrder.getRemainingQuantity());

            if (tradeQuantity > 0) {
                Trade trade = createTrade(newOrder, restingOrder, tradePrice, tradeQuantity, side);
                trades.add(trade);
                notifyTradeListeners(trade);

                newOrder.fill(tradeQuantity);
                restingOrder.fill(tradeQuantity);

                System.out.println("  Matched " + tradeQuantity + " at " + tradePrice + " (New " + side + " Order: " + newOrder.getRemainingQuantity() + " left, Resting " + (side == OrderSide.BUY ? "Ask" : "Bid") + ": " + restingOrder.getRemainingQuantity() + " left)");

                if (restingOrder.getStatus() == OrderStatus.FILLED) {
                    restingIter.remove();
                    allOpenOrders.remove(restingOrder.getOrderId());
                    System.out.println("  Resting " + (side == OrderSide.BUY ? "Ask" : "Bid") + " " + restingOrder.getOrderId() + " FILLED.");
                }
            }
        }
    }

    private Trade createTrade(Order newOrder, Order restingOrder, double tradePrice, double tradeQuantity, OrderSide side) {
        String buyerOrderId = (side == OrderSide.BUY) ? newOrder.getOrderId() : restingOrder.getOrderId();
        String sellerOrderId = (side == OrderSide.BUY) ? restingOrder.getOrderId() : newOrder.getOrderId();

        return new Trade(
                UUID.randomUUID().toString(),
                buyerOrderId,
                sellerOrderId,
                newOrder.getInstrumentId(),
                tradePrice,
                tradeQuantity,
                Instant.now()
        );
    }

    private void updateNewOrderStatus(Order newOrder) {
        if (newOrder.getStatus() == OrderStatus.FILLED || newOrder.getStatus() == OrderStatus.CANCELED) {
            allOpenOrders.remove(newOrder.getOrderId());
        } else {
            allOpenOrders.put(newOrder.getOrderId(), newOrder);
        }
    }

    /**
     * Cancels an order by its ID.
     * @param orderId The ID of the order to cancel.
     */
    public void cancelOrder(String orderId) {
        Order orderToCancel = allOpenOrders.get(orderId);
        if (orderToCancel != null && orderToCancel.getStatus() != OrderStatus.FILLED && orderToCancel.getStatus() != OrderStatus.CANCELED) {
            orderBook.removeOrder(orderToCancel);
            orderToCancel.setStatus(OrderStatus.CANCELED);
            allOpenOrders.remove(orderId);
            System.out.println("Order " + orderId + " has been CANCELED.");
        } else {
            System.out.println("Order " + orderId + " not found or cannot be canceled.");
        }
    }

    public Order getOrderById(String orderId) {
        return allOpenOrders.get(orderId);
    }

    public List<Order> getAllOpenOrders() {
        return new ArrayList<>(allOpenOrders.values());
    }

    public BBO getBBO() {
        return orderBook.getBBO();
    }

    public void printOrderBook() {
        orderBook.printOrderBook();
    }

    public void reset() {
        orderBook.clear();
        allOpenOrders.clear();
        System.out.println("Order Book Reset.");
    }
}