package com.ses.service.integrationhub;

import com.ses.mapper.ExternalApiReadSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalApiReadSnapshotPurgeServiceTest {
    private final ExternalApiReadSnapshotMapper mapper = mock(ExternalApiReadSnapshotMapper.class);
    private ExternalApiReadSnapshotPurgeService service;
    private final Instant now = Instant.parse("2026-08-30T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new ExternalApiReadSnapshotPurgeService(mapper);
    }

    @Test
    void oneRunDeletesOnlyFiniteHeaderBatchAndRerunIsSafe() {
        when(mapper.selectExpiredSnapshotIds(now, 2)).thenReturn(List.of("expired-a", "expired-b"));
        when(mapper.deleteSnapshotsById(List.of("expired-a", "expired-b"))).thenReturn(2);

        assertEquals(2, service.purgeExpiredBatch(now, 2));
        verify(mapper).deleteSnapshotsById(List.of("expired-a", "expired-b"));

        when(mapper.selectExpiredSnapshotIds(now, 2)).thenReturn(List.of());
        assertEquals(0, service.purgeExpiredBatch(now, 2));
        verify(mapper, never()).deleteSnapshotsById(List.of());
    }

    @Test
    void requestedBatchIsClampedToSafeMaximum() {
        when(mapper.selectExpiredSnapshotIds(now, ExternalApiReadSnapshotPurgeService.MAX_BATCH_SIZE))
                .thenReturn(List.of());

        assertEquals(0, service.purgeExpiredBatch(now, 10_000));
        verify(mapper).selectExpiredSnapshotIds(now, ExternalApiReadSnapshotPurgeService.MAX_BATCH_SIZE);
    }

    @Test
    void partialDeleteIsRejectedSoTransactionRollsBackAndSchedulerCanRetry() {
        when(mapper.selectExpiredSnapshotIds(now, 2)).thenReturn(List.of("expired-a", "expired-b"));
        when(mapper.deleteSnapshotsById(List.of("expired-a", "expired-b"))).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> service.purgeExpiredBatch(now, 2));
        verify(mapper).deleteSnapshotsById(anyList());

        when(mapper.deleteSnapshotsById(List.of("expired-a", "expired-b"))).thenReturn(2);
        assertEquals(2, service.purgeExpiredBatch(now, 2));
    }

    @Test
    void invalidRequestDoesNotTouchDatabase() {
        assertThrows(IllegalArgumentException.class, () -> service.purgeExpiredBatch(null, 1));
        assertThrows(IllegalArgumentException.class, () -> service.purgeExpiredBatch(now, 0));
        verify(mapper, never()).selectExpiredSnapshotIds(org.mockito.ArgumentMatchers.any(), anyInt());
    }
}
