package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.entity.CertificationEvent;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.impl.FileScopeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificationEvidenceAccessServiceTest {

    @Mock private EngineerCertificationMapper certificationMapper;
    @Mock private DocumentLinkMapper documentLinkMapper;
    @Mock private DocumentVersionMapper documentVersionMapper;
    @Mock private DocumentService documentService;
    @Mock private FileScopeValidationService fileScopeValidationService;
    @Mock private EngineerAccountLinkService accountLinkService;
    @Mock private CertificationLearningGapQueryService queryService;
    @Mock private CertificationEventMapper eventMapper;

    private CertificationEvidenceAccessService service;
    private EngineerCertification record;
    private DocumentVersion version;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        service = new CertificationEvidenceAccessService(certificationMapper, documentLinkMapper,
                documentVersionMapper, documentService, fileScopeValidationService, accountLinkService,
                queryService, Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Tokyo")), eventMapper);
        record = new EngineerCertification();
        record.setId(11L);
        record.setTenantId("default");
        record.setEngineerId(42L);
        record.setRecordState("ACTIVE");
        record.setCurrentFlag(1);
        version = new DocumentVersion();
        version.setId(88L);
        version.setTenantId("default");
        version.setDocumentId(77L);
        version.setVersionNo(2);
        version.setOriginalName("evidence.pdf");
        version.setContentType("application/pdf");
        version.setSha256("abc123");
        version.setScanStatus("CLEAN");
        CertificationEvent verify = new CertificationEvent();
        verify.setCertificationRecordId(11L);
        verify.setEventType("VERIFY");
        verify.setEffectiveRecordState("ACTIVE");
        verify.setEvidenceDocumentId(77L);
        verify.setEvidenceDocumentVersionId(88L);
        verify.setEvidenceDocumentHash("abc123");
        when(eventMapper.selectByTenantAndRecordId("default", 11L)).thenReturn(List.of(verify));
        when(certificationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);
        DocumentLink link = new DocumentLink();
        link.setDocumentId(77L);
        link.setTenantId("default");
        link.setTargetType("CERTIFICATION_RECORD");
        link.setTargetId(11L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(link));
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);
        when(documentService.getVersionStorageKey(77L, 2)).thenReturn("certification/evidence-key");
        when(documentService.download(77L, 2)).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        when(queryService.detail(eq(42L), any(), any())).thenReturn(new CertificationLearningGapRow(
                42L, "対象", "稼動中", "ACTIVE", List.of(), List.of(), null, null, null, List.of()));
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void managementDownloadはscopeとtypedLinkと版hashを毎回検証する() {
        var result = service.downloadForManagement(42L, 11L, 77L, 2,
                new TestingAuthenticationToken("8", "n", "ROLE_HR"));

        assertEquals("evidence.pdf", result.fileName());
        verify(fileScopeValidationService).assertCertificationEvidenceDownloadAllowed(
                "certification/evidence-key", 11L, 77L, 88L, "abc123");
        verify(documentService).download(77L, 2);
    }

    @Test
    void URLのengineerIdがrecordと違えば証憑も取得できない() {
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.downloadForManagement(99L, 11L, 77L, 2, null));
    }

    @Test
    void 本人downloadはaccountLinkの本人だけに限定する() {
        when(accountLinkService.findEngineerIdByUserId(100L)).thenReturn(42L);
        assertEquals(77L, service.downloadForSelf(100L, 11L, 77L, 2).documentId());
        when(accountLinkService.findEngineerIdByUserId(101L)).thenReturn(99L);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.downloadForSelf(101L, 11L, 77L, 2));
    }

    @Test
    void 同じdocumentの別CLEAN版はVERIFYeventのexact版と一致しないため拒否する() {
        version.setId(89L);

        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.downloadForSelf(100L, 11L, 77L, 2));
    }

    @Test
    void VERIFYeventのhash不一致は拒否する() {
        CertificationEvent event = new CertificationEvent();
        event.setCertificationRecordId(11L);
        event.setEventType("VERIFY");
        event.setEffectiveRecordState("ACTIVE");
        event.setEvidenceDocumentId(77L);
        event.setEvidenceDocumentVersionId(88L);
        event.setEvidenceDocumentHash("different-hash");
        when(eventMapper.selectByTenantAndRecordId("default", 11L)).thenReturn(List.of(event));

        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.downloadForSelf(100L, 11L, 77L, 2));
    }

    @Test
    void VERIFYeventが無い証憑とgenericEngineerLinkだけの証憑は拒否する() {
        when(eventMapper.selectByTenantAndRecordId("default", 11L)).thenReturn(List.of());
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.downloadForSelf(100L, 11L, 77L, 2));

        CertificationEvent verify = new CertificationEvent();
        verify.setEventType("VERIFY");
        verify.setEffectiveRecordState("ACTIVE");
        verify.setEvidenceDocumentId(77L);
        verify.setEvidenceDocumentVersionId(88L);
        verify.setEvidenceDocumentHash("abc123");
        when(eventMapper.selectByTenantAndRecordId("default", 11L)).thenReturn(List.of(verify));
        DocumentLink generic = new DocumentLink();
        generic.setDocumentId(77L);
        generic.setTargetType("ENGINEER");
        generic.setTargetId(42L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(generic));
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.downloadForSelf(100L, 11L, 77L, 2));
    }
}
