package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.IntegrationHubDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-05 F1: dedicated delivery ledger、allow-list snapshot、claim/CAS引数。 */
class ApiDeliveryServiceTest {
    private ApiDeliveryMapper mapper;
    private ApiDeliveryServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);

    @BeforeEach
    void setUp() {
        mapper = mock(ApiDeliveryMapper.class);
        service = new ApiDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
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
            assertEquals(IntegrationHubDigest.sha256Hex("event-1|7|1"), row.getProviderIdempotencyKey());
            assertEquals("{\"status\":\"ok\"}", row.getExternalDtoSnapshot());
            return 1;
        });
        ExternalDtoSnapshot snapshot = ExternalDtoSnapshot.of("{\"status\":\"ok\"}");

        ApiDelivery row = service.enqueue("event-1", 7L, 1, "client-a", "scope", "tenant-a",
                "event.type", "v1", "corr-1", snapshot, now);

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
}
