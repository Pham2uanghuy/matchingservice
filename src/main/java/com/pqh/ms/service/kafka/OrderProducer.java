package com.pqh.ms.service.kafka;

import com.pqh.ms.shared.BaseKafkaProducer;
import com.pqh.ms.service.kafka.msg.OrderEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderProducer extends BaseKafkaProducer<OrderEvent> {
    private final NewTopic orderCommandTopic;

    private final NewTopic orderStatusUpdateTopic;

    public OrderProducer(KafkaTemplate<String, OrderEvent> kafkaTemplate,
                         NewTopic orderCommandTopic,
                         NewTopic orderStatusUpdateTopic) {
        super(kafkaTemplate);
        this.orderCommandTopic = orderCommandTopic;
        this.orderStatusUpdateTopic = orderStatusUpdateTopic;
    }


    public void sendOrderCommand(OrderEvent event) {
        sendMessage(orderCommandTopic.name(), event);
    }

    public void sendOrderStatusUpdate(OrderEvent event) {
        sendMessage(orderStatusUpdateTopic.name(), event);
    }

}
