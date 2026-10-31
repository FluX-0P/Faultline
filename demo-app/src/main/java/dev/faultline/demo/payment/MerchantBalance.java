package dev.faultline.demo.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The shop's balance. Every payment is paid to it, and payments for different accounts are processed by different
 * workers, so several workers credit it at the same time.
 */
@Component
class MerchantBalance {
    static final String SHOP = "shop";

    private final JdbcClient db;
    /** B4 when false: two workers can read the same balance, and the second write undoes the first one's credit. */
    private final boolean locked;

    MerchantBalance(JdbcClient db, @Value("${payments.merchant-balance:read-then-write}") String mode) {
        this.db = db;
        this.locked = mode.equals("locked");
    }

    /** Called inside the payment's transaction. */
    void credit(long amountMinor) {
        write(read() + amountMinor);
    }

    long read() {
        // locked: the row stays locked until the payment commits, so the next worker waits, then reads the new balance
        return db.sql("select balance_minor from merchant where merchant_id = ?" + (locked ? " for update" : ""))
            .param(SHOP).query(Long.class).single();
    }

    void write(long balanceMinor) {
        db.sql("update merchant set balance_minor = ? where merchant_id = ?").params(balanceMinor, SHOP).update();
    }
}
