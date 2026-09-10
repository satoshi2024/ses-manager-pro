package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B2 inbound parserはraw bodyをallow-list snapshotへ縮約し、未知field/重複を拒否する。 */
class ExternalApiInboundWebhookParserTest {
    private ExternalApiInboundWebhookParser parser;

    @BeforeEach
    void setUp() {
        parser = new ExternalApiInboundWebhookParser(new ObjectMapper(),
                new com.ses.config.integrationhub.IntegrationHubInboundProviderCatalog(Set.of("provider-a")));
    }

    @Test
    void rawBodyは永続化可能なmetadataだけへ縮約される() {
        ExternalApiInboundWebhookParser.Parsed parsed = parser.parse(
                "provider-a", "evt-1",
                ("{\"providerEventId\":\"evt-1\",\"eventType\":\"resource.changed\","
                        + "\"canonicalPayload\":{\"status\":\"ACTIVE\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                LocalDateTime.of(2026, 8, 31, 12, 0));

        assertTrue(parsed.snapshot().json().contains("\"provider\":\"provider-a\""));
        assertTrue(parsed.snapshot().json().contains("\"signatureResult\":\"VALID\""));
        assertFalse(parsed.snapshot().json().contains("rawBody"));
        assertFalse(parsed.snapshot().json().contains("secret"));
    }

    @Test
    void providerとcanonicalPayloadを省略した最小合法requestを受理する() {
        ExternalApiInboundWebhookParser.Parsed parsed = parser.parse(
                "provider-a", "evt-minimal",
                "{\"providerEventId\":\"evt-minimal\",\"eventType\":\"health.ping\"}"
                        .getBytes(StandardCharsets.UTF_8),
                LocalDateTime.of(2026, 8, 31, 12, 0));

        assertTrue(parsed.snapshot().json().contains("\"providerEventId\":\"evt-minimal\""));
        assertFalse(parsed.snapshot().json().contains("canonicalPayload"));
    }

    @Test
    void canonicalPayloadの未知nestedFieldとscalar型違いは400契約で拒否する() {
        assertThrows(com.ses.config.integrationhub.ExternalApiSecurityException.class, () -> parser.parse(
                "provider-a", "evt-nested",
                ("{\"providerEventId\":\"evt-nested\",\"eventType\":\"health.ping\","
                        + "\"canonicalPayload\":{\"payload\":{\"internalDatabaseId\":1}}}")
                        .getBytes(StandardCharsets.UTF_8),
                LocalDateTime.of(2026, 8, 31, 12, 0)));
        assertThrows(com.ses.config.integrationhub.ExternalApiSecurityException.class, () -> parser.parse(
                "provider-a", "evt-type",
                ("{\"providerEventId\":\"evt-type\",\"eventType\":\"health.ping\","
                        + "\"canonicalPayload\":{\"status\":{\"nested\":true}}}")
                        .getBytes(StandardCharsets.UTF_8),
                LocalDateTime.of(2026, 8, 31, 12, 0)));
    }

    @Test
    void providerEventId不一致と未知fieldをfailClosedする() {
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                ("{\"providerEventId\":\"evt-2\",\"eventType\":\"resource.changed\"}")
                        .getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                "{\"providerEventId\":\"evt-1\",\"eventType\":\"resource.changed\",\"payload\":{}}"
                        .getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                "{\"eventType\":\"resource.changed\"}".getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                "{\"providerEventId\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                "{\"providerEventId\":\"evt-1\",\"eventType\":\"resource.changed\",\"canonicalPayload\":[]}".getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
    }

    @Test
    void duplicateJsonKeyを受理しない() {
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                ("{\"providerEventId\":\"evt-1\",\"providerEventId\":\"evt-1\","
                        + "\"eventType\":\"resource.changed\"}")
                        .getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
    }
}
