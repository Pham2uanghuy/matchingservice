package com.pqh.ms.service.kafka;

import com.pqh.ms.service.impl.MatchingService;
import com.pqh.ms.service.kafka.msg.OrderEvent;
import com.pqh.ms.shared.BaseKafkaConsumer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class OrderStatusUpdateConsumer extends BaseKafkaConsumer<OrderEvent> {
    private final MatchingService matchingService;

    public OrderStatusUpdateConsumer(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @KafkaListener(
            topics = "${spring.kafka.topic.order-status-update}",
            groupId = "order.order-status-update.matching-service"
    )
    public void listen(OrderEvent event) {
        handleMessage(event);
    }


    @Override
    protected void processEvent(OrderEvent event) {
        matchingService.onChange(event.getOrder());
    }
}
