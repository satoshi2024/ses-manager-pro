package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalApiDataScopeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void intersectionIsFiniteAndDoesNotTurnMissingDimensionIntoAll() {
        ExternalApiDataScope client = ExternalApiDataScope.parse(
                "{\"projectIds\":[\"p-1\",\"p-2\"],\"tenantIds\":[\"tenant-a\"]}", objectMapper);
        ExternalApiDataScope route = ExternalApiDataScope.parse(
                "{\"projectIds\":[\"p-2\",\"p-3\"],\"tenantIds\":[\"tenant-a\"]}", objectMapper);
        ExternalApiDataScope intersection = client.intersect(route);
        assertEquals(java.util.Set.of("p-2"), intersection.values().get("projectIds"));
        assertEquals(java.util.Set.of("tenant-a"), intersection.values().get("tenantIds"));
        assertEquals(true, ExternalApiDataScope.parse("{\"projectIds\":[\"p-1\"]}", objectMapper)
                .intersect(ExternalApiDataScope.parse("{\"engineerIds\":[\"e-1\"]}", objectMapper)).isEmpty());
    }

    @Test
    void unknownEmptyDuplicateAndWildcardScopesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalApiDataScope.parse("{\"all\":[\"*\"]}", objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalApiDataScope.parse("{\"projectIds\":[]}", objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalApiDataScope.parse("{\"projectIds\":[\"p-1\",\"p-1\"]}", objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalApiDataScope.parse("{\"projectIds\":[\"*\"]}", objectMapper));
    }

    @Test
    void authoritativeTenantAndLegalEntityMustBeSingletonAndMatching() {
        ExternalApiDataScope omitted = ExternalApiDataScope.parse("{\"projectIds\":[\"p-1\"]}", objectMapper);
        assertDoesNotThrow(() -> omitted.requireAuthoritativeBinding("tenant-a", 9L));
        assertThrows(IllegalArgumentException.class, () -> ExternalApiDataScope.parse(
                "{\"projectIds\":[\"p-1\"],\"tenantIds\":[\"tenant-b\"]}", objectMapper)
                .requireAuthoritativeBinding("tenant-a", 9L));
        assertThrows(IllegalArgumentException.class, () -> ExternalApiDataScope.parse(
                "{\"projectIds\":[\"p-1\"],\"legalEntityIds\":[\"10\"]}", objectMapper)
                .requireAuthoritativeBinding("tenant-a", 9L));
    }

    @Test
    void emptyAuthoritativeIntersectionCannotBeDropped() {
        ExternalApiDataScope client = ExternalApiDataScope.parse(
                "{\"projectIds\":[\"p-1\"],\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"]}", objectMapper);
        ExternalApiDataScope route = ExternalApiDataScope.parse(
                "{\"projectIds\":[\"p-1\"],\"tenantIds\":[\"tenant-b\"],\"legalEntityIds\":[\"10\"]}", objectMapper);
        ExternalApiDataScope intersection = client.intersect(route);
        assertThrows(IllegalArgumentException.class,
                () -> intersection.requireAuthoritativeBinding("tenant-a", 9L));
        assertEquals(java.util.Set.of(), intersection.values().get("tenantIds"));
        assertEquals(java.util.Set.of(), intersection.values().get("legalEntityIds"));
    }
}
