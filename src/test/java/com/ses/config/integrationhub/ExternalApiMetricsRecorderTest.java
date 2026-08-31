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

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
