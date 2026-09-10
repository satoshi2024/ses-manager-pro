package com.ses.service.ai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule/Geminiが同一の認可実装とBP法人predicateを使用する契約テスト。 */
class AiMatchingProviderScopeContractTest {
    @Test
    void RuleとGeminiは共通scopeGuardとBP法人predicateを使用する() throws Exception {
        String rule = source("com/ses/service/ai/impl/RuleMatchingServiceImpl.java");
        String gemini = source("com/ses/service/ai/impl/GeminiMatchingServiceImpl.java");
        for (String implementation : new String[]{rule, gemini}) {
            assertTrue(implementation.contains("AiMatchingScopeGuard"));
            assertTrue(implementation.contains("BpAvailability::getLegalEntityId"));
            assertTrue(implementation.contains("matchingScopeGuard.allowsBp"));
        }
        assertTrue(gemini.contains("AiGatewayRequest.legacyMatching(context)"));
        assertTrue(gemini.contains("fillMatchExplanation(dto"));
        assertTrue(gemini.contains("int defaultScore,\n                                      CopilotExecutionContext context"));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relative), StandardCharsets.UTF_8);
    }
}
