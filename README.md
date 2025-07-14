# Matching Order Service - Order Book Engine (Java + Spring Boot)

This service implements a basic **Order Matching Engine** for a centralized exchange (CEX), inspired by [philipperemy/Order-Book-Matching-Engine](https://github.com/philipperemy/Order-Book-Matching-Engine).

**Note:** This project is **for learning purposes only** and is currently **under development**. It is **not suitable for production** use. The core matching logic works ,and integrations with Kafka and Redis are implemented, but many features are still being implemented or tested.

---

## Educational Goals
- Understanding Order Books: Gain insight into the fundamental mechanisms of an order book, including bid and ask levels, and how orders are matched.
- Java Collections for Order Book Management: Learn practical techniques for managing complex data structures like bid/ask levels using Java's rich collection framework.
- Spring Boot Integration with Asynchronous Components: Practice integrating various asynchronous components within a Spring Boot application.
- Event-Driven Design with Kafka: Apply event-driven architectural principles using Kafka as a messaging backbone for decoupled communication.
- Redis as a Cache Layer: Implement Redis for caching to improve performance and reduce direct database interactions.
- System Decoupling for Scalability and Performance: Understand and implement strategies for decoupling system components to enhance scalability and overall performance.

---

## Tech Stack

- Java 17+
- Spring Boot
- Maven
- PostgreSQL (optional for persistence)
- JUnit (for testing)
- Kafka (event-bus)
- Redis (cache between service and database)

---

## Component Responsibilities

### Kafka
Kafka is used as a messaging layer between components:
- Controller → MatchingService
- MatchingService ↔ OrderBookEngine
- 
Kafka enables asynchronous, decoupled processing between layers.

### Redis

Redis acts as a cache between the matching service and the database.
The MatchingService does not interact directly with PostgreSQL.

Responsibilities:
-Store open orders and recent trades
- Update cache on order status and trade when matching
- Support read-through and write-through caching

### PostgreSQL
PostgreSQL is used for persistent storage:
- Order history
- Trade logs


> **Status:** Educational project — still evolving

