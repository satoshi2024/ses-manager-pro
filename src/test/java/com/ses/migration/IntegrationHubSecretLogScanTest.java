package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** M: integration hubソースのsecret/PII log出力禁止を静的scanで固定する。 */
class IntegrationHubSecretLogScanTest {
    private static final List<Path> SCAN_ROOTS = List.of(
            Path.of("src/main/java/com/ses/config/integrationhub"),
            Path.of("src/main/java/com/ses/service/integrationhub"),
            Path.of("src/main/java/com/ses/controller/externalapi"),
            Path.of("src/main/java/com/ses/controller/api/IntegrationHubInboundEventAdminApiController.java"),
            Path.of("src/main/java/com/ses/controller/page/IntegrationHubInboundEventPageController.java"));

    private static final List<Pattern> FORBIDDEN_LOG_PATTERNS = List.of(
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*plaintextSecret"),
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*encryptedSecret"),
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*rawBody"),
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*rawRequest"),
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*decrypt\\("),
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*CLIENT_SECRET"),
            Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\([^)]*getMessage\\(\\)")
    );

    @Test
    void integrationHubソースはsecretやrawBodyをlogへ出力しない() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : SCAN_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.isDirectory(root)
                    ? Files.walk(root).filter(path -> path.toString().endsWith(".java"))
                    : Stream.of(root)) {
                paths.forEach(path -> scan(path, violations));
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "integration hub log scan violations:\n" + String.join("\n", violations));
    }

    private static void scan(Path file, List<String> violations) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = source.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!line.contains("log.")) {
                    continue;
                }
                for (Pattern pattern : FORBIDDEN_LOG_PATTERNS) {
                    if (pattern.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + " -> " + line.trim());
                    }
                }
            }
        } catch (IOException ex) {
            violations.add(file + ": unreadable -> " + ex.getMessage());
        }
    }
}
