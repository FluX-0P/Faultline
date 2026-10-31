package dev.faultline.cli.source;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** The tables SQL statements in the code read, update or write, such as the strings of a method. */
public final class SqlTables {
    private static final Pattern SELECT_FROM = Pattern.compile("(?is)\\bselect\\b.+?\\bfrom\\s+([\\w.]+)");
    private static final Pattern UPDATE = Pattern.compile("(?is)^\\s*update\\s+([\\w.]+)\\s+set\\b");
    private static final Pattern INSERT = Pattern.compile("(?is)^\\s*insert\\s+into\\s+([\\w.]+)");
    private static final Pattern WRITE = Pattern.compile("(?is)^\\s*(insert\\s+into|update|delete\\s+from)\\s+([\\w.]+)");

    private SqlTables() {}

    /** Tables a select reads from. */
    public static Set<String> read(List<String> sql) {
        return tables(sql, SELECT_FROM);
    }

    public static Set<String> updated(List<String> sql) {
        return tables(sql, UPDATE);
    }

    public static Set<String> inserted(List<String> sql) {
        return tables(sql, INSERT);
    }

    /** Tables an insert, update or delete writes to. */
    public static Set<String> written(List<String> sql) {
        return tables(sql, WRITE);
    }

    private static Set<String> tables(List<String> sql, Pattern statement) {
        var tables = new TreeSet<String>();
        for (String s : sql) {
            var match = statement.matcher(s);
            if (match.find()) tables.add(match.group(match.groupCount()).toLowerCase(Locale.ROOT));
        }
        return tables;
    }
}
