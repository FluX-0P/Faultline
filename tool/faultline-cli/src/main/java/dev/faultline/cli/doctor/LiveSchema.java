package dev.faultline.cli.doctor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import dev.faultline.cli.app.Environment;

/**
 * A running database's tables, read from information_schema: what the app made of its migrations, Liquibase changelogs
 * or Hibernate's ddl-auto. Tables outside public are named with their schema; partitions are left out, since the app
 * writes to their parent table. Each table's row count is what the app started with, so a table that has rows holds
 * data the app needs.
 */
final class LiveSchema {
    private static final String COLUMNS = """
        select c.table_schema, c.table_name, c.column_name, c.data_type
        from information_schema.columns c
        join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
        where t.table_type = 'BASE TABLE' and c.table_schema not in ('pg_catalog', 'information_schema')
          and not exists (select 1 from pg_catalog.pg_inherits i
                          join pg_catalog.pg_class child on child.oid = i.inhrelid
                          join pg_catalog.pg_namespace n on n.oid = child.relnamespace
                          where n.nspname = c.table_schema and child.relname = c.table_name)
        order by c.table_schema, c.table_name, c.ordinal_position""";

    private static final String PRIMARY_KEY = """
        select k.column_name
        from information_schema.table_constraints c
        join information_schema.key_column_usage k
          on k.constraint_name = c.constraint_name and k.table_schema = c.table_schema and k.table_name = c.table_name
        where c.constraint_type = 'PRIMARY KEY' and c.table_schema = ? and c.table_name = ?
        order by k.ordinal_position""";

    private LiveSchema() {}

    static List<Environment.Table> read(Connection conn) throws SQLException {
        var columns = new LinkedHashMap<String, List<Environment.Column>>();
        var quoted = new LinkedHashMap<String, String>();
        try (var st = conn.createStatement(); var rs = st.executeQuery(COLUMNS)) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                String name = schema.equals("public") ? table : schema + "." + table;
                quoted.putIfAbsent(name, quote(schema) + "." + quote(table));
                columns.computeIfAbsent(name, k -> new ArrayList<>()).add(new Environment.Column(rs.getString(3), rs.getString(4)));
            }
        }
        var tables = new ArrayList<Environment.Table>();
        for (var e : columns.entrySet()) {
            long rows;
            try (var st = conn.createStatement(); var rs = st.executeQuery("select count(*) from " + quoted.get(e.getKey()))) {
                rs.next();
                rows = rs.getLong(1);
            }
            String key = rows == 0 ? null : primaryKey(conn, e.getKey());
            List<String> keys = key == null ? null : keys(conn, quoted.get(e.getKey()), key);
            tables.add(new Environment.Table(e.getKey(), List.copyOf(e.getValue()), rows, key, keys));
        }
        return tables;
    }

    /** The table's primary key column, when it has a key of one column. */
    private static String primaryKey(Connection conn, String table) throws SQLException {
        String schema = table.contains(".") ? table.substring(0, table.indexOf('.')) : "public";
        String name = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
        try (var st = conn.prepareStatement(PRIMARY_KEY)) {
            st.setString(1, schema);
            st.setString(2, name);
            var columns = new ArrayList<String>();
            try (var rs = st.executeQuery()) {
                while (rs.next()) columns.add(rs.getString(1));
            }
            return columns.size() == 1 ? columns.getFirst() : null;
        }
    }

    /** The first values of the key, in order: rows a request can refer to. */
    private static List<String> keys(Connection conn, String table, String key) throws SQLException {
        var keys = new ArrayList<String>();
        try (var st = conn.createStatement();
             var rs = st.executeQuery("select " + quote(key) + " from " + table + " order by 1 limit " + Environment.Table.KEYS)) {
            while (rs.next()) keys.add(rs.getString(1));
        }
        return List.copyOf(keys);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
