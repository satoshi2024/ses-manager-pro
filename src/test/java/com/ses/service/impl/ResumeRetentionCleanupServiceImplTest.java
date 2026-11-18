package com.ses.service.impl;

import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import com.ses.service.FileStorageService;
import com.ses.service.document.ResumeIngestionLedgerRetentionService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF07 A1H-2 D4・D5: 履歴取込の原本・抽出本文の purge と BP 空き取込の抽出本文の消去が、回の初めの1回の gate の照会で
 * 保全 hold を尊重すること（production-bridge-phased-plan.md §3.4.1・§3.4.3、Demo 3・Demo 4 の経路部分）。帰属できる held な行
 * だけが残り、他者の行・どの本人にも帰属しない行は SUBJECT hold では消え、DATA_ELEMENT hold・UNAVAILABLE では止まる。
 * hold 機構を使っていない tenant は照会1回で従来どおり消し、本人導出を呼ばない。本人導出は resolver の mock（provider の
 * 和集合の規則は {@code HoldSubjectResolverPathTest} が実 DB で扱う）。
 */
class ResumeRetentionCleanupServiceImplTest {

    private static final String TENANT = "tenant-a";
    private static final LocalDateTime THRESHOLD = LocalDateTime.of(2026, 10, 1, 0, 0);

    private final ResumeIngestionMapper resumeMapper = mock(ResumeIngestionMapper.class);
    private final BpAvailabilityIngestionMapper bpMapper = mock(BpAvailabilityIngestionMapper.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private PrivacyHoldTestGuards.Stub hold;
    private ObjectProvider<ResumeIngestionLedgerRetentionService> ledger;
    private ResumeRetentionCleanupServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hold = PrivacyHoldTestGuards.stub();
        ledger = mock(ObjectProvider.class);
        service = new ResumeRetentionCleanupServiceImpl(resumeMapper, bpMapper, fileStorage,
                mock(TenantAwareBatchRunner.class), ledger, hold.guard);
        when(resumeMapper.selectExpiredOriginalsForTenant(TENANT, THRESHOLD)).thenReturn(List.of(
                resume(10L, "held-engineer.pdf"), resume(11L, "held-candidate.pdf"), resume(12L, "other.pdf"),
                resume(13L, "unconverted.pdf")));
        when(resumeMapper.purgeOriginalForTenant(anyLong(), eq(TENANT), anyString(), any())).thenReturn(1);
        when(bpMapper.selectExpiredWithTextForTenant(TENANT, THRESHOLD)).thenReturn(List.of(bp(20L), bp(21L)));
        when(bpMapper.clearExtractedTextForTenant(anyLong(), eq(TENANT))).thenReturn(1);
    }

    @Test
    void D4_D5_SUBJECT_holdの本人の取込だけが残り他者と帰属しない取込は消える() {
        hold.tenant(TENANT, PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7", "CANDIDATE:3", "BP_AVAILABILITY:5"),
                Set.of(), catalog()));
        // 変換済みの行は要員、未変換は候補者の hold で止まる（和集合）。13 はどの本人にも帰属しない
        when(hold.resolver.resumeIngestionSubjects(eq(TENANT), any()))
                .thenReturn(Map.of(10L, Set.of("ENGINEER:7"), 11L, Set.of("CANDIDATE:3")));
        when(hold.resolver.bpAvailabilityIngestionSubjects(eq(TENANT), any()))
                .thenReturn(Map.of(20L, Set.of("BP_AVAILABILITY:5")));

        service.cleanupTenant(TENANT, THRESHOLD);

        verify(resumeMapper, never()).purgeOriginalForTenant(eq(10L), any(), any(), any());
        verify(resumeMapper, never()).purgeOriginalForTenant(eq(11L), any(), any(), any());
        verify(resumeMapper).purgeOriginalForTenant(eq(12L), eq(TENANT), any(), any());
        verify(resumeMapper).purgeOriginalForTenant(eq(13L), eq(TENANT), any(), any());
        verify(fileStorage, never()).delete("held-engineer.pdf");
        verify(fileStorage, never()).delete("held-candidate.pdf");
        verify(fileStorage).delete("other.pdf");
        verify(fileStorage).delete("unconverted.pdf");
        verify(bpMapper, never()).clearExtractedTextForTenant(20L, TENANT);
        verify(bpMapper).clearExtractedTextForTenant(21L, TENANT);
        assertEquals(1, hold.gateCalls.get(), "D4 と D5 は1回の照会を共有する");
        assertEquals(2.0, hold.skipped(PrivacyHoldPath.D4, PrivacyHoldGate.Verdict.HELD));
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D5, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D4_抽出本文のDATA_ELEMENT_holdは履歴取込を全て止めBP取込は消す() {
        hold.tenant(TENANT, PrivacyHoldTestGuards.verified(Set.of(), Set.of("RESUME_INGESTION_TEXT"), catalog()));

        service.cleanupTenant(TENANT, THRESHOLD);

        verify(resumeMapper, never()).purgeOriginalForTenant(anyLong(), any(), any(), any());
        verify(fileStorage, never()).delete(anyString());
        verify(bpMapper).clearExtractedTextForTenant(20L, TENANT);
        verify(bpMapper).clearExtractedTextForTenant(21L, TENANT);
        verifyNoInteractions(hold.resolver);
        assertEquals(4.0, hold.skipped(PrivacyHoldPath.D4, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D4_D5_gateがUNAVAILABLEなら履歴取込もBP取込も消さない() {
        hold.tenant(TENANT, PrivacyHoldTestGuards.unavailable());

        service.cleanupTenant(TENANT, THRESHOLD);

        verify(resumeMapper, never()).purgeOriginalForTenant(anyLong(), any(), any(), any());
        verify(fileStorage, never()).delete(anyString());
        verify(bpMapper, never()).clearExtractedTextForTenant(anyLong(), any());
        assertEquals(4.0, hold.skipped(PrivacyHoldPath.D4, PrivacyHoldGate.Verdict.UNAVAILABLE));
        assertEquals(2.0, hold.skipped(PrivacyHoldPath.D5, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    @Test
    void D4_本人を導出できなければこの回の履歴取込を消さない() {
        hold.tenant(TENANT, PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), catalog()));
        when(hold.resolver.resumeIngestionSubjects(eq(TENANT), any())).thenThrow(new IllegalStateException("provider"));

        service.cleanupTenant(TENANT, THRESHOLD);

        verify(resumeMapper, never()).purgeOriginalForTenant(anyLong(), any(), any(), any());
        assertEquals(4.0, hold.skipped(PrivacyHoldPath.D4, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    @Test
    void D4_D5_hold機構を使っていないtenantは照会1回だけで従来どおり消し本人導出を呼ばない() {
        service.cleanupTenant(TENANT, THRESHOLD);

        for (long id = 10L; id <= 13L; id++) {
            verify(resumeMapper).purgeOriginalForTenant(eq(id), eq(TENANT), any(), any());
        }
        verify(bpMapper).clearExtractedTextForTenant(20L, TENANT);
        verify(bpMapper).clearExtractedTextForTenant(21L, TENANT);
        assertEquals(1, hold.gateCalls.get());
        verifyNoInteractions(hold.resolver);
    }

    @Test
    void 副本の調整は既定で原本のpurgeの後に呼ばれる() {
        ResumeIngestionLedgerRetentionService ledgerRetention = mock(ResumeIngestionLedgerRetentionService.class);
        when(ledger.getIfAvailable()).thenReturn(ledgerRetention);
        when(ledgerRetention.alignPurgedCopies(eq(TENANT), any()))
                .thenReturn(new ResumeIngestionLedgerRetentionService.Result(4, 0, 0));

        service.cleanupTenant(TENANT, THRESHOLD);

        verify(ledgerRetention).alignPurgedCopies(eq(TENANT), any(LocalDate.class));
        verify(resumeMapper).purgeOriginalForTenant(eq(10L), eq(TENANT), any(), any());
    }

    @Test
    void 副本の調整の停止口がfalseなら副本だけを止め原本とBP取込のpurgeは続ける() {
        ReflectionTestUtils.setField(service, "ledgerCopyAlignmentEnabled", false);

        service.cleanupTenant(TENANT, THRESHOLD);

        for (long id = 10L; id <= 13L; id++) {
            verify(resumeMapper).purgeOriginalForTenant(eq(id), eq(TENANT), any(), any());
        }
        verify(bpMapper).clearExtractedTextForTenant(20L, TENANT);
        verify(bpMapper).clearExtractedTextForTenant(21L, TENANT);
        // 副本の service を取得もしない（DocumentRetentionJob の廃棄候補につながる保存期限の変更が起きない）
        verifyNoInteractions(ledger);
    }

    private static Map<String, SubjectAttribution> catalog() {
        return Map.of("RESUME_INGESTION_ORIGINAL", SubjectAttribution.ATTRIBUTABLE,
                "RESUME_INGESTION_TEXT", SubjectAttribution.ATTRIBUTABLE,
                "BP_AVAILABILITY_INGESTION_TEXT", SubjectAttribution.ATTRIBUTABLE);
    }

    private static ResumeIngestion resume(long id, String storedName) {
        ResumeIngestion job = new ResumeIngestion();
        job.setId(id);
        job.setStoredFileName(storedName);
        job.setStatus("確定済");
        job.setVersion(1);
        return job;
    }

    private static BpAvailabilityIngestion bp(long id) {
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setId(id);
        return job;
    }
}
