package dev.faultline.cli.workload;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * A request body with placeholders, filled in once per request:
 * <pre>
 *   ${seq}          the request's number in the run: 0, 1, 2 ...
 *   ${seed}         the run's seed
 *   ${int:MIN:MAX}  a random whole number from MIN to MAX, both included
 *   ${seq:N}        the same, with zeros in front to N digits, such as ${seq:12}; ${seed:N} likewise
 * </pre>
 * Random numbers come from one java.util.Random(seed), drawn left to right, so the same seed gives the same requests.
 * Zeros in front make a value of a fixed length, such as a UUID the app parses and the database prints back the same:
 * 00000000-0000-0000-${seq:4}-${seed:12} (a run's seed has up to 6 digits).
 */
public final class RequestTemplate {
    public static final String SYNTAX = "${seq}, ${seed} and ${int:MIN:MAX}";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern INT = Pattern.compile("int:(-?\\d+):(-?\\d+)");
    private static final Pattern PADDED = Pattern.compile("(seq|seed):(\\d{1,2})");

    private sealed interface Part permits Text, Seq, Seed, IntRange {}

    private record Text(String text) implements Part {}

    /** {@code width}: the digits it is padded to with zeros, 0 for none. */
    private record Seq(int width) implements Part {}

    private record Seed(int width) implements Part {}

    private record IntRange(int min, int max) implements Part {}

    private final String source;
    private final List<Part> parts;

    private RequestTemplate(String source, List<Part> parts) {
        this.source = source;
        this.parts = parts;
    }

    public static RequestTemplate parse(String template) {
        if (template == null || template.isBlank()) throw new IllegalArgumentException("the request body is empty");
        var parts = new ArrayList<Part>();
        var m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) parts.add(new Text(template.substring(last, m.start())));
            parts.add(placeholder(m.group(1)));
            last = m.end();
        }
        if (last < template.length()) parts.add(new Text(template.substring(last)));
        return new RequestTemplate(template, List.copyOf(parts));
    }

    private static Part placeholder(String name) {
        if (name.equals("seq")) return new Seq(0);
        if (name.equals("seed")) return new Seed(0);
        var padded = PADDED.matcher(name);
        if (padded.matches()) {
            int width = Integer.parseInt(padded.group(2));
            if (width < 1) throw new IllegalArgumentException("${" + name + "}: the digits to pad to must be at least 1");
            return padded.group(1).equals("seq") ? new Seq(width) : new Seed(width);
        }
        var m = INT.matcher(name);
        if (m.matches()) {
            long min = Long.parseLong(m.group(1));
            long max = Long.parseLong(m.group(2));
            if (min > max) throw new IllegalArgumentException("${" + name + "}: MIN is larger than MAX");
            if (min < Integer.MIN_VALUE || max - min + 1 > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("${" + name + "}: the range is too large");
            }
            return new IntRange((int) min, (int) max);
        }
        throw new IllegalArgumentException("unknown placeholder ${" + name + "}: use " + SYNTAX);
    }

    public String render(long seed, int seq, Random random) {
        var sb = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Text t -> sb.append(t.text());
                case Seq s -> sb.append(padded(seq, s.width()));
                case Seed s -> sb.append(padded(seed, s.width()));
                case IntRange r -> sb.append(r.min() + random.nextInt(r.max() - r.min() + 1));
            }
        }
        return sb.toString();
    }

    /** 42 as 0042 for a width of 4; as it is for 0, or when it has more digits than that. */
    private static String padded(long n, int width) {
        String digits = Long.toString(n);
        return width == 0 || digits.length() >= width || n < 0 ? digits : "0".repeat(width - digits.length()) + digits;
    }

    public String source() {
        return source;
    }
}
