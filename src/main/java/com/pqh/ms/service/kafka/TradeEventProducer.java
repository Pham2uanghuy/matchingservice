package com.pqh.ms.service.kafka;


import com.pqh.ms.service.kafka.msg.TradeEvent;
import com.pqh.ms.shared.BaseKafkaProducer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TradeEventProducer extends BaseKafkaProducer<TradeEvent>{
        private NewTopic tradeTopic;


        public TradeEventProducer(KafkaTemplate<String, TradeEvent> kafkaTemplate,
                             NewTopic tradeTopic) {
            super(kafkaTemplate);
            this.tradeTopic = tradeTopic;

        }

        public void sendTrade(TradeEvent event) {
            sendMessage(tradeTopic.name(), event);
        }
}
