package dev.faultline.demo.payment;

import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the rest of the system that a payment went through, on payments.completed. Either the event is published once
 * the payment has committed, or it is saved to the outbox in the payment's own transaction and published from there.
 */
@Component
class CompletedEvents {
    private record OutboxRow(long id, String key, String payload) {}

    private final KafkaTemplate<String, String> kafka;
    private final JdbcClient db;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final boolean outbox;

    CompletedEvents(KafkaTemplate<String, String> kafka, JdbcClient db, JsonMapper json, ApplicationEventPublisher events,
                    @Value("${payments.completed-events:after-commit}") String mode) {
        this.kafka = kafka;
        this.db = db;
        this.json = json;
        this.events = events;
        this.outbox = mode.equals("outbox");
    }

    /** Called inside the payment's transaction. */
    void paymentApplied(PaymentEvent p) {
        var event = new PaymentCompleted(p.paymentId(), p.accountId(), p.amountMinor());
        if (outbox) {
            // the fix: saved with the payment, so it can only exist if the payment does, and goes out after a crash too
            db.sql("insert into outbox(event_key, payload) values (?, ?)").params(p.accountId(), json.writeValueAsString(event)).update();
        } else {
            events.publishEvent(event);
        }
    }

    // B8: the payment is saved, then the event is published. A crash in between loses the event: Kafka redelivers the
    // message, but the payment is already done, so nothing publishes it again.
    @TransactionalEventListener
    void onCommitted(PaymentCompleted event) {
        publish(event.accountId(), json.writeValueAsString(event));
    }

    /** The outbox relay, on every worker. Rows another worker is busy with are skipped; a row is deleted once sent. */
    @Scheduled(fixedDelay = 200)
    @Transactional
    public void publishOutbox() {
        if (!outbox) return;
        var rows = db.sql("select id, event_key, payload from outbox order by id limit 100 for update skip locked")
            .query((rs, n) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
        for (var row : rows) {
            publish(row.key(), row.payload());
            db.sql("delete from outbox where id = ?").param(row.id()).update();
        }
    }

    void publish(String key, String payload) {
        try {
            kafka.send(TopicConfig.PAYMENTS_COMPLETED, key, payload).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing a completed payment", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not publish a completed payment", e.getCause());
        }
    }
}
