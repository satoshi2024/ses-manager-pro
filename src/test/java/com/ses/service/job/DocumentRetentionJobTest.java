package com.ses.service.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.mapper.DocumentMapper;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.document.DocumentEligibilityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U9（NF01細粒度epoch計画 §4.3 DocumentRetentionJob・§6.2）: 保存期限jobがtenant contextを持たずに、各文書行自身の
 * tenantで {@link DocumentEligibilityService} を文書ごとに呼び、1文書の失敗で残りを止めないことを確かめる。
 */
class DocumentRetentionJobTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Document.class);
    }

    @Test
    void 保存期限jobは文書自身のtenantで文書ごとに廃棄候補を登録し失敗しても次へ進む() {
        AccountingTenantContextHolder.clear();
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired("tenant-a", 1L), expired("tenant-b", 2L), expired("tenant-c", 3L)));
        when(eligibility.registerRetentionDisposalCandidate(eq("tenant-a"), eq(1L), any()))
                .thenThrow(BusinessException.of(409, "error.authorization.epochUnavailable"));
        when(eligibility.registerRetentionDisposalCandidate(eq("tenant-b"), eq(2L), any())).thenReturn(true);
        when(eligibility.registerRetentionDisposalCandidate(eq("tenant-c"), eq(3L), any())).thenReturn(false);

        new DocumentRetentionJob(documentMapper, eligibility, com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.noHoldsGuard()).processExpiredDocuments();

        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-a"), eq(1L), any(LocalDate.class));
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-b"), eq(2L), any(LocalDate.class));
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-c"), eq(3L), any(LocalDate.class));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId(), "jobはtenant contextを設定しない");
    }

    // ---- NF07 A1H-2 D3: 保全 hold は tenant ごとに1回だけ照会し、当たる文書は申請を作らずに数える ----

    @Test
    void D3_SUBJECT_holdの本人の文書は申請を作らず他の文書と帰属しない文書は従来どおり登録する() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired("tenant-a", 1L, "CONTRACT"), expired("tenant-a", 2L, "CONTRACT"),
                expired("tenant-a", 3L, "IMPORT"), expired("tenant-b", 4L, "CONTRACT")));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-a", PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), Map.of(
                        "DOCUMENT:CONTRACT", SubjectAttribution.ATTRIBUTABLE,
                        "DOCUMENT:IMPORT", SubjectAttribution.NOT_ATTRIBUTABLE)));
        when(hold.resolver.documentSubjects(eq("tenant-a"), any())).thenReturn(Map.of(1L, Set.of("ENGINEER:7")));

        new DocumentRetentionJob(documentMapper, eligibility, hold.guard).processExpiredDocuments();

        verify(eligibility, never()).registerRetentionDisposalCandidate(eq("tenant-a"), eq(1L), any());
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-a"), eq(2L), any());
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-a"), eq(3L), any());
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-b"), eq(4L), any());
        assertEquals(2, hold.gateCalls.get(), "tenant ごとに1回");
        verify(hold.resolver, times(1)).documentSubjects(eq("tenant-a"), any());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D3, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D3_gateがUNAVAILABLEのtenantは全て飛ばし未使用のtenantは従来どおり登録する() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired("tenant-a", 1L, "CONTRACT"), expired("tenant-a", 2L, "IMPORT"),
                expired("tenant-b", 3L, "CONTRACT")));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-a", PrivacyHoldTestGuards.unavailable());

        new DocumentRetentionJob(documentMapper, eligibility, hold.guard).processExpiredDocuments();

        verify(eligibility, never()).registerRetentionDisposalCandidate(eq("tenant-a"), anyLong(), any());
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-b"), eq(3L), any());
        verifyNoInteractions(hold.resolver);
        assertEquals(2.0, hold.skipped(PrivacyHoldPath.D3, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    @Test
    void D3_DATA_ELEMENT_holdと分類UNKNOWNの文書種別は飛ばす() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired("tenant-a", 1L, "IMPORT"), expired("tenant-a", 2L, "QUOTATION"),
                expired("tenant-a", 3L, "CONTRACT")));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-a", PrivacyHoldTestGuards.verified(Set.of(), Set.of("DOCUMENT:IMPORT"), Map.of(
                        "DOCUMENT:IMPORT", SubjectAttribution.NOT_ATTRIBUTABLE,
                        "DOCUMENT:QUOTATION", SubjectAttribution.UNKNOWN,
                        "DOCUMENT:CONTRACT", SubjectAttribution.ATTRIBUTABLE)));

        new DocumentRetentionJob(documentMapper, eligibility, hold.guard).processExpiredDocuments();

        verify(eligibility, never()).registerRetentionDisposalCandidate(eq("tenant-a"), eq(1L), any());
        verify(eligibility, never()).registerRetentionDisposalCandidate(eq("tenant-a"), eq(2L), any());
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-a"), eq(3L), any());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D3, PrivacyHoldGate.Verdict.HELD));
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D3, PrivacyHoldGate.Verdict.UNCLASSIFIED));
    }

    @Test
    void D3_hold機構を使っていない環境では照会はtenantごとに1回だけで本人導出を呼ばない() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired("tenant-a", 1L, "CONTRACT"), expired("tenant-a", 2L, "IMPORT"),
                expired("tenant-a", 3L, "QUOTATION")));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub();

        new DocumentRetentionJob(documentMapper, eligibility, hold.guard).processExpiredDocuments();

        verify(eligibility, times(3)).registerRetentionDisposalCandidate(eq("tenant-a"), anyLong(), any());
        assertEquals(1, hold.gateCalls.get());
        verifyNoInteractions(hold.resolver);
        assertEquals(0.0, hold.skipped(PrivacyHoldPath.D3, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D3_tenantの無い文書は申請を作らずに数え他の文書の抽出を止めない() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired(null, 1L, "CONTRACT"), expired("tenant-a", 2L, "CONTRACT")));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub();

        new DocumentRetentionJob(documentMapper, eligibility, hold.guard).processExpiredDocuments();

        verify(eligibility, never()).registerRetentionDisposalCandidate(org.mockito.ArgumentMatchers.isNull(), eq(1L), any());
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-a"), eq(2L), any());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D3, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    private static Document expired(String tenantId, long id) {
        return expired(tenantId, id, null);
    }

    private static Document expired(String tenantId, long id, String documentType) {
        Document document = new Document();
        document.setId(id);
        document.setTenantId(tenantId);
        document.setDocumentType(documentType);
        document.setStatus("CONFIRMED");
        document.setLegalHoldFlag(0);
        document.setRetentionUntil(LocalDate.now().minusDays(1));
        return document;
    }
}
