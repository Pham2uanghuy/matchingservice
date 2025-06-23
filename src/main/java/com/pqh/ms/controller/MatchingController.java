package com.pqh.ms.controller;

import com.pqh.ms.entity.BBO;
import com.pqh.ms.entity.Order;
import com.pqh.ms.entity.Trade;
import com.pqh.ms.service.impl.MatchingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class MatchingController {

    private final MatchingService matchingService;

    public MatchingController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    /**
     * Endpoint to submit a new order (BUY or SELL).
     * Request Body: JSON representation of an Order (price, quantity, side, instrumentId, userId)
     * Response: The updated Order entity with its ID and current status.
     */
    @PostMapping
    public ResponseEntity<Order> placeOrder(@RequestBody Order order) {
        // Validate input 'order' if necessary (e.g., price > 0, quantity > 0)
        Order processedOrder = matchingService.placeOrder(order);
        return ResponseEntity.status(HttpStatus.CREATED).body(processedOrder);
    }

    /**
     * Endpoint to get the details of a specific order.
     * @param orderId The unique ID of the order.
     * @return The Order entity if found, or 404 NOT FOUND.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        Order order = matchingService.getOrder(orderId);
        if (order != null) {
            return ResponseEntity.ok(order);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Endpoint to cancel an order.
     * @param orderId The unique ID of the order to cancel.
     * @return 200 OK if canceled, or appropriate error if not found/cannot cancel.
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String orderId) {
        matchingService.cancelOrder(orderId);
        return ResponseEntity.ok().build();
    }

    /**
     * Endpoint to get the current Best Bid and Offer (BBO).
     * @return The BBO object.
     */
    @GetMapping("/bbo")
    public ResponseEntity<BBO> getBBO() {
        BBO bbo = matchingService.getBBO();
        return ResponseEntity.ok(bbo);
    }

    /**
     * Endpoint to get a list of recent trades.
     * @return A list of Trade objects.
     */
    @GetMapping("/trades/recent")
    public ResponseEntity<List<Trade>> getRecentTrades() {
        List<Trade> trades = matchingService.getRecentTrades();
        return ResponseEntity.ok(trades);
    }

    /**
     * Optional: Endpoint to print the current state of the order book for debugging.
     */
    @GetMapping("/book/print")
    public ResponseEntity<String> printOrderBook() {
        matchingService.printCurrentOrderBook();
        return ResponseEntity.ok("Order book printed to console.");
    }
}
