package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiUsageBucket;
import com.ses.mapper.ApiUsageBucketMapper;
import com.ses.service.integrationhub.ApiUsageBucketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-05 F1: approved subject keyとminute/day/burstの同一predicate。 */
class ApiUsageBucketServiceTest {
    private ApiUsageBucketMapper mapper;
    private ApiUsageBucketServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 30, 0, 0, 30);
    private static final String ROUTE = "/external-api/v1/projects";
    private static final String INBOUND_ROUTE = "/external-api/v1/webhooks/{provider}";

    @BeforeEach
    void setUp() {
        mapper = mock(ApiUsageBucketMapper.class);
        service = new ApiUsageBucketServiceImpl(java.time.Clock.systemUTC(), mapper);
    }

    @Test
    void 初回consumeはapproved四列keyで20から一つだけ消費する() {
        when(mapper.selectSubjectForUpdate("client-a", "engineer.read", "tenant-a", ROUTE))
                .thenReturn(null);
        when(mapper.insert(any(ApiUsageBucket.class))).thenAnswer(invocation -> {
            ApiUsageBucket row = invocation.getArgument(0);
            assertEquals("client-a", row.getClientId());
            assertEquals("engineer.read", row.getScopeCode());
            assertEquals("tenant-a", row.getTenantId());
            assertEquals(ROUTE, row.getRouteTemplate());
            assertEquals(1, row.getMinuteCount());
            assertEquals(1, row.getDayCount());
            assertEquals(19, row.getBurstTokens());
            return 1;
        });

        ApiUsageBucketService.RateDecision result = service.consumeAt(
                "client-a", "engineer.read", "tenant-a", ROUTE, now);

        assertTrue(result.allowed());
        verify(mapper).insert(any(ApiUsageBucket.class));
    }

    @Test
    void burst不足はminuteとdayを変更せず次tokenまで拒否する() {
        ApiUsageBucket bucket = bucket(60, 100, 0, now.minusSeconds(1));
        when(mapper.selectSubjectForUpdate("client-a", "scope", "tenant-a", ROUTE)).thenReturn(bucket);

        ApiUsageBucketService.RateDecision result = service.consumeAt("client-a", "scope", "tenant-a", ROUTE, now);

        assertFalse(result.allowed());
        assertTrue(result.exhaustedLimits().contains("MINUTE"));
        assertTrue(result.exhaustedLimits().contains("BURST"));
        assertEquals(30, result.retryAfterSeconds());
        verify(mapper, never()).updateCounters(any(ApiUsageBucket.class));
    }

    @Test
    void 三秒境界では一tokenだけrefillしてconsumeする() {
        ApiUsageBucket bucket = bucket(0, 0, 0, now.minusSeconds(3));
        when(mapper.selectSubjectForUpdate("client-a", "scope", "tenant-a", ROUTE)).thenReturn(bucket);
        when(mapper.updateCounters(any(ApiUsageBucket.class))).thenAnswer(invocation -> {
            ApiUsageBucket updated = invocation.getArgument(0);
            assertEquals(0, updated.getBurstTokens());
            assertEquals(1, updated.getMinuteCount());
            assertEquals(1, updated.getDayCount());
            return 1;
        });

        assertTrue(service.consumeAt("client-a", "scope", "tenant-a", ROUTE, now).allowed());
        verify(mapper).updateCounters(any(ApiUsageBucket.class));
    }

    @Test
    void clockRollbackではwindowとrefillを後戻りさせない() {
        LocalDateTime stored = now.plusMinutes(1);
        ApiUsageBucket bucket = bucket(59, 99, 10, stored);
        bucket.setBurstLastRefillAt(stored);
        when(mapper.selectSubjectForUpdate("client-a", "scope", "tenant-a", ROUTE)).thenReturn(bucket);
        when(mapper.updateCounters(any(ApiUsageBucket.class))).thenReturn(1);

        assertTrue(service.consumeAt("client-a", "scope", "tenant-a", ROUTE, now).allowed());
        assertEquals(now.withSecond(0).withNano(0), bucket.getMinuteWindowStart());
        assertEquals(60, bucket.getMinuteCount());
        assertEquals(stored, bucket.getBurstLastRefillAt());
    }

    @Test
    void rawResourcePathはquotaSubjectKeyとして受け付けない() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.consumeAt("client-a", "scope", "tenant-a",
                        "/external-api/v1/projects/123", now));
    }

    @Test
    void inboundWebhookはrouteCatalogのcanonicalTemplateでquotaを消費する() {
        when(mapper.selectSubjectForUpdate("client-a", "integration.webhook.receive", "tenant-a", INBOUND_ROUTE))
                .thenReturn(null);
        when(mapper.insert(any(ApiUsageBucket.class))).thenReturn(1);

        ApiUsageBucketService.RateDecision result = service.consumeAt(
                "client-a", "integration.webhook.receive", "tenant-a", INBOUND_ROUTE, now);

        assertTrue(result.allowed());
        verify(mapper).insert(any(ApiUsageBucket.class));
    }

    private ApiUsageBucket bucket(int minute, int day, int burst, LocalDateTime refillAt) {
        return ApiUsageBucket.builder()
                .id(1L).clientId("client-a").scopeCode("scope").tenantId("tenant-a").routeTemplate(ROUTE)
                .minuteWindowStart(now.withSecond(0).withNano(0)).minuteCount(minute)
                .dayWindowStart(now.toLocalDate().atStartOfDay()).dayCount(day)
                .burstTokens(burst).burstLastRefillAt(refillAt).version(0).updatedAt(now).build();
    }
}
