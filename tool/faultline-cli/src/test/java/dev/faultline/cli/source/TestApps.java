package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A small payments app on disk, shaped like demo-app, for tests that read an app's folder. */
public final class TestApps {
    private TestApps() {}

    public static Path payments(Path dir) throws IOException {
        write(dir, "README.md", "# Payments\n\nTakes payments over HTTP and applies them from Kafka.\n");
        write(dir, "pom.xml", """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.2</version></parent>
              <artifactId>payments</artifactId><version>1.2.0</version>
            </project>
            """);
        write(dir, "src/main/resources/application.yml", """
            server:
              port: 8081
            spring:
              datasource:
                url: jdbc:postgresql://localhost:5432/pay   # the local database
                username: pay
                password: secret
              kafka:
                consumer:
                  group-id: from-config
                  properties:
                    "[session.timeout.ms]": 6000
            app:
              topic: payments.in
            """);
        write(dir, "src/main/resources/db/migration/V1__init.sql", """
            -- planted: no unique key on payment_id
            create table payments (payment_id text not null); /* block
            comment */
            """);
        write(dir, "src/main/java/pay/Topics.java", """
            package pay;
            class Topics {
                static final String IN = "${app.topic}";
            }
            """);
        write(dir, "src/main/java/pay/PaymentRequest.java", """
            package pay;
            record PaymentRequest(String paymentId, long amount) {}
            """);
        write(dir, "src/main/java/pay/PaymentController.java", """
            package pay;
            @RestController
            @RequestMapping("/api")
            class PaymentController {
                @PostMapping("/payments")
                ResponseEntity<Void> pay(@RequestBody PaymentRequest req) { return null; }

                @GetMapping({"/payments/{id}", "/p/{id}"})
                Object get(String id) { return null; }
            }
            """);
        write(dir, "src/main/java/pay/PaymentListener.java", """
            package pay;
            @Component
            class PaymentListener {
                private final PaymentService service;

                // B1: planted bug, crash between commit and ack
                @KafkaListener(topics = Topics.IN)
                void onMessage(String payload, Acknowledgment ack) {
                    service.apply(payload);
                    ack.acknowledge();
                }

                @KafkaListener(topics = "audit", groupId = "auditors")
                void audit(String payload) {}
            }
            """);
        write(dir, "src/main/java/pay/PaymentService.java", """
            package pay;
            @Service
            class PaymentService {
                private final JdbcClient db;

                /** Applies one payment. */
                @Transactional
                void apply(String id) {
                    db.sql("insert into payments(payment_id) values (?)").param(id).update();
                }
            }
            """);
        write(dir, "src/test/java/pay/PaymentTest.java", "package pay; class PaymentTest {}");
        write(dir, "target/classes/ignored.txt", "build output");
        return dir;
    }

    private static void write(Path dir, String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
