package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiIdempotencyRecord;
import com.ses.mapper.ApiIdempotencyRecordMapper;
import com.ses.service.integrationhub.ApiIdempotencyService;
import com.ses.service.integrationhub.IdempotencyPayloadConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-05 F1: same digest reuse / different digest conflict。 */
class ApiIdempotencyServiceTest {
    private ApiIdempotencyRecordMapper mapper;
    private ApiIdempotencyServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 30, 0, 0);
    private final String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        mapper = mock(ApiIdempotencyRecordMapper.class);
        service = new ApiIdempotencyServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void 同一payloadは既存recordを再利用し異なるpayloadは安全な409相当へ収束する() {
        when(mapper.selectByNaturalKeyForUpdate("client-a", "/route", "key-1"))
                .thenReturn(null)
                .thenReturn(ApiIdempotencyRecord.builder().id(1L).version(0).requestDigest(digest)
                        .status("IN_PROGRESS").build());
        when(mapper.insert(any(ApiIdempotencyRecord.class))).thenAnswer(invocation -> {
            ApiIdempotencyRecord row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        });

        ApiIdempotencyService.Reservation first = service.reserve("client-a", "/route", "key-1", digest, now);
        assertFalse(first.reused());
        ApiIdempotencyService.Reservation second = service.reserve("client-a", "/route", "key-1", digest, now);
        assertTrue(second.reused());
        assertThrows(IdempotencyPayloadConflictException.class,
                () -> service.reserve("client-a", "/route", "key-1", digest.substring(0, 63) + "0", now));
    }

    @Test
    void successはsafeSnapshotだけを30日retentionへ接続する() {
        when(mapper.transitionSucceeded(1L, 0, digest, 200, "{\"status\":\"ok\"}", now, now.plusDays(30)))
                .thenReturn(1);
        assertTrue(service.completeSucceeded(1L, 0, digest, 200, "{\"status\":\"ok\"}", now));
        verify(mapper).transitionSucceeded(1L, 0, digest, 200, "{\"status\":\"ok\"}", now, now.plusDays(30));
    }
}
