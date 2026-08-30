package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCursorCodec;
import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.dto.integrationhub.ExternalApiProject;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.mapper.ExternalApiReadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalApiReadServiceTest {
    private final ExternalApiReadMapper mapper = mock(ExternalApiReadMapper.class);
    private final ExternalApiPrincipal principal = new ExternalApiPrincipal(
            "client-a", 7L, "tenant-a", 9L, "{\"projectIds\":[\"1\",\"2\"]}", 1, "key-1", "STANDARD");
    private final ExternalApiEffectiveScope scope = new ExternalApiEffectiveScope("tenant-a", 9L, Map.of(
            "tenantIds", Set.of("tenant-a"), "legalEntityIds", Set.of("9"),
            "projectIds", Set.of("1", "2"), "customerIds", Set.of("10")));
    private ExternalApiReadService service;

    @BeforeEach
    void setUp() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        properties.getPublicApi().setCursorTtlSeconds(300);
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
        service = new ExternalApiReadService(mapper, new ExternalApiPublicIdCodec(properties),
                new ExternalApiCursorCodec(properties), clock);
    }

    @Test
    void listUsesOnlyEffectiveScopeAndReturnsAllowListDtoWithOpaqueCursor() {
        when(mapper.selectProjects(List.of(1L, 2L), List.of(10L), null, 2)).thenReturn(List.of(
                projectRow(2L, 10L), projectRow(1L, 10L)));
        ExternalApiReadRow extra = projectRow(0L, 10L);
        when(mapper.selectProjects(List.of(1L, 2L), List.of(10L), null, 3)).thenReturn(List.of(
                projectRow(2L, 10L), projectRow(1L, 10L), extra));

        var response = service.listProjects(principal, scope, 2, null);

        assertEquals(2, response.items().size());
        assertTrue(response.hasMore());
        assertTrue(response.nextCursor().startsWith("v1."));
        ExternalApiProject first = response.items().get(0);
        assertEquals("ACTIVE", first.status());
        verify(mapper).selectProjects(List.of(1L, 2L), List.of(10L), null, 3);
    }

    @Test
    void cursorBindsScopeAndUsesLastInternalIdOnlyInsideMapper() {
        when(mapper.selectProjects(List.of(1L, 2L), List.of(10L), null, 2)).thenReturn(List.of(
                projectRow(2L, 10L), projectRow(1L, 10L)));
        String cursor = service.listProjects(principal, scope, 1, null).nextCursor();
        when(mapper.selectProjects(List.of(1L, 2L), List.of(10L), 2L, 2)).thenReturn(List.of(projectRow(1L, 10L)));

        var response = service.listProjects(principal, scope, 1, cursor);

        assertEquals(1, response.items().size());
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), response.asOf());
        verify(mapper).selectProjects(List.of(1L, 2L), List.of(10L), 2L, 2);
    }

    @Test
    void detailRequiresOpaqueIdFromEffectiveScopeAndDoesNotEnumerate() {
        ExternalApiPublicIdCodec codec = new ExternalApiPublicIdCodec(properties());
        String publicId = codec.encode(principal, "project", 1L);
        when(mapper.selectProjects(List.of(1L), List.of(10L), null, 1)).thenReturn(List.of(projectRow(1L, 10L)));

        ExternalApiProject result = service.getProject(principal, scope, publicId);

        assertEquals(publicId, result.publicProjectId());
        assertNull(service.getProject(principal, scope, "not-a-valid-public-id"));
    }

    @Test
    void malformedNumericScopeFailsClosedBeforeQuery() {
        ExternalApiEffectiveScope malformed = new ExternalApiEffectiveScope("tenant-a", 9L, Map.of(
                "tenantIds", Set.of("tenant-a"), "legalEntityIds", Set.of("9"),
                "projectIds", Set.of("not-an-internal-id")));

        assertThrows(ExternalApiSecurityException.class,
                () -> service.listProjects(principal, malformed, 50, null));
    }

    private ExternalApiReadRow projectRow(long id, long customerId) {
        ExternalApiReadRow row = new ExternalApiReadRow();
        row.setId(id);
        row.setStatus("ACTIVE");
        row.setStartDate(LocalDate.of(2026, 1, 1));
        row.setEndDate(LocalDate.of(2026, 12, 31));
        row.setCustomerId(customerId);
        return row;
    }

    private IntegrationHubExternalApiProperties properties() {
        IntegrationHubExternalApiProperties value = new IntegrationHubExternalApiProperties();
        value.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        return value;
    }
}
