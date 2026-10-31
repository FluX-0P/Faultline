package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Path;

/** Everything Faultline reads from an app's folder, read once per command. */
public record App(Path dir, RepoFiles files, SpringConfig config, JavaSources code) {

    public static App read(Path dir) throws IOException {
        var files = RepoFiles.scan(dir);
        var config = SpringConfig.load(files);
        return new App(dir, files, config, JavaSources.parse(files, config));
    }

    public String name() {
        return files.name();
    }
}
