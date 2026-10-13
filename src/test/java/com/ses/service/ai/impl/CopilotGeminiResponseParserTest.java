package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopilotGeminiResponseParserTest {
    private final CopilotGeminiResponseParser parser = new CopilotGeminiResponseParser();

    @Test
    void acceptsOneStoppedCandidateAndExactQueryBoundSummary() {
        String body = """
                {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"{\\"queryId\\":\\"q-1\\",\\"summaryText\\":\\"概要です\\",\\"claimKeys\\":[\\"sales\\"]}"}]}}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":5,"totalTokenCount":9}}
                """;
        assertEquals("{\"queryId\":\"q-1\",\"summaryText\":\"概要です\",\"claimKeys\":[\"sales\"]}",
                parser.extractSummaryJson(body.getBytes(StandardCharsets.UTF_8), System.nanoTime() + 5_000_000_000L,
                        "q-1"));
    }

    @Test
    void rejectsDuplicateKeysAdditionalCandidatesAndQueryMismatch() {
        String duplicate = "{\"candidates\":[],\"candidates\":[],\"usageMetadata\":{}}";
        assertThrows(BusinessException.class, () -> parse(duplicate));
        String twoCandidates = envelope("{\"queryId\":\"q-1\",\"summaryText\":\"ok\",\"claimKeys\":[]}", 2);
        assertThrows(BusinessException.class, () -> parse(twoCandidates));
        String mismatch = envelope("{\"queryId\":\"q-2\",\"summaryText\":\"ok\",\"claimKeys\":[]}", 1);
        assertThrows(BusinessException.class, () -> parse(mismatch));
    }

    @Test
    void rejectsUnknownFieldsThoughtsInvalidUsageAndLooseInnerTypes() {
        assertThrows(BusinessException.class, () -> parse(envelope("{\"queryId\":\"q-1\",\"summaryText\":\"ok\",\"claimKeys\":[],\"extra\":1}", 1)));
        assertThrows(BusinessException.class, () -> parse(envelope("{\"queryId\":\"q-1\",\"summaryText\":\"ok\",\"claimKeys\":[]}", 1)
                .replace("\"text\":", "\"thought\":true,\"text\":")));
        assertThrows(BusinessException.class, () -> parse(envelope("{\"queryId\":\"q-1\",\"summaryText\":\"ok\",\"claimKeys\":[]}", 1)
                .replace("\"totalTokenCount\":5", "\"totalTokenCount\":\"5\"")));
        assertThrows(BusinessException.class, () -> parse(envelope("{\"queryId\":\"q-1\",\"summaryText\":1,\"claimKeys\":[]}", 1)));
    }

    private void parse(String body) {
        parser.extractSummaryJson(body.getBytes(StandardCharsets.UTF_8), System.nanoTime() + 5_000_000_000L, "q-1");
    }

    private String envelope(String summary, int candidateCount) {
        String escaped = summary.replace("\\", "\\\\").replace("\"", "\\\"");
        String candidate = "{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\""
                + escaped + "\"}]}}";
        String candidates = candidateCount == 1 ? "[" + candidate + "]" : "[" + candidate + "," + candidate + "]";
        return "{\"candidates\":" + candidates + ",\"usageMetadata\":{\"promptTokenCount\":2,"
                + "\"candidatesTokenCount\":3,\"totalTokenCount\":5}}";
    }
}
