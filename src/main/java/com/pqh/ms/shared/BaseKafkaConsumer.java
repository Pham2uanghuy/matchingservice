package com.pqh.ms.shared;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseKafkaConsumer<T> {

    protected  final Logger log = LoggerFactory.getLogger(getClass());
    public void handleMessage(T event) {
        log.info("Received event of type {} from Kafka: {}", event.getClass().getSimpleName(), event);

        try {
            processEvent(event);
        } catch (Exception e) {
            log.error("Error processing event: {} - {}", event, e.getMessage(), e);
        }
    }

    protected abstract void processEvent(T event);
}
