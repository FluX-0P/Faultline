package dev.faultline.demo.payment;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService {
    /** How a redelivered payment is recognised. Each value but CLAIM_FIRST plants a bug. */
    enum Dedupe {
        /** B1: no check at all, so a message redelivered after a crash is applied twice. */
        NONE,
        /** B10: check, then act. Two workers can both see "not done yet" for the same payment and both apply it. */
        CHECK_THEN_INSERT,
        /** The fix: claim the payment first, in the same transaction. A second worker waits on the row, then skips. */
        CLAIM_FIRST
    }

    private final JdbcClient db;
    private final Dedupe dedupe;

    PaymentService(JdbcClient db, @Value("${payments.dedupe:none}") String dedupe) {
        this.db = db;
        this.dedupe = Dedupe.valueOf(dedupe.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    @Transactional
    public void process(PaymentRequested p) {
        switch (dedupe) {
            case NONE -> apply(p);
            case CHECK_THEN_INSERT -> {
                if (alreadyProcessed(p)) return;
                apply(p);
                markProcessed(p);
            }
            case CLAIM_FIRST -> {
                if (markProcessed(p) == 0) return;
                apply(p);
            }
        }
    }

    void apply(PaymentRequested p) {
        db.sql("update accounts set balance_minor = balance_minor - ? where account_id = ?")
          .params(p.amountMinor(), p.accountId()).update();
        db.sql("insert into payments(payment_id, account_id, amount_minor) values (?, ?, ?)")
          .params(p.paymentId(), p.accountId(), p.amountMinor()).update();
    }

    private boolean alreadyProcessed(PaymentRequested p) {
        return db.sql("select exists(select 1 from processed_events where payment_id = ?)")
            .param(p.paymentId()).query(Boolean.class).single();
    }

    private int markProcessed(PaymentRequested p) {
        return db.sql("insert into processed_events(payment_id) values (?) on conflict do nothing")
            .param(p.paymentId()).update();
    }
}
