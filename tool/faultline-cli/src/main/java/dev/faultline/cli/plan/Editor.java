package dev.faultline.cli.plan;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a file in your editor and waits until you close it: $VISUAL or $EDITOR if set, otherwise VS Code
 * ("code --wait"), otherwise Notepad on Windows and vi elsewhere.
 */
final class Editor {
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private Editor() {}

    static void open(Path file) throws IOException, InterruptedException {
        String editor = System.getenv("VISUAL");
        if (editor == null || editor.isBlank()) editor = System.getenv("EDITOR");
        if (editor == null || editor.isBlank()) editor = onPath(WINDOWS ? "code.cmd" : "code") ? "code --wait" : WINDOWS ? "notepad" : "vi";

        var command = new ArrayList<String>();
        // code is a .cmd script on Windows, which only cmd can start
        if (WINDOWS) command.addAll(List.of("cmd", "/c"));
        command.addAll(List.of(editor.strip().split("\\s+")));
        command.add(file.toAbsolutePath().toString());
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (exit != 0) throw new IllegalStateException("the editor (" + editor + ") exited with code " + exit);
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            try {
                if (!dir.isBlank() && Files.isRegularFile(Path.of(dir.replace("\"", ""), program))) return true;
            } catch (InvalidPathException e) {
                // a malformed PATH entry: skip it
            }
        }
        return false;
    }
}
