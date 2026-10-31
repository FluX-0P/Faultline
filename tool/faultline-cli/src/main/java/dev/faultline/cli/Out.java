package dev.faultline.cli;

import java.io.PrintWriter;
import java.nio.file.Path;

/** The CLI's output: one formatted line at a time, never interleaved when several threads print. */
public final class Out {
    private Out() {}

    public static void line(PrintWriter out, String format, Object... args) {
        synchronized (out) {
            out.println(args.length == 0 ? format : format.formatted(args));
            out.flush();
        }
    }

    /** A path with / separators, so a printed command also works when pasted into Git Bash. */
    public static String path(Path path) {
        return path.toString().replace('\\', '/');
    }
}
