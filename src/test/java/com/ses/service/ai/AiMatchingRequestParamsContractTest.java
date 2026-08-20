package com.ses.service.ai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17-P2-04: matching が t_ai_log.request_params にエンティティIDを書かないこと。
 */
class AiMatchingRequestParamsContractTest {

    @Test
    void geminiMatchingはAiLogへエンティティIDを書かない() throws Exception {
        String src = readMainSource("com/ses/service/ai/impl/GeminiMatchingServiceImpl.java");
        assertFalse(src.contains("AiLogMapper"), "AiLogMapper 依存を残してはいけない");
        assertFalse(src.contains("logAiMatch"), "logAiMatch を残してはいけない");
        assertFalse(src.contains("setRequestParams"), "request_params 書き込みを残してはいけない");
        assertFalse(src.contains("\"engineerId\""), "engineerId を params に書いてはいけない");
        assertFalse(src.contains("\"projectId\""), "projectId を params に書いてはいけない");
    }

    @Test
    void ruleMatchingはAiLogへエンティティIDを書かない() throws Exception {
        String src = readMainSource("com/ses/service/ai/impl/RuleMatchingServiceImpl.java");
        assertFalse(src.contains("AiLogMapper"), "AiLogMapper 依存を残してはいけない");
        assertFalse(src.contains("logAiMatch"), "logAiMatch を残してはいけない");
        assertFalse(src.contains("setRequestParams"), "request_params 書き込みを残してはいけない");
        assertFalse(src.contains("\"engineerId\""), "engineerId を params に書いてはいけない");
        assertFalse(src.contains("\"projectId\""), "projectId を params に書いてはいけない");
    }

    @Test
    void evaluationQueryは90日窓を持つ() throws Exception {
        String src = readMainSource("com/ses/service/ai/impl/AiEvaluationQueryServiceImpl.java");
        assertTrue(src.contains("minusDays(onlineWindowDays)") || src.contains("minusDays(90)"));
        assertTrue(src.contains("getCreatedAt"));
    }

    private static String readMainSource(String relativeUnderJava) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path candidate = root.resolve("src/main/java").resolve(relativeUnderJava);
        if (!Files.exists(candidate)) {
            candidate = Path.of("C:/work/ses-review-s17/src/main/java").resolve(relativeUnderJava);
        }
        assertTrue(Files.exists(candidate), "source not found: " + candidate);
        return Files.readString(candidate, StandardCharsets.UTF_8);
    }
}
