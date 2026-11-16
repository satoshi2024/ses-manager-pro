package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentAccessLog;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentType;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentTypeMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.document.DocumentEligibilityService;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF07 A1H-2 D1・D2: 文書の廃棄執行・廃棄申請が NF07 保全 hold を同じ transaction の中・更新の前に判定すること
 * （production-bridge-phased-plan.md §3.4.1・§3.4.2、Demo 2）。NF07 hold と {@code legal_hold_flag} をそれぞれ単独と両方で確かめ、
 * gate が UNAVAILABLE・分類 UNKNOWN・本人の導出失敗も 409 で処分しないこと、hold 機構を使っていない tenant は照会1回で従来どおり
 * 処分し本人導出を呼ばないことを固定する。gate は判定を差し替えられる mock、本人導出は resolver の mock（provider の規則そのものは
 * {@code HoldSubjectResolverPathTest} が実 DB で扱う）。
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplDisposalHoldTest {

    private static final String TENANT = "default";
    private static final String HELD_ENGINEER = "ENGINEER:7";

    @Mock DocumentMapper documentMapper;
    @Mock DocumentVersionMapper documentVersionMapper;
    @Mock DocumentLinkMapper documentLinkMapper;
    @Mock DocumentAccessLogMapper documentAccessLogMapper;
    @Mock DocumentDisposalRequestMapper documentDisposalRequestMapper;
    @Mock DocumentTypeMapper documentTypeMapper;
    @Mock com.ses.service.security.DataScopeService dataScopeService;
    @Mock DocumentStorage documentStorage;
    @Mock ObjectProvider<FileScanner> fileScannerProvider;
    @Mock com.ses.mapper.DocumentHashClaimMapper documentHashClaimMapper;
    @Mock ObjectProvider<com.ses.service.security.AuthorizationService> authorizationServiceProvider;
    @Mock ObjectProvider<com.ses.service.EngineerAccountLinkService> engineerAccountLinkServiceProvider;
    @Mock ObjectProvider<com.ses.service.security.OrganizationScopeService> organizationScopeServiceProvider;
    @Mock com.ses.service.AssetScopeService assetScopeService;
    @Mock DocumentEligibilityService eligibility;
    @Mock com.ses.service.document.DocumentLinkCommandService linkCommandService;

    PrivacyHoldTestGuards.Stub hold;
    DocumentServiceImpl sut;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(config, "");
        for (Class<?> entity : new Class<?>[]{Document.class, DocumentVersion.class, DocumentLink.class,
                DocumentAccessLog.class, DocumentDisposalRequest.class, DocumentType.class}) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, entity);
        }
        var principal = User.withUsername("1").password("").authorities("ROLE_管理者").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        hold = PrivacyHoldTestGuards.stub();
        sut = new DocumentServiceImpl(documentMapper, documentVersionMapper, documentLinkMapper, documentAccessLogMapper,
                documentDisposalRequestMapper, documentTypeMapper, dataScopeService, documentStorage, fileScannerProvider,
                null, null, documentHashClaimMapper, authorizationServiceProvider, engineerAccountLinkServiceProvider,
                organizationScopeServiceProvider, assetScopeService, eligibility, linkCommandService, hold.guard);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    // ---- D1: 廃棄執行 ----

    @Test
    void D1_NF07のSUBJECT_holdに当たる文書は409で処分せずlegal_hold_flagも立てない() {
        Document doc = approvedTarget(0);
        hold.tenant(TENANT, subjectHold());
        when(hold.resolver.documentSubjects(eq(TENANT), any())).thenReturn(Map.of(5L, Set.of(HELD_ENGINEER)));

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(409, ex.getCode());
        assertEquals(PrivacyHoldPathGuard.HOLD_ACTIVE, ex.getMessageKey());
        verify(eligibility, never()).executeDisposal(anyString(), anyLong(), anyLong());
        verify(eligibility, never()).changeLegalHold(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(documentStorage, never()).delete(anyString());
        assertEquals(0, doc.getLegalHoldFlag(), "NF07 hold は legal_hold_flag を書き換えない");
        verify(hold.gate).evaluateLocked(TENANT);
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D1, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D1_legal_hold_flagだけなら従来どおり400でNF07のgateを照会しない() {
        approvedTarget(1);

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(400, ex.getCode());
        assertEquals("error.document.legalHoldActive", ex.getMessageKey());
        assertEquals(0, hold.gateCalls.get());
        verify(eligibility, never()).executeDisposal(anyString(), anyLong(), anyLong());
    }

    @Test
    void D1_NF07_holdとlegal_hold_flagが両方あれば従来の400で処分しない() {
        approvedTarget(1);
        hold.tenant(TENANT, subjectHold());

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(400, ex.getCode());
        assertEquals("error.document.legalHoldActive", ex.getMessageKey());
        verify(eligibility, never()).executeDisposal(anyString(), anyLong(), anyLong());
    }

    @Test
    void D1_gateがUNAVAILABLEなら管理者向けの再実行を促す409で処分しない() {
        approvedTarget(0);
        hold.tenant(TENANT, PrivacyHoldTestGuards.unavailable());

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(409, ex.getCode());
        // D1 は管理者だけが実行するため、判定できない場合は hold の有無と区別して再実行を促す（review P3-8）
        assertEquals(PrivacyHoldPathGuard.HOLD_UNVERIFIABLE, ex.getMessageKey());
        verify(eligibility, never()).executeDisposal(anyString(), anyLong(), anyLong());
        verifyNoInteractions(hold.resolver);
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D1, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    @Test
    void D1_DATA_ELEMENT_holdの文書種別は本人に関係なく409() {
        approvedTarget(0);
        hold.tenant(TENANT, PrivacyHoldTestGuards.verified(Set.of(), Set.of("DOCUMENT:CONTRACT"), catalog()));

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(409, ex.getCode());
        verify(eligibility, never()).executeDisposal(anyString(), anyLong(), anyLong());
        verifyNoInteractions(hold.resolver);
    }

    @Test
    void D1_分類がcatalogに無い文書種別はfail_closedで409() {
        approvedTarget(0);
        hold.tenant(TENANT, PrivacyHoldTestGuards.verified(Set.of(HELD_ENGINEER), Set.of(), Map.of()));

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(409, ex.getCode());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D1, PrivacyHoldGate.Verdict.UNCLASSIFIED));
    }

    @Test
    void D1_本人を導出できなければ409() {
        approvedTarget(0);
        hold.tenant(TENANT, subjectHold());
        when(hold.resolver.documentSubjects(eq(TENANT), any())).thenThrow(new IllegalStateException("provider"));

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));

        assertEquals(409, ex.getCode());
        verify(eligibility, never()).executeDisposal(anyString(), anyLong(), anyLong());
    }

    @Test
    void D1_他の本人のholdでは従来どおり処分する() {
        approvedTarget(0);
        hold.tenant(TENANT, subjectHold());
        when(hold.resolver.documentSubjects(eq(TENANT), any())).thenReturn(Map.of(99L, Set.of(HELD_ENGINEER)));
        when(eligibility.executeDisposal(TENANT, 5L, 200L)).thenReturn(true);

        sut.executeDisposal(200L);

        verify(eligibility).executeDisposal(TENANT, 5L, 200L);
    }

    @Test
    void D1_hold機構を使っていないtenantは照会1回だけで従来どおり処分し本人導出を呼ばない() {
        approvedTarget(0);
        when(eligibility.executeDisposal(TENANT, 5L, 200L)).thenReturn(true);

        sut.executeDisposal(200L);

        verify(eligibility).executeDisposal(TENANT, 5L, 200L);
        assertEquals(1, hold.gateCalls.get());
        verify(hold.gate).evaluateLocked(TENANT);
        verifyNoInteractions(hold.resolver);
    }

    // ---- D2: 廃棄申請 ----

    @Test
    void D2_NF07のholdに当たる文書は409で申請を作らない() {
        disposableDocument(0);
        hold.tenant(TENANT, subjectHold());
        when(hold.resolver.documentSubjects(eq(TENANT), any())).thenReturn(Map.of(5L, Set.of(HELD_ENGINEER)));

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.requestDisposal(5L, "期限到来"));

        assertEquals(409, ex.getCode());
        assertEquals(PrivacyHoldPathGuard.HOLD_ACTIVE, ex.getMessageKey());
        verify(eligibility, never()).requestDisposal(anyString(), anyLong(), any(), any());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D2, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D2_gateがUNAVAILABLEなら409で申請を作らない() {
        disposableDocument(0);
        hold.tenant(TENANT, PrivacyHoldTestGuards.unavailable());

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.requestDisposal(5L, "期限到来"));

        assertEquals(409, ex.getCode());
        // 申請者は管理者とは限らないため、判定できない場合も hold の有無を推測させない同じ key（review P3-8）
        assertEquals(PrivacyHoldPathGuard.HOLD_ACTIVE, ex.getMessageKey());
        verify(eligibility, never()).requestDisposal(anyString(), anyLong(), any(), any());
    }

    @Test
    void D2_NF07_holdとlegal_hold_flagが両方あれば従来の400で申請を作らない() {
        disposableDocument(1);
        hold.tenant(TENANT, subjectHold());

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.requestDisposal(5L, "期限到来"));

        assertEquals(400, ex.getCode());
        assertEquals("error.document.legalHoldActive", ex.getMessageKey());
        verify(eligibility, never()).requestDisposal(anyString(), anyLong(), any(), any());
    }

    @Test
    void D2_hold機構を使っていないtenantは照会1回だけで従来どおり申請を作る() {
        disposableDocument(0);
        when(eligibility.requestDisposal(eq(TENANT), eq(5L), any(), eq("期限到来")))
                .thenReturn(new DocumentDisposalRequest());

        sut.requestDisposal(5L, "期限到来");

        verify(eligibility).requestDisposal(eq(TENANT), eq(5L), any(), eq("期限到来"));
        assertEquals(1, hold.gateCalls.get());
        verifyNoInteractions(hold.resolver);
    }

    private Document approvedTarget(int legalHoldFlag) {
        DocumentDisposalRequest req = new DocumentDisposalRequest();
        req.setId(200L);
        req.setDocumentId(5L);
        req.setStatus("APPROVED");
        when(documentDisposalRequestMapper.selectById(200L)).thenReturn(req);
        return document(legalHoldFlag);
    }

    private Document disposableDocument(int legalHoldFlag) {
        return document(legalHoldFlag);
    }

    private Document document(int legalHoldFlag) {
        Document doc = new Document();
        doc.setId(5L);
        doc.setTenantId(TENANT);
        doc.setDocumentType("CONTRACT");
        doc.setLegalHoldFlag(legalHoldFlag);
        doc.setRetentionUntil(LocalDate.now().minusDays(1));
        doc.setStatus("CONFIRMED");
        when(documentMapper.selectOne(any())).thenReturn(doc);
        return doc;
    }

    private static PrivacyHoldGate.Decision subjectHold() {
        return PrivacyHoldTestGuards.verified(Set.of(HELD_ENGINEER), Set.of(), catalog());
    }

    private static Map<String, SubjectAttribution> catalog() {
        return Map.of("DOCUMENT:CONTRACT", SubjectAttribution.ATTRIBUTABLE);
    }
}
