package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.ApiClientMapper;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubWebhookDeliveryBindingValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-05 F1: dedicated delivery ledger、allow-list snapshot、claim/CAS引数。 */
class ApiDeliveryServiceTest {
    private ApiDeliveryMapper mapper;
    private ApiClientMapper clientMapper;
    private ExternalApiPublicIdCodec publicIdCodec;
    private ApiDeliveryServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);
    private final String payloadHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final String providerKey = IntegrationHubDigest.sha256Hex("event-1|7|1");

    @BeforeEach
    void setUp() {
        mapper = mock(ApiDeliveryMapper.class);
        clientMapper = mock(ApiClientMapper.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        publicIdCodec = new ExternalApiPublicIdCodec(properties);
        when(clientMapper.selectByClientIdForUpdate("client-a")).thenReturn(client());
        service = new ApiDeliveryServiceImpl(mapper,
                new IntegrationHubWebhookDeliveryBindingValidator(clientMapper, publicIdCodec));
    }

    @Test
    void enqueueはPENDINGの専用deliveryRowを一世代一件で作る() {
        when(mapper.selectByEventGeneration("event-1", 7L, 1)).thenReturn(null);
        when(mapper.insert(any(ApiDelivery.class))).thenAnswer(invocation -> {
            ApiDelivery row = invocation.getArgument(0);
            assertEquals("PENDING", row.getStatus());
            assertEquals(8, row.getMaxAttempts());
            assertEquals("client-a", row.getClientId());
            assertEquals("tenant-a", row.getTenantId());
            assertEquals("project", row.getPrimaryResourceType());
            assertEquals(1L, row.getPrimaryResourceId());
            assertEquals(IntegrationHubDigest.sha256Hex("event-1|7|1"), row.getProviderIdempotencyKey());
            assertEquals(snapshotJson(), row.getExternalDtoSnapshot());
            return 1;
        });
        ExternalDtoSnapshot snapshot = ExternalDtoSnapshot.of(snapshotJson());

        ApiDelivery row = service.enqueue("event-1", 7L, 1, "client-a", "scope", "tenant-a",
                "project", 1L, "event.type", "v1", "correlation-000001", snapshot, now);

        assertEquals("PENDING", row.getStatus());
        verify(mapper).insert(any(ApiDelivery.class));
    }

    @Test
    void externalDTOへsecretやraw_bodyを渡せない() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"password\":\"do-not-store\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"raw_body\":\"do-not-store\"}"));
    }

    @Test
    void claimは短いDBtransactionでleaseをCASし外部callを行わない() {
        ApiDelivery pending = ApiDelivery.builder().id(7L).version(2).status("PENDING")
                .nextAttemptAt(now.minusSeconds(1)).build();
        ApiDelivery claimed = ApiDelivery.builder().id(7L).version(3).status("CLAIMED")
                .leaseToken("lease-1").build();
        when(mapper.selectForUpdate(7L)).thenReturn(pending);
        when(mapper.claim(7L, 2, "lease-1", now.plusMinutes(1), now)).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(claimed);

        ApiDelivery result = service.claim(7L, "lease-1", now, now.plusMinutes(1));

        assertTrue(result != null && "CLAIMED".equals(result.getStatus()));
        verify(mapper).claim(7L, 2, "lease-1", now.plusMinutes(1), now);
    }

    @Test
    void resultCASはlease世代とprovider冪等keyとpayloadHashを全て照合する() {
        when(mapper.transitionSucceeded(7L, 3, 1, "lease-1", providerKey, payloadHash,
                "provider-request-1", now, now.plusDays(30))).thenReturn(1);

        assertTrue(service.markSucceeded(7L, 3, 1, "lease-1", providerKey, payloadHash,
                "provider-request-1", now));
        verify(mapper).transitionSucceeded(7L, 3, 1, "lease-1", providerKey, payloadHash,
                "provider-request-1", now, now.plusDays(30));
    }

    @Test
    void 外部DTOは構造化allowList外のfieldとnestedfieldを拒否する() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"password\":\"do-not-store\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"payload\":{\"internalDbId\":1}}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"payload\":\"{\\\"password\\\":\\\"secret\\\"}\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"canonicalPayload\":\"raw-provider-body-with-PII\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"payload\":{\"status\":\"{\\\"password\\\":\\\"secret\\\"}\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"canonicalPayload\":{\"resultCode\":\"raw-provider-body-with-PII\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDtoSnapshot.of("{\"payload\":{\"publicProjectId\":\"project-1\",\"startDate\":\"not-a-date\"}}"));
        assertDoesNotThrow(() -> ExternalDtoSnapshot.of(
                "{\"payload\":{\"publicProjectId\":\"project-1\",\"status\":\"ACTIVE\",\"startDate\":\"2026-08-30\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("event-1", 7L, 1, "client-a", "scope", "tenant-a",
                        "project", 1L, "event.type", "v1", "correlation-000001",
                        ExternalDtoSnapshot.of("{\"code\":\"not-an-outbound-field\"}"), now));
    }

    @Test
    void enqueueはprimaryのtypeまたはIDとsnapshotのopaqueIDが不一致なら保存しない() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("event-1", 7L, 1, "client-a", "scope", "tenant-a",
                        "project", 2L, "event.type", "v1", "correlation-000001",
                        ExternalDtoSnapshot.of(snapshotJson()), now));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("event-1", 7L, 1, "client-a", "scope", "tenant-a",
                        "contract-status", 1L, "event.type", "v1", "correlation-000001",
                        ExternalDtoSnapshot.of(snapshotJson()), now));
        verify(mapper, org.mockito.Mockito.never()).insert(any(ApiDelivery.class));
    }

    @Test
    void 同時enqueueのDuplicateKey収束もpayloadHashとprimaryTypeIdを同時比較する() {
        String eventId = "event-duplicate";
        ExternalDtoSnapshot snapshot = ExternalDtoSnapshot.of(snapshotJson(eventId));
        ApiDelivery concurrent = ApiDelivery.builder().payloadHash(snapshot.payloadHash())
                .primaryResourceType("project").primaryResourceId(2L).build();
        when(mapper.selectByEventGeneration(eventId, 7L, 1)).thenReturn(null, concurrent);
        when(mapper.insert(any(ApiDelivery.class))).thenThrow(new DuplicateKeyException("race"));

        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue(eventId, 7L, 1, "client-a", "scope", "tenant-a",
                        "project", 1L, "event.type", "v1", "correlation-000001", snapshot, now));
    }

    private ApiClient client() {
        return ApiClient.builder().id(11L).clientId("client-a").tenantId("tenant-a").legalEntityId(9L)
                .dataScopeJson("{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"]}")
                .clientTier("STANDARD").status("ACTIVE").build();
    }

    private String snapshotJson() {
        return snapshotJson("event-1");
    }

    private String snapshotJson(String eventId) {
        String publicProjectId = publicIdCodec.encode(new com.ses.config.integrationhub.ExternalApiPrincipal(
                "client-a", 11L, "tenant-a", 9L,
                "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"]}",
                1, "delivery-binding", "STANDARD"), "project", 1L);
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"event.type\",\"schemaVersion\":\"v1\",\"createdAt\":\"2026-08-30T12:00:00Z\",\"publicResourceId\":\"" + publicProjectId + "\",\"correlationId\":\"correlation-000001\",\"payload\":{\"publicProjectId\":\"" + publicProjectId + "\",\"status\":\"ACTIVE\"}}";
    }
}
