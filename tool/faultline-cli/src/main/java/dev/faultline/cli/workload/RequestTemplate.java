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
 * </pre>
 * Random numbers come from one java.util.Random(seed), drawn left to right, so the same seed gives the same requests.
 */
public final class RequestTemplate {
    public static final String SYNTAX = "${seq}, ${seed} and ${int:MIN:MAX}";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern INT = Pattern.compile("int:(-?\\d+):(-?\\d+)");

    private sealed interface Part permits Text, Seq, Seed, IntRange {}

    private record Text(String text) implements Part {}

    private record Seq() implements Part {}

    private record Seed() implements Part {}

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
        if (name.equals("seq")) return new Seq();
        if (name.equals("seed")) return new Seed();
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
                case Seq _ -> sb.append(seq);
                case Seed _ -> sb.append(seed);
                case IntRange r -> sb.append(r.min() + random.nextInt(r.max() - r.min() + 1));
            }
        }
        return sb.toString();
    }

    public String source() {
        return source;
    }
}
