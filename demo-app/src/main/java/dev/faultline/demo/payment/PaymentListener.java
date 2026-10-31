package dev.faultline.demo.payment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class PaymentListener {
    /** Time for a passing problem to clear before a payment on the retry topic is tried again. */
    static final long RETRY_DELAY_MS = 2_000;
    static final int POOL_THREADS = 4;

    private final PaymentService service;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final boolean retryTopic;
    /** Where payments are processed, so the listener can take the next one at once; null: on the listener's own thread. */
    private final ExecutorService pool;

    PaymentListener(PaymentService service, KafkaTemplate<String, String> kafka, JsonMapper json,
                    @Value("${payments.retries:retry-topic}") String retries, @Value("${payments.processing:thread-pool}") String processing) {
        this.service = service;
        this.kafka = kafka;
        this.json = json;
        this.retryTopic = retries.equals("retry-topic");
        this.pool = processing.equals("thread-pool")
            ? Executors.newFixedThreadPool(POOL_THREADS, Thread.ofPlatform().name("payment-pool-", 1).factory()) : null;
    }

    @KafkaListener(topics = TopicConfig.PAYMENTS_REQUESTED, groupId = "payment-worker")
    void onMessage(String payload, Acknowledgment ack) throws Exception {
        var event = json.readValue(payload, PaymentEvent.class);
        if (pool != null) {
            pool.submit(() -> service.process(event));
            ack.acknowledge();   // B2: acknowledged as soon as it is handed over, so a crash loses what the pool had not done
            return;
        }
        try {
            service.process(event);
        } catch (RuntimeException e) {
            // in place: the error handler retries this payment before any later one
            if (!retryTopic) throw e;
            // B9: retried later from another topic, so later payments for the same account overtake it
            kafka.send(TopicConfig.PAYMENTS_RETRY, event.accountId(), payload).get();
        }
        ack.acknowledge();   // B1: a crash between the commit above and this line re-runs the payment
    }

    @KafkaListener(topics = TopicConfig.PAYMENTS_RETRY, groupId = "payment-retry")
    void onRetry(String payload, Acknowledgment ack) throws Exception {
        Thread.sleep(RETRY_DELAY_MS);
        service.process(json.readValue(payload, PaymentEvent.class));
        ack.acknowledge();
    }

    @PreDestroy
    void stop() {
        if (pool != null) pool.shutdown();
    }
}
