package dev.faultline.cli.stub;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The stub's record of every charge, in Postgres: table charges in schema stub, created on start. Kept apart from the
 * app's tables but in the same database, so a plan's SQL rules can check it, such as one charge per payment.
 * A charge with an Idempotency-Key that was seen before is not charged again: the first charge is returned.
 */
final class Ledger implements AutoCloseable {
    static final String TABLE = "stub.charges";

    record Charge(long chargeId, boolean replayed) {}

    private final String url;
    private final String user;
    private final String password;
    private final BlockingQueue<Connection> pool;

    Ledger(String url, String user, String password, int connections) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.pool = new ArrayBlockingQueue<>(connections);
        for (int i = 0; i < connections; i++) pool.add(open());
        Connection c = pool.peek();
        try (var st = c.createStatement()) {
            st.execute("create schema if not exists stub");
            st.execute("""
                create table if not exists stub.charges (
                  id              bigserial primary key,
                  payment_id      text not null,
                  account_id      text,
                  amount_minor    bigint not null,
                  idempotency_key text unique,
                  created_at      timestamptz not null default clock_timestamp()
                )""");
        }
    }

    Charge charge(String paymentId, String accountId, long amountMinor, String idempotencyKey) throws SQLException, InterruptedException {
        Connection c = pool.take();
        try {
            if (idempotencyKey == null) return new Charge(insert(c, paymentId, accountId, amountMinor), false);
            Long id = insertOrNull(c, paymentId, accountId, amountMinor, idempotencyKey);
            if (id != null) return new Charge(id, false);
            try (var st = c.prepareStatement("select id from stub.charges where idempotency_key = ?")) {
                st.setString(1, idempotencyKey);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    return new Charge(rs.getLong(1), true);
                }
            }
        } catch (SQLException e) {
            c.close();
            c = open();
            throw e;
        } finally {
            pool.put(c);
        }
    }

    private static long insert(Connection c, String paymentId, String accountId, long amountMinor) throws SQLException {
        try (var st = c.prepareStatement("insert into stub.charges(payment_id, account_id, amount_minor) values (?, ?, ?) returning id")) {
            st.setString(1, paymentId);
            st.setString(2, accountId);
            st.setLong(3, amountMinor);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The new charge's ID, or null when a charge with this key already exists. */
    private static Long insertOrNull(Connection c, String paymentId, String accountId, long amountMinor, String key) throws SQLException {
        try (var st = c.prepareStatement("insert into stub.charges(payment_id, account_id, amount_minor, idempotency_key) values (?, ?, ?, ?) "
            + "on conflict (idempotency_key) do nothing returning id")) {
            st.setString(1, paymentId);
            st.setString(2, accountId);
            st.setLong(3, amountMinor);
            st.setString(4, key);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public void close() {
        for (Connection c : pool) {
            try {
                c.close();
            } catch (SQLException e) {
                // shutting down anyway
            }
        }
    }
}
