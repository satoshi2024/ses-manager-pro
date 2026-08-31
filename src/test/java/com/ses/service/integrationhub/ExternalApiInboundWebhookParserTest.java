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
    void providerEventId不一致と未知fieldをfailClosedする() {
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                ("{\"providerEventId\":\"evt-2\",\"eventType\":\"resource.changed\"}")
                        .getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                "{\"providerEventId\":\"evt-1\",\"eventType\":\"resource.changed\",\"payload\":{}}"
                        .getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
    }

    @Test
    void duplicateJsonKeyを受理しない() {
        assertThrows(RuntimeException.class, () -> parser.parse("provider-a", "evt-1",
                ("{\"providerEventId\":\"evt-1\",\"providerEventId\":\"evt-1\","
                        + "\"eventType\":\"resource.changed\"}")
                        .getBytes(StandardCharsets.UTF_8), LocalDateTime.now()));
    }
}
