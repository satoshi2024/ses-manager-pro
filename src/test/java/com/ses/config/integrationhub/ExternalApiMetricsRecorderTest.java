package com.ses.config.integrationhub;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalApiMetricsRecorderTest {
    @Test
    void labelsAreFiniteAndNeverContainRequestIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExternalApiMetricsRecorder recorder = new ExternalApiMetricsRecorder(providerOf(registry));
        recorder.record("/external-api/v1/projects/{publicProjectId}", "GET", 200,
                "AUTHORIZED", "STANDARD");
        recorder.record("/external-api/v1/projects/p-secret-client-id", "GET", 404,
                "UNKNOWN_ROUTE_OR_METHOD", "client-a");

        assertEquals(2, registry.getMeters().size());
        registry.getMeters().forEach(meter -> {
            meter.getId().getTags().forEach(tag -> {
                assertTrue(tag.getValue().length() <= 64 || "route".equals(tag.getKey()));
                assertTrue(!tag.getValue().contains("p-secret-client-id"));
                assertTrue(!tag.getValue().contains("client-a"));
                assertTrue(!tag.getValue().contains("correlation"));
            });
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "AUTHORIZED,SUCCESS", "AUTHENTICATED,SUCCESS", "SCOPE_ALLOWED,SUCCESS",
            "UNTRUSTED_PROXY,SOURCE_REJECTED", "DUPLICATE_PROXY_HEADER,SOURCE_REJECTED",
            "PROXY_HEADER_MISSING,SOURCE_REJECTED", "UNSUPPORTED_PROXY_CHAIN,SOURCE_REJECTED",
            "SOURCE_IP_INVALID,SOURCE_REJECTED", "CLIENT_IP_NOT_ALLOWED,AUTH_REJECTED",
            "NONCE_REPLAY,AUTH_REJECTED", "FORBIDDEN_SCOPE,SCOPE_REJECTED", "RATE_LIMITED,RATE_LIMITED",
            "DISABLED_DENY,DISABLED", "INTERNAL_ERROR,INTERNAL_ERROR", "REQUEST_TARGET_INVALID,REQUEST_REJECTED",
            "NOT_AUTHORIZED_OR_SUCCESS,OTHER"})
    void decisionは部分一致せず固定分類へ対応付く(String decision, String outcome) {
        assertEquals(outcome, ExternalApiMetricsRecorder.finiteOutcome(decision));
    }

    @Test
    void 認証認可の固定decisionがOTHERに落ちないことを機械的に確認する() throws Exception {
        var pattern = java.util.regex.Pattern.compile("(?:authentication|forbidden|invalid|notFound|conflict)\\(\"([A-Z_]+)\"");
        try (var paths = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/com/ses/config/integrationhub"))) {
            for (var path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(java.nio.file.Files.readString(path));
                while (matcher.find()) {
                    org.junit.jupiter.api.Assertions.assertNotEquals("OTHER",
                            ExternalApiMetricsRecorder.finiteOutcome(matcher.group(1)), path + ": " + matcher.group(1));
                }
            }
        }
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
