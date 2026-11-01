package dev.faultline.cli.run;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

import dev.faultline.cli.plan.Plan;

/** A connection to each database of a plan, open for the whole run. */
final class Databases implements AutoCloseable {
    private final Map<Plan.Database, Connection> connections = new IdentityHashMap<>();

    private Databases() {}

    static Databases open(Plan plan) throws SQLException {
        var databases = new Databases();
        try {
            for (var db : plan.allDatabases()) databases.connections.put(db, DriverManager.getConnection(db.url(), db.user(), db.password()));
        } catch (SQLException e) {
            databases.close();
            throw e;
        }
        return databases;
    }

    Connection of(Plan.Database db) {
        var conn = connections.get(db);
        if (conn == null) throw new IllegalArgumentException("no connection to " + db.url());
        return conn;
    }

    @Override
    public void close() throws SQLException {
        SQLException first = null;
        for (var conn : connections.values()) {
            try {
                conn.close();
            } catch (SQLException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
