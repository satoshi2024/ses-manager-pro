package com.ses.service.integrationhub;

import com.ses.mapper.ExternalApiReadSnapshotMapper;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.verifyNoInteractions;

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
        service = new ExternalApiReadSnapshotPurgeService(mapper, com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.noHoldsGuard());
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

    // ---- NF07 A1H-2 D9 ----

    @Test
    void D9_DATA_ELEMENT_holdかUNAVAILABLEならその回を消さず件数を数えSUBJECT_holdでは消す() {
        when(mapper.selectExpiredSnapshotIds(now, 2)).thenReturn(List.of("expired-a", "expired-b"));
        when(mapper.deleteSnapshotsById(List.of("expired-a", "expired-b"))).thenReturn(2);
        Map<String, SubjectAttribution> catalog = Map.of("EXTERNAL_API_READ_SNAPSHOT", SubjectAttribution.NOT_ATTRIBUTABLE);

        PrivacyHoldTestGuards.Stub elementHold = PrivacyHoldTestGuards.stub().tenant("tenant-a",
                PrivacyHoldTestGuards.verified(Set.of(), Set.of("EXTERNAL_API_READ_SNAPSHOT"), catalog));
        assertEquals(0, new ExternalApiReadSnapshotPurgeService(mapper, elementHold.guard).purgeExpiredBatch(now, 2));
        assertEquals(2.0, elementHold.skipped(PrivacyHoldPath.D9, PrivacyHoldGate.Verdict.HELD));

        PrivacyHoldTestGuards.Stub unavailable = PrivacyHoldTestGuards.stub().tenant("tenant-a",
                PrivacyHoldTestGuards.unavailable());
        assertEquals(0, new ExternalApiReadSnapshotPurgeService(mapper, unavailable.guard).purgeExpiredBatch(now, 2));
        verify(mapper, never()).deleteSnapshotsById(anyList());

        PrivacyHoldTestGuards.Stub subjectHold = PrivacyHoldTestGuards.stub().tenant("tenant-a",
                PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), catalog));
        assertEquals(2, new ExternalApiReadSnapshotPurgeService(mapper, subjectHold.guard).purgeExpiredBatch(now, 2));
        verifyNoInteractions(subjectHold.resolver);
    }

    @Test
    void D9_hold機構を使っていない環境は照会1回だけで従来どおり消す() {
        when(mapper.selectExpiredSnapshotIds(now, 2)).thenReturn(List.of("expired-a"));
        when(mapper.deleteSnapshotsById(List.of("expired-a"))).thenReturn(1);
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub();

        assertEquals(1, new ExternalApiReadSnapshotPurgeService(mapper, hold.guard).purgeExpiredBatch(now, 2));
        assertEquals(1, hold.gateCalls.get());
        verifyNoInteractions(hold.resolver);
    }
}
