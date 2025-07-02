package com.pqh.ms.entity;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
public class OrderBook {

    private final NavigableMap<Double, List<Order>> bidLevels;
    private final NavigableMap<Double, List<Order>> askLevels;

    public OrderBook() {
        //Treemap for Bids: descending price order ( highest bid first)
        this.bidLevels = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        // Treemap for Ask: ascending price order (lowest ask first)
        this.askLevels = new ConcurrentSkipListMap<>();
    }

    public void addOrder(Order order) {
         // Choose the appropriate Levels based on the OrderSide (BUY or SELL)
        Map<Double, List<Order>> targetBook =
                (order.getSide() == OrderSide.BUY) ? bidLevels : askLevels;

         //Add the order to the corresponding price level
         //If the price level does not exist, create a new synchronized list
        targetBook
                .computeIfAbsent(order.getPrice(), k -> Collections.synchronizedList(new LinkedList<>()))
                .add(order);
    }

    public void removeOrder(Order order) {
        // Choose the appropriate levels based on the OrderSide ( BUY or SELL)
        Map<Double, List<Order>> targetBook = (order.getSide() == OrderSide.BUY) ? bidLevels : askLevels;

         // Get Order list at the price of coming order;
        List<Order> ordersAtLevel = targetBook.get(order.getPrice());
        if (ordersAtLevel != null) {
            //Remove order if it exists

            ordersAtLevel.remove(order);
            if (ordersAtLevel.isEmpty()) {
                //Remove the price if no available orders at that price
                targetBook.remove(order.getPrice());
            }
        }
    }

    public Iterator<Map.Entry<Double, List<Order>>> getBidLevelsIterator() {
        return bidLevels.entrySet().iterator();
    }

    public Iterator<Map.Entry<Double, List<Order>>> getAskLevelsIterator() {
        return askLevels.entrySet().iterator();
    }

    public BBO getBBO() {
        BBO bbo = new BBO();
        if (!bidLevels.isEmpty()) {
            Map.Entry<Double, List<Order>> bestBid = bidLevels.firstEntry();
            bbo.bid_price = bestBid.getKey();
            bbo.bid_quantity = bestBid.getValue().stream().mapToDouble(Order::getRemainingQuantity).sum();
        }
        if (!askLevels.isEmpty()) {
            Map.Entry<Double, List<Order>> bestAsk = askLevels.firstEntry();
            bbo.ask_price = bestAsk.getKey();
            bbo.ask_quantity = bestAsk.getValue().stream().mapToDouble(Order::getRemainingQuantity).sum();
        }
        return bbo;
    }

    public void printOrderBook() {
        System.out.println("___ ORDER BOOK ___");
        System.out.println("Asks:");
        new ArrayList<>(askLevels.entrySet()).stream()
                .sorted(Map.Entry.comparingByKey(Comparator.reverseOrder()))
                .forEach(entry -> System.out.println("  " + entry.getValue().stream().mapToDouble(Order::getRemainingQuantity).sum() + " @ " + entry.getKey()));

        System.out.println("Bids:");
        bidLevels.forEach((price, orders) -> System.out.println("  " + orders.stream().mapToDouble(Order::getRemainingQuantity).sum() + " @ " + price));
        System.out.println("_________________");
    }

    public void clear() {
        bidLevels.clear();
        askLevels.clear();
    }
}
