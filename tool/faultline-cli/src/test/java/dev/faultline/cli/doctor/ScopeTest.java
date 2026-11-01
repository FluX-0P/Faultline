package dev.faultline.cli.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import dev.faultline.cli.source.App;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeTest {
    private static final String LISTENER = "package a;\nclass L {\n    @KafkaListener(topics = \"t\") void on(String m) {}\n}\n";

    @Test
    void aSpringBootAppWithKafkaListenersAndPostgresIsInScope(@TempDir Path dir) throws Exception {
        var verdict = Scope.check(TestStacks.shop(dir), App.read(dir));

        assertThat(verdict.out()).isEmpty();
        assertThat(verdict.found()).isEqualTo("Spring Boot 3 on Java 17, 1 @KafkaListener method, Postgres");
        assertThat(verdict.notes()).anyMatch(n -> n.startsWith("not a git checkout"));
    }

    @Test
    void anInMemoryDatabaseIsOutOfScope(@TempDir Path dir) throws Exception {
        app(dir, "<dependencies><dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId></dependency></dependencies>",
            "spring.datasource.url=jdbc:h2:mem:orders\n", LISTENER);

        assertThat(Scope.check(dir, App.read(dir)).out()).containsExactly("H2 only: an in-memory database inside the app, which Faultline cannot check from outside");
    }

    @Test
    void springCloudStreamConsumersAndMySqlAreOutOfScope(@TempDir Path dir) throws Exception {
        app(dir, "<dependencies><dependency><artifactId>spring-cloud-stream</artifactId></dependency>"
            + "<dependency><artifactId>mysql-connector-j</artifactId></dependency></dependencies>",
            "spring.datasource.url=jdbc:mysql://db/orders\n", "package a;\nclass F {}\n");

        assertThat(Scope.check(dir, App.read(dir)).out()).containsExactly(
            "its consumers are Spring Cloud Stream functions, not @KafkaListener methods: Faultline does not cover Spring Cloud Stream yet",
            "MySQL: Faultline checks Postgres databases only, for now");
    }

    @Test
    void javaOlderThan17IsOutOfScope(@TempDir Path dir) throws Exception {
        app(dir, "<properties><java.version>1.8</java.version></properties>", "spring.datasource.url=jdbc:postgresql://db/orders\n", LISTENER);

        assertThat(Scope.check(dir, App.read(dir)).out()).containsExactly("Java 8: Faultline's fault agent needs Java 17 or newer");
    }

    @Test
    void aScriptWithWindowsLineEndingsIsACheckoutALinuxContainerCannotRun(@TempDir Path dir) throws Exception {
        TestStacks.shop(dir);
        write(dir, "docker/postgres/init-user-db.sh", "#!/bin/bash\r\nset -e\r\n");

        assertThat(Scope.check(dir, App.read(dir)).out()).containsExactly("1 shell script of the checkout has Windows line endings, such as "
            + "docker/postgres/init-user-db.sh, which a Linux container cannot run: clone again with git -c core.longpaths=true -c core.autocrlf=false clone URL");
    }

    @Test
    void readsVersionsFromMavenAndGradle() {
        var java = List.of(Pattern.compile("<java\\.version>\\s*([\\d.]+)\\s*<"), Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"));
        assertThat(Scope.lowest(java, "<java.version>21</java.version> JavaLanguageVersion.of(17)")).hasValue(17);
        assertThat(Scope.lowest(java, "<java.version>${jdk}</java.version>")).isEmpty();
    }

    /** @param pom what the pom.xml has after its Spring Boot parent */
    private static void app(Path dir, String pom, String properties, String java) throws IOException {
        write(dir, "pom.xml", "<project><parent><artifactId>spring-boot-starter-parent</artifactId><version>3.3.0</version></parent>" + pom + "</project>\n");
        write(dir, "src/main/resources/application.properties", properties);
        write(dir, "src/main/java/a/Code.java", java);
    }

    private static void write(Path dir, String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
