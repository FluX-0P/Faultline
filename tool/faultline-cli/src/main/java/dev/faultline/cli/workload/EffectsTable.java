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
    private static final String UNDEFINED_SCHEMA = "3F000";

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

    /** Request IDs in the order their rows were added, by a column that grows with each insert (such as an id). */
    public List<String> appliedOrder(Connection db, String by) throws SQLException {
        if (!isIdentifier(by)) throw new IllegalArgumentException("not a column name: " + by);
        var ids = new ArrayList<String>();
        try (var st = db.createStatement(); var rs = st.executeQuery("select " + column + " from " + table + " order by " + by)) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        return ids;
    }

    /** Runs an app rule's query and returns each row it found as text, its columns joined by ", ". At most 1000 rows. */
    public static List<String> rows(Connection db, String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (var st = db.createStatement()) {
            st.setMaxRows(1000);
            try (var rs = st.executeQuery(sql.strip().replaceAll(";$", ""))) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    var row = new ArrayList<String>();
                    for (int c = 1; c <= columns; c++) row.add(rs.getString(c));
                    rows.add(String.join(", ", row));
                }
            }
        }
        return rows;
    }

    public record Reset(int ran, List<String> skipped) {}

    /**
     * Runs the plan's reset statements in one transaction. A statement on a table (or schema) that does not exist yet is
     * skipped: on a fresh database the app, or the stub, creates its tables (and starting data) when it starts.
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
                    if (!UNDEFINED_TABLE.equals(e.getSQLState()) && !UNDEFINED_SCHEMA.equals(e.getSQLState())) {
                        throw new SQLException("reset statement failed: " + sql, e);
                    }
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
