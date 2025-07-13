package com.pqh.ms.shared.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${spring.kafka.topic.order-command}")
    private String orderCommandTopic;

    @Value("${spring.kafka.topic.order-status-update}")
    private String orderStatusUpdateTopic;

    @Value("${spring.kafka.topic.trade}")
    private String tradeTopic;

    @Bean
    public NewTopic orderCommandTopic() {
        return TopicBuilder.name(orderCommandTopic)
                .build();
    }

    @Bean
    public NewTopic orderStatusUpdateTopic() {
        return TopicBuilder.name(orderStatusUpdateTopic)
                .build();
    }

    @Bean
    public NewTopic tradeTopic() {
        return TopicBuilder.name(tradeTopic)
                .build();
    }
}
