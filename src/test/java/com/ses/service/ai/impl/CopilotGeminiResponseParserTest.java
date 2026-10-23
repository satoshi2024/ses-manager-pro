package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void rejectsBlockedSafetyRatingInvalidCandidateIndexAndNonzeroOptionalUsage() {
        String valid = envelope("{\"queryId\":\"q-1\",\"summaryText\":\"ok\",\"claimKeys\":[]}", 1);
        assertThrows(BusinessException.class, () -> parse(valid.replace("\"finishReason\":", "\"index\":2147483648,\"finishReason\":")));
        assertThrows(BusinessException.class, () -> parse(valid.replace("\"finishReason\":", "\"safetyRatings\":[{\"category\":\"HARM_CATEGORY_NONE\",\"probability\":\"LOW\",\"blocked\":true}],\"finishReason\":")));
        assertThrows(BusinessException.class, () -> parse(valid.replace("\"totalTokenCount\":5", "\"totalTokenCount\":5,\"thoughtsTokenCount\":1")));
        assertThrows(BusinessException.class, () -> parse(valid.replace("\"totalTokenCount\":5", "\"totalTokenCount\":5,\"cachedContentTokenCount\":1")));
    }

    @Test
    void validatedOuterUsageSurvivesInnerJsonAndDeadlineFailures() {
        String malformedInner = envelope("{", 1);
        CopilotGeminiResponseParser.ValidatedEnvelope envelope = parser.parseEnvelope(
                malformedInner.getBytes(StandardCharsets.UTF_8), System.nanoTime() + 5_000_000_000L);

        CopilotGeminiResponseParser.ValidatedEnvelopeFailure invalidJson = assertThrows(
                CopilotGeminiResponseParser.ValidatedEnvelopeFailure.class,
                () -> parser.validateInnerSummary(envelope, System.nanoTime() + 5_000_000_000L, "q-1"));
        assertEquals("COPILOT_RESPONSE_INVALID", invalidJson.getMessage());
        assertEquals(2, invalidJson.usage().promptTokenCount());
        assertEquals(3, invalidJson.usage().candidateTokenCount());
        assertEquals(5, invalidJson.usage().totalTokenCount());

        CopilotGeminiResponseParser.ValidatedEnvelopeFailure deadline = assertThrows(
                CopilotGeminiResponseParser.ValidatedEnvelopeFailure.class,
                () -> parser.validateInnerSummary(envelope, System.nanoTime() - 1, "q-1"));
        assertEquals("COPILOT_DEADLINE_EXCEEDED", deadline.getMessage());
        assertEquals(envelope.usage(), deadline.usage());
    }

    @Test
    void outer候補失敗はusage値だけを保持しobservationを作らない() {
        String invalidCandidate = envelope(
                "{\"queryId\":\"q-1\",\"summaryText\":\"ok\",\"claimKeys\":[]}", 1)
                .replace("\"finishReason\":\"STOP\"", "\"finishReason\":\"MAX_TOKENS\"");
        CopilotGeminiResponseParser.ValidatedEnvelopeFailure failure = assertThrows(
                CopilotGeminiResponseParser.ValidatedEnvelopeFailure.class,
                () -> parser.parseEnvelope(invalidCandidate.getBytes(StandardCharsets.UTF_8),
                        System.nanoTime() + 5_000_000_000L));

        assertEquals(2, failure.usage().promptTokenCount());
        assertFalse(Arrays.stream(failure.getClass().getDeclaredFields())
                .anyMatch(field -> CopilotGeminiResponseParser.TransportUsageObservation.class
                        .isAssignableFrom(field.getType())));
        assertFalse(TransportBoundResponse.class.isAssignableFrom(failure.getClass()));
    }

    @Test
    void sealedObservationはtransportの単一privateissuerだけが実装する() throws Exception {
        assertTrue(TransportBoundResponse.class.isSealed());
        assertEquals(1, TransportBoundResponse.class.getPermittedSubclasses().length);
        Class<?> issuer = TransportBoundResponse.class.getPermittedSubclasses()[0];
        assertEquals(CopilotBoundedHttpTransport.IssuedTransportBoundResponse.class, issuer);
        assertTrue(Modifier.isFinal(issuer.getModifiers()));
        Constructor<?>[] constructors = issuer.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(issuer));
        assertFalse(CopilotBoundedHttpTransport.Response.class.isAssignableFrom(issuer));

        var issuerMethod = Arrays.stream(CopilotBoundedHttpTransport.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("issueBoundResponse"))
                .findFirst().orElseThrow();
        assertTrue(Modifier.isPrivate(issuerMethod.getModifiers()));
        assertEquals(1, issuerMethod.getParameterCount());
        Class<?> privateExchange = issuerMethod.getParameterTypes()[0];
        assertTrue(Modifier.isPrivate(privateExchange.getModifiers()));
        assertTrue(Arrays.stream(privateExchange.getDeclaredFields())
                .anyMatch(field -> javax.net.ssl.SSLSession.class.equals(field.getType())));
        assertFalse(Arrays.stream(CopilotBoundedHttpTransport.class.getMethods())
                .anyMatch(method -> TransportBoundResponse.class.isAssignableFrom(method.getReturnType())));

        var boundParser = CopilotGeminiResponseParser.class.getDeclaredMethod("parseBounded",
                TransportBoundResponse.class, long.class, String.class);
        assertEquals(CopilotGeminiResponseParser.BoundParsedResponse.class, boundParser.getReturnType());
        var rawParser = CopilotGeminiResponseParser.class.getDeclaredMethod(
                "parseEnvelope", byte[].class, long.class);
        assertEquals(CopilotGeminiResponseParser.ValidatedEnvelope.class, rawParser.getReturnType());
        assertFalse(TransportBoundResponse.class.isAssignableFrom(rawParser.getReturnType()));
        assertThrows(NoSuchMethodException.class, () -> CopilotGeminiResponseParser.class.getDeclaredMethod(
                "parseBounded", byte[].class, long.class, String.class));
        assertFalse(TransportBoundResponse.class.isAssignableFrom(SyntheticLoopbackObservation.class));
    }

    private static final class SyntheticLoopbackObservation {
        private final String marker = "SYNTHETIC_TEST_ONLY";
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
