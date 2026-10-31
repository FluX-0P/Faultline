package dev.faultline.demo.payment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class TopicConfig {
    static final String PAYMENTS_REQUESTED = "payments.requested";
    static final String PAYMENTS_RETRY = "payments.retry";
    /** Where the API publishes when a chaos relay sits in front of payments.requested (payments.inbound-topic). */
    static final String PAYMENTS_RAW = "payments.raw";
    /** One event per payment that went through, for the rest of the system. */
    static final String PAYMENTS_COMPLETED = "payments.completed";

    // created at startup if missing, so the listeners never wait on an unknown topic
    @Bean
    NewTopic paymentsRequested() {
        return TopicBuilder.name(PAYMENTS_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentsRetry() {
        return TopicBuilder.name(PAYMENTS_RETRY).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentsRaw() {
        return TopicBuilder.name(PAYMENTS_RAW).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentsCompleted() {
        return TopicBuilder.name(PAYMENTS_COMPLETED).partitions(3).replicas(1).build();
    }

    /** What happens to a message whose listener keeps throwing. */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka, @Value("${payments.bad-messages:retry-forever}") String mode) {
        if (mode.equals("dead-letter")) {
            // after 2 more tries the message is parked on a dead-letter topic, and its partition moves on
            return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafka), new FixedBackOff(0, 2));
        }
        // B7: retry forever, so one message that can never succeed blocks every message behind it
        return new DefaultErrorHandler(new FixedBackOff(500, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
