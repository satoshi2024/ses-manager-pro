package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiNonceReplay;
import com.ses.mapper.ApiNonceReplayMapper;
import com.ses.service.integrationhub.ApiNonceReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

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
        service = new ApiNonceReplayServiceImpl(mapper);
    }

    @Test
    void futureSignedTimestampを含むmax基準で受付窓より長いTTLを設定しrawNonceを保存しない() {
        byte[] raw = "nonce-value-which-is-not-persisted".getBytes(StandardCharsets.UTF_8);
        LocalDateTime signed = accepted.plusMinutes(4);
        when(mapper.insert(any(ApiNonceReplay.class))).thenAnswer(invocation -> {
            ApiNonceReplay row = invocation.getArgument(0);
            assertEquals(signed.plusSeconds(ApiNonceReplayService.NONCE_RETENTION_SECONDS), row.getExpiresAt());
            assertNotEquals(new String(raw, StandardCharsets.UTF_8), row.getNonceHash());
            assertEquals(64, row.getNonceHash().length());
            return 1;
        });

        assertTrue(service.accept("client-a", 2, raw, signed, accepted));
    }

    /**
     * N5-P3-1: 受付窓の最終瞬間（署名時刻 + 許容差 + 秒切捨て分）に同じnonceが再送されても、
     * その時点のpurge（expires_at <= now）では行が残っていること。node間の時計差も許容差の分だけ吸収する。
     */
    @Test
    void nonce保存期限は受付窓の境界と重ならず秒丸めとnode間時計差の余裕を持つ() {
        // 最悪ケース: clientの時計が許容差いっぱい進んでおり、基準が署名時刻になる。
        LocalDateTime signed = accepted.plusSeconds(ApiNonceReplayService.SIGNED_TIMESTAMP_SKEW_SECONDS);
        java.util.concurrent.atomic.AtomicReference<LocalDateTime> expires = new java.util.concurrent.atomic.AtomicReference<>();
        when(mapper.insert(any(ApiNonceReplay.class))).thenAnswer(invocation -> {
            expires.set(((ApiNonceReplay) invocation.getArgument(0)).getExpiresAt());
            return 1;
        });

        assertTrue(service.accept("client-a", 1, "nonce-value-which-is-not-persisted".getBytes(StandardCharsets.UTF_8),
                signed, accepted));

        // 同じ署名時刻が受理されうる最後の瞬間（秒切捨てで+1秒未満まで）。
        LocalDateTime lastAcceptable = signed.plusSeconds(ApiNonceReplayService.SIGNED_TIMESTAMP_SKEW_SECONDS)
                .plusNanos(999_999_999L);
        assertTrue(expires.get().isAfter(lastAcceptable), "受付窓の終端より後まで保存する");
        // purge nodeの時計が受付nodeより許容差分進んでいても、終端時点の再送をまだ拒否できる。
        assertTrue(expires.get().isAfter(lastAcceptable.plusSeconds(
                ApiNonceReplayService.SIGNED_TIMESTAMP_SKEW_SECONDS)), "node間時計差の余裕");
        assertEquals(2 * ApiNonceReplayService.SIGNED_TIMESTAMP_SKEW_SECONDS + 1,
                ApiNonceReplayService.NONCE_RETENTION_SECONDS);
    }

    @Test
    void unique競合はfalseへ収束し再受付しない() {
        when(mapper.insert(any(ApiNonceReplay.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertFalse(service.accept("client-a", 1, "nonce-value-which-is-not-persisted".getBytes(StandardCharsets.UTF_8),
                accepted, accepted));
    }
}
