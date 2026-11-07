package dev.faultline.cli.scan;

import java.util.List;
import java.util.Optional;

/**
 * The eight risky patterns the scan looks for. Each has the outcome it leads to, how bad that is (money over lost data
 * over slow recovery, for ranking), and what the LLM is told: what goes wrong, and what counts as protection.
 */
public record RiskPattern(String id, String title, String outcome, int severity, String risk) {
    static final int MONEY = 3;
    static final int DATA = 2;
    static final int SLOW = 1;

    static final List<RiskPattern> ALL = List.of(
        new RiskPattern("P1", "Save, then acknowledge the message", "applied twice", MONEY, """
            The Kafka listener does its work (usually database writes, committed in a transaction), then acknowledges the
            message. If the process crashes after the work is committed but before the acknowledgment, Kafka delivers the
            message again after the restart, and the work runs a second time.

            Protection: a duplicate check that commits in the same transaction as the work, such as the message's ID recorded
            under a unique key and skipped when seen before, a unique constraint that stops the second run before any of its
            effects happen, or an upsert keyed by the message's ID. A check outside the transaction, or one that runs after
            some effects were applied, does not count."""),
        new RiskPattern("P2", "Auto-commit with work handed to a thread pool", "lost", DATA, """
            The work for a Kafka message runs somewhere else than the listener's own call (an executor or thread pool,
            @Async, a CompletableFuture), or the consumer commits offsets on its own (auto-commit). Either way the message can
            count as done before its work is. If the process crashes then, the work is lost: Kafka will not deliver the
            message again.

            Protection: the offset is committed only after the work has finished, for example the listener waits for the work,
            or acknowledges only once it has committed."""),
        new RiskPattern("P3", "No duplicate check on a consumer", "applied twice", MONEY, """
            The method does the work for a Kafka message. Kafka delivers at least once, so the same message can arrive twice,
            and a client can also submit the same request twice, which arrives as two messages with different message IDs.

            Protection: a duplicate check that commits in the same transaction as the work and is keyed by what makes the
            request unique to the business (such as a payment or order ID). A check keyed only by an ID each message or
            submission gets for itself lets a second submission of the same request through."""),
        new RiskPattern("P4", "Read, modify, save without locking", "balance wrong", MONEY, """
            The method reads a value, computes a new one in the code, and writes it back. If two workers do this for the same
            row at the same time, both read the old value and the second write undoes the first.

            Protection: an atomic update (set x = x + ?), a read that locks the row until the write commits (select ... for
            update), or optimistic locking (a version checked by the update). Also judge whether two workers can really
            write the same row at the same time: a row only ever written by the consumer of one Kafka partition key is safe,
            a row that many workers write is not."""),
        new RiskPattern("P5", "Outside call without an idempotency key, or inside a DB transaction", "charged twice", MONEY, """
            The method calls an outside service, such as a payment provider, over the network. A call that times out may
            still have succeeded at the other end; if the code then tries again, the operation happens twice. A call made
            inside a database transaction makes this more likely: the transaction can roll back after the call succeeded,
            and the work, call included, is tried again.

            Protection: an idempotency key the service recognises a retry by (such as an Idempotency-Key header carrying a
            stable ID of the operation, the same on every attempt). With such a key, a call that is repeated, after a
            timeout or after a rollback, cannot happen twice, inside a transaction or not."""),
        new RiskPattern("P6", "Retries with no backoff or limit", "slow recovery", SLOW, """
            The code retries a failed operation. Retrying at once, with no delay or almost none, and without end buries a
            dependency that is struggling under requests, so it cannot recover, and the app stalls behind it.

            Protection: a delay between attempts that grows (exponential backoff, ideally with jitter), a limit on attempts,
            or a circuit breaker. Any one of them is enough, so the risk is retries that have none: retries that wait longer
            each time are not this risk even without a limit, and a limited number of retries is not this risk even without
            a delay."""),
        new RiskPattern("P7", "No dead-letter handling", "stuck", DATA, """
            A Kafka message that can never be processed (it does not parse, or always fails) is retried by the listener's
            error handling. If it is retried forever, it blocks every message behind it on its partition.

            Protection: after a limited number of attempts the message is set aside, on a dead-letter topic or by a
            recoverer, so its partition moves on."""),
        new RiskPattern("P8", "Save, then publish an event", "event lost", DATA, """
            The code saves to the database and publishes an event (for example to Kafka) as two separate steps. If the
            process crashes between them, or one of them fails, the event is lost, or sent for data that was never saved. A
            message redelivered after a crash usually finds the work already done, and publishes nothing. Publishing once
            the transaction has committed (an after-commit hook, @TransactionalEventListener) is still two steps: a crash
            after the commit and before the publish loses the event.

            Protection: a transactional outbox (the event is saved in the same transaction as the data and published from
            there, again after a crash), or another way that makes the publish certain once the data is saved."""),
        new RiskPattern("P9", "Relies on Kafka Connect delivering once", "applied twice", MONEY, """
            The Kafka listener consumes a topic that Kafka Connect fills, such as Debezium carrying an outbox table to
            Kafka. Kafka Connect delivers at least once: when its worker stops without a clean shutdown, it sends again what
            it had sent since it last stored its position, so the same event arrives twice, with the same content and the
            same ID.

            Protection: the consumer applies an event once however often it arrives: a duplicate check keyed by the event's
            ID (or the outbox row's), committed in the same transaction as the work, an upsert keyed by it, or a unique
            constraint that stops the second one before any of its effects."""));

    public static Optional<RiskPattern> byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
