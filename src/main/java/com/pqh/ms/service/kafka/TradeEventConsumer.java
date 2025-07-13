package com.pqh.ms.service.kafka;

import com.pqh.ms.service.impl.MatchingService;
import com.pqh.ms.service.kafka.msg.TradeEvent;
import com.pqh.ms.shared.BaseKafkaConsumer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class TradeEventConsumer extends BaseKafkaConsumer<TradeEvent> {
    private final MatchingService matchingService;

    public TradeEventConsumer(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @KafkaListener(
            topics = "${spring.kafka.topic.trade}",
            groupId = "trade.matching-service"
    )
    public void listenOrderCommands(TradeEvent event) {
        handleMessage(event);
    }

    @Override
    protected void processEvent(TradeEvent event) {
        matchingService.onTrade(event.getTrade());
    }
}
