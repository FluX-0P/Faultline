package dev.faultline.demo.payment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class TopicConfig {
    static final String PAYMENTS_REQUESTED = "payments.requested";

    // created at startup if missing, so the listener never waits on an unknown topic
    @Bean
    NewTopic paymentsRequested() {
        return TopicBuilder.name(PAYMENTS_REQUESTED).partitions(3).replicas(1).build();
    }
}
