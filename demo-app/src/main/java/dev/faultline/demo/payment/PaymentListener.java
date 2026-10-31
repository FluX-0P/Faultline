package dev.faultline.demo.payment;

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

    private final PaymentService service;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final boolean retryTopic;

    PaymentListener(PaymentService service, KafkaTemplate<String, String> kafka, JsonMapper json,
                    @Value("${payments.retries:retry-topic}") String retries) {
        this.service = service;
        this.kafka = kafka;
        this.json = json;
        this.retryTopic = retries.equals("retry-topic");
    }

    @KafkaListener(topics = TopicConfig.PAYMENTS_REQUESTED, groupId = "payment-worker")
    void onMessage(String payload, Acknowledgment ack) throws Exception {
        var event = json.readValue(payload, PaymentEvent.class);
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
}
