package com.ses.migration;

import com.ses.entity.integrationhub.ApiUsageBucket;
import com.ses.mapper.ApiUsageBucketMapper;
import com.ses.service.integrationhub.ApiUsageBucketService;
import com.ses.service.integrationhub.impl.ApiUsageBucketServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M: contract SLAのrate exact boundary（minute=60, burst=20）をH2実mapper経路で固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHubMPerformanceBoundaryTest {
    private static final String CLIENT_ID = "m-perf-client";
    private static final String SCOPE = "project.read";
    private static final String TENANT = "tenant-m-perf";
    private static final String ROUTE = "/external-api/v1/projects";
    private static final LocalDateTime WINDOW = LocalDateTime.of(2026, 8, 31, 10, 0, 15);

    @Autowired
    private ApiUsageBucketMapper mapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiUsageBucketService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM t_api_usage_bucket WHERE client_id = ?", CLIENT_ID);
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T10:00:15Z"), ZoneOffset.UTC);
        service = new ApiUsageBucketServiceImpl(clock, mapper);
    }

    @Test
    void minute59件目は許可し60件目でRetryAfter付き拒否する() {
        ApiUsageBucket bucket = new ApiUsageBucket();
        bucket.setClientId(CLIENT_ID);
        bucket.setScopeCode(SCOPE);
        bucket.setTenantId(TENANT);
        bucket.setRouteTemplate(ROUTE);
        bucket.setMinuteWindowStart(WINDOW.withSecond(0).withNano(0));
        bucket.setDayWindowStart(WINDOW.toLocalDate().atStartOfDay());
        bucket.setMinuteCount(59);
        bucket.setDayCount(59);
        bucket.setBurstTokens(20);
        bucket.setBurstLastRefillAt(WINDOW);
        bucket.setVersion(0);
        mapper.insert(bucket);

        ApiUsageBucketService.RateDecision allowed = service.consumeAt(
                CLIENT_ID, SCOPE, TENANT, ROUTE, WINDOW);
        assertTrue(allowed.allowed());

        ApiUsageBucketService.RateDecision denied = service.consumeAt(
                CLIENT_ID, SCOPE, TENANT, ROUTE, WINDOW.plusSeconds(1));
        assertFalse(denied.allowed());
        assertTrue(denied.exhaustedLimits().contains("MINUTE"));
        assertTrue(denied.retryAfterSeconds() >= 44 && denied.retryAfterSeconds() <= 45);
    }

    @Test
    void burst20を超える同秒burstはrefill前に拒否する() {
        ApiUsageBucket bucket = new ApiUsageBucket();
        bucket.setClientId(CLIENT_ID);
        bucket.setScopeCode(SCOPE);
        bucket.setTenantId(TENANT);
        bucket.setRouteTemplate(ROUTE);
        bucket.setMinuteWindowStart(WINDOW.withSecond(0).withNano(0));
        bucket.setDayWindowStart(WINDOW.toLocalDate().atStartOfDay());
        bucket.setMinuteCount(0);
        bucket.setDayCount(0);
        bucket.setBurstTokens(0);
        bucket.setBurstLastRefillAt(WINDOW);
        bucket.setVersion(0);
        mapper.insert(bucket);

        ApiUsageBucketService.RateDecision denied = service.consumeAt(
                CLIENT_ID, SCOPE, TENANT, ROUTE, WINDOW.plusSeconds(1));
        assertFalse(denied.allowed());
        assertTrue(denied.exhaustedLimits().contains("BURST"));
    }
}
