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
            try (var st = conn.createStatement(); var rs = st.executeQuery("select count(*) from " + quoted.get(e.getKey()))) {
                rs.next();
                tables.add(new Environment.Table(e.getKey(), List.copyOf(e.getValue()), rs.getLong(1)));
            }
        }
        return tables;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
