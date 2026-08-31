package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.ExternalApiCursorCodec;
import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.dto.integrationhub.ExternalApiProject;
import com.ses.dto.integrationhub.ExternalApiInvoiceStatus;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.dto.integrationhub.ExternalApiSnapshotItem;
import com.ses.mapper.ExternalApiReadMapper;
import com.ses.mapper.ExternalApiReadSnapshotMapper;
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
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalApiReadServiceTest {
    private final ExternalApiReadMapper mapper = mock(ExternalApiReadMapper.class);
    private final ExternalApiReadSnapshotMapper snapshotMapper = mock(ExternalApiReadSnapshotMapper.class);
    private final ExternalApiPrincipal principal = new ExternalApiPrincipal(
            "client-a", 7L, "tenant-a", 9L, "{\"projectIds\":[\"1\",\"2\",\"3\"]}", 1, "key-1", "STANDARD");
    private final ExternalApiEffectiveScope scope = new ExternalApiEffectiveScope("tenant-a", 9L, Map.of(
            "tenantIds", Set.of("tenant-a"), "legalEntityIds", Set.of("9"),
            "projectIds", Set.of("1", "2", "3"), "customerIds", Set.of("10")));
    private ExternalApiReadService service;

    @BeforeEach
    void setUp() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        properties.getPublicApi().setCursorTtlSeconds(300);
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
        service = new ExternalApiReadService(mapper, snapshotMapper, new ExternalApiPublicIdCodec(properties),
                new ExternalApiCursorCodec(properties), new ObjectMapper().findAndRegisterModules(), clock);
    }

    @Test
    void listUsesOnlyEffectiveScopeAndReturnsAllowListDtoWithOpaqueCursor() {
        when(mapper.selectProjects(List.of(1L, 2L, 3L), List.of(10L), null, 513)).thenReturn(List.of(
                projectRow(2L, 10L), projectRow(1L, 10L), projectRow(3L, 10L)));

        var response = service.listProjects(principal, scope, 2, null);

        assertEquals(2, response.items().size());
        assertTrue(response.hasMore());
        assertTrue(response.nextCursor().startsWith("v1."));
        ExternalApiProject first = response.items().get(0);
        assertEquals("ACTIVE", first.status());
        verify(mapper).selectProjects(List.of(1L, 2L, 3L), List.of(10L), null, 513);
        verify(snapshotMapper, never()).selectExpiredSnapshotIds(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(snapshotMapper, never()).deleteSnapshotsById(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void fractionalClockIsNormalizedToSameSecondAcrossSnapshotPages() {
        IntegrationHubExternalApiProperties properties = properties();
        properties.getPublicApi().setCursorTtlSeconds(300);
        Clock fractionalClock = Clock.fixed(Instant.parse("2026-08-30T00:00:00.123456Z"), ZoneOffset.UTC);
        ExternalApiReadService fractionalService = new ExternalApiReadService(mapper, snapshotMapper,
                new ExternalApiPublicIdCodec(properties), new ExternalApiCursorCodec(properties),
                new ObjectMapper().findAndRegisterModules(), fractionalClock);
        when(mapper.selectProjects(List.of(1L, 2L, 3L), List.of(10L), null, 513)).thenReturn(List.of(
                projectRow(2L, 10L), projectRow(1L, 10L)));
        String cursor = fractionalService.listProjects(principal, scope, 1, null).nextCursor();
        when(snapshotMapper.selectItemsAfter(anyString(), eq(2L), eq(2))).thenReturn(List.of(
                new ExternalApiSnapshotItem(1L,
                        "{\"publicProjectId\":\"public-project-1\",\"status\":\"ACTIVE\","
                                + "\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\","
                                + "\"publicCustomerId\":\"public-customer-10\"}")));

        var second = fractionalService.listProjects(principal, scope, 1, cursor);

        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), second.asOf());
    }

    @Test
    void cursorBindsScopeAndUsesLastInternalIdOnlyInsideMapper() {
        when(mapper.selectProjects(List.of(1L, 2L, 3L), List.of(10L), null, 513)).thenReturn(List.of(
                projectRow(2L, 10L), projectRow(1L, 10L)));
        String cursor = service.listProjects(principal, scope, 1, null).nextCursor();
        when(snapshotMapper.selectItemsAfter(anyString(), eq(2L), eq(2))).thenReturn(List.of(
                new ExternalApiSnapshotItem(1L,
                        "{\"publicProjectId\":\"public-project-1\",\"status\":\"ACTIVE\","
                                + "\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\","
                                + "\"publicCustomerId\":\"public-customer-10\"}")));

        var response = service.listProjects(principal, scope, 1, cursor);

        assertEquals(1, response.items().size());
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), response.asOf());
        assertEquals("public-project-1", response.items().get(0).publicProjectId());
        org.mockito.Mockito.verify(snapshotMapper).selectItemsAfter(anyString(), eq(2L), eq(2));
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

    @Test
    void invoiceWithMultipleContractsNeverPretendsToHaveOnePublicContract() {
        ExternalApiEffectiveScope invoiceScope = new ExternalApiEffectiveScope("tenant-a", 9L, Map.of(
                "tenantIds", Set.of("tenant-a"), "legalEntityIds", Set.of("9"),
                "invoiceIds", Set.of("3"), "customerIds", Set.of("10"),
                "contractIds", Set.of("20", "21")));
        ExternalApiReadRow row = new ExternalApiReadRow();
        row.setId(3L);
        row.setStatus("未送付");
        row.setContractId(20L);
        row.setContractCount(2L);
        when(mapper.selectInvoices(List.of(3L), List.of(20L, 21L), List.of(10L), null, 1))
                .thenReturn(List.of(row));

        String publicId = new ExternalApiPublicIdCodec(properties()).encode(principal, "invoice-status", 3L);
        ExternalApiInvoiceStatus result = service.getInvoiceStatus(principal, invoiceScope, publicId);

        assertNull(result.publicContractId());
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
