package dev.faultline.cli.workload;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where the app records what it did: one row per processed request in {@code table}, with the request's ID in
 * {@code column}. Both names come from the plan, so they must be plain SQL identifiers before they go into a query.
 */
public final class EffectsTable {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final String UNDEFINED_TABLE = "42P01";

    private final String table;
    private final String column;

    public EffectsTable(String table, String column) {
        if (!isIdentifier(table)) throw new IllegalArgumentException("not a table name: " + table);
        if (!isIdentifier(column)) throw new IllegalArgumentException("not a column name: " + column);
        this.table = table;
        this.column = column;
    }

    public static boolean isIdentifier(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }

    public String table() {
        return table;
    }

    public long rows(Connection db) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** How many rows each request ID has. */
    public Map<String, Integer> appliedCounts(Connection db) throws SQLException {
        var counts = new HashMap<String, Integer>();
        try (var st = db.createStatement();
             var rs = st.executeQuery("select " + column + ", count(*) from " + table + " group by " + column)) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    public record Reset(int ran, List<String> skipped) {}

    /**
     * Runs the plan's reset statements in one transaction. A statement on a table that does not exist yet is skipped:
     * on a fresh database the app creates its tables (and starting data) when it starts.
     */
    public static Reset reset(Connection db, List<String> statements) throws SQLException {
        int ran = 0;
        var skipped = new ArrayList<String>();
        db.setAutoCommit(false);
        try (var st = db.createStatement()) {
            for (String sql : statements) {
                Savepoint before = db.setSavepoint();
                try {
                    st.execute(sql);
                    ran++;
                } catch (SQLException e) {
                    if (!UNDEFINED_TABLE.equals(e.getSQLState())) throw new SQLException("reset statement failed: " + sql, e);
                    db.rollback(before);
                    skipped.add(sql);
                }
            }
            db.commit();
            return new Reset(ran, skipped);
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }
}
