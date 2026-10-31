package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

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

    /** The app as it runs with these settings on top of its own configuration. */
    public App withSettings(Map<String, String> settings) {
        if (settings.isEmpty()) return this;
        var changed = config.with(settings);
        return new App(dir, files, changed, code.withConfig(changed));
    }
}
