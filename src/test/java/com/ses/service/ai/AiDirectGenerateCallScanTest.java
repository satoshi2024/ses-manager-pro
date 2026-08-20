package com.ses.service.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDirectGenerateCallScanTest {

    @Test
    void mainのgenerate呼出はgatewayとprovider実装に限る() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.contains("AiExecutionGatewayImpl")
                        || relative.endsWith("TextServiceImpl.java")
                        || relative.endsWith("AiTextService.java")
                        || relative.contains("MockAiResponses")) {
                    return;
                }
                String source;
                try {
                    source = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                if (source.contains("aiTextService.generate(")) {
                    offenders.add(relative);
                }
            });
        }
        assertTrue(offenders.isEmpty(), "gateway外の AiTextService.generate: " + offenders);
    }
}
