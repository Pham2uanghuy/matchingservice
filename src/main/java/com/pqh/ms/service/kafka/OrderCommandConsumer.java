package com.pqh.ms.service.kafka;

import com.pqh.ms.service.impl.MatchingService;
import com.pqh.ms.shared.BaseKafkaConsumer;
import com.pqh.ms.service.kafka.msg.OrderEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class OrderCommandConsumer extends BaseKafkaConsumer<OrderEvent> {
    private final MatchingService matchingService;

    public OrderCommandConsumer (MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @KafkaListener(
            topics = "${spring.kafka.topic.order-command}",
            groupId = "order.command.matching-service"
    )
    public void listenOrderCommands(OrderEvent event) {
        handleMessage(event);
    }


    @Override
    protected void processEvent(OrderEvent event) {
        matchingService.placeOrder(event.getOrder());
    }
}
