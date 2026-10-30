package dev.faultline.cli.workload;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Everything specific to the demo payments app: how payments are generated, what the request looks like,
 * and which tables hold the results. Phase 4 moves these details into plan.yaml; until then they live only here.
 */
public final class PaymentWorkload {
    public static final String PATH = "/payments";
    public static final int ACCOUNTS = 100;
    public static final long INITIAL_BALANCE = 1_000_000;
    static final int MIN_AMOUNT = 100;
    static final int MAX_AMOUNT = 5_000;

    /**
     * The same seed always gives the same payments. java.util.Random's algorithm is fixed by the Java spec,
     * so this holds on every JDK and every machine.
     */
    public List<Payment> generate(long seed, int count) {
        var random = new Random(seed);
        var payments = new ArrayList<Payment>(count);
        for (int i = 0; i < count; i++) {
            String account = "acct-" + (1 + random.nextInt(ACCOUNTS));
            long amount = MIN_AMOUNT + random.nextInt(MAX_AMOUNT - MIN_AMOUNT + 1);
            payments.add(new Payment(i, "pay-" + seed + "-" + i, account, amount));
        }
        return payments;
    }

    public String requestBody(Payment p) {
        return "{\"paymentId\":\"%s\",\"accountId\":\"%s\",\"amountMinor\":%d}"
            .formatted(p.paymentId(), p.accountId(), p.amountMinor());
    }

    /** Empties the payments table and puts every account back to its starting balance, in one transaction. */
    public void reset(Connection db) throws SQLException {
        db.setAutoCommit(false);
        try (var st = db.createStatement()) {
            st.execute("truncate payments");
            st.execute("update accounts set balance_minor = " + INITIAL_BALANCE);
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    public long appliedRows(Connection db) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery("select count(*) from payments")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** How many times each payment ID was applied. */
    public Map<String, Integer> appliedCounts(Connection db) throws SQLException {
        var counts = new HashMap<String, Integer>();
        try (var st = db.createStatement();
             var rs = st.executeQuery("select payment_id, count(*) from payments group by payment_id")) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    public Map<String, Long> balances(Connection db) throws SQLException {
        var balances = new HashMap<String, Long>();
        try (var st = db.createStatement();
             var rs = st.executeQuery("select account_id, balance_minor from accounts")) {
            while (rs.next()) {
                balances.put(rs.getString(1), rs.getLong(2));
            }
        }
        return balances;
    }
}
