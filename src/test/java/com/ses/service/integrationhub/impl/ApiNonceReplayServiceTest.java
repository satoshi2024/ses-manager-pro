package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiNonceReplay;
import com.ses.mapper.ApiNonceReplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** NF-05 F1: nonce hash only、rotation-spanning unique、future timestamp TTL。 */
class ApiNonceReplayServiceTest {
    private ApiNonceReplayMapper mapper;
    private ApiNonceReplayServiceImpl service;
    private final LocalDateTime accepted = LocalDateTime.of(2026, 8, 30, 12, 0);

    @BeforeEach
    void setUp() {
        mapper = mock(ApiNonceReplayMapper.class);
        service = new ApiNonceReplayServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void futureSignedTimestampを含むmax基準で5分TTLを設定しrawNonceを保存しない() {
        byte[] raw = "nonce-value-which-is-not-persisted".getBytes(StandardCharsets.UTF_8);
        LocalDateTime signed = accepted.plusMinutes(4);
        when(mapper.insert(any(ApiNonceReplay.class))).thenAnswer(invocation -> {
            ApiNonceReplay row = invocation.getArgument(0);
            assertEquals(signed.plusMinutes(5), row.getExpiresAt());
            assertNotEquals(new String(raw, StandardCharsets.UTF_8), row.getNonceHash());
            assertEquals(64, row.getNonceHash().length());
            return 1;
        });

        assertTrue(service.accept("client-a", 2, raw, signed, accepted));
    }

    @Test
    void unique競合はfalseへ収束し再受付しない() {
        when(mapper.insert(any(ApiNonceReplay.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertFalse(service.accept("client-a", 1, "nonce-value-which-is-not-persisted".getBytes(StandardCharsets.UTF_8),
                accepted, accepted));
    }
}
