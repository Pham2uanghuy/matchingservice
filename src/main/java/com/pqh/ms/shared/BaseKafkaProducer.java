package com.pqh.ms.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

public abstract class BaseKafkaProducer<T>{
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected KafkaTemplate<String, T> kafkaTemplate;

    protected BaseKafkaProducer(KafkaTemplate<String, T> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    protected void sendMessage(String topic, T payload) {
        log.info("Sending message to topic {}: {}", topic, payload.toString());

        Message<T> message = MessageBuilder
                .withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .build();

        kafkaTemplate.send(message);
    }


}
