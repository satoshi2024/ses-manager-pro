package com.ses.service.certificationlearninggap;

import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationEvidenceRestrictedResolverTest {

    @Mock private EngineerCertificationMapper certificationMapper;
    @Mock private DocumentLinkMapper linkMapper;
    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentVersionMapper versionMapper;
    @Mock private CertificationEventMapper eventMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private FileScopeValidationService fileScopeValidationService;

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void verificationCandidatesAreCleanAndTyped() {
        EngineerCertification record = certificationRecord("tenant-a", "DRAFT");
        DocumentLink link = new DocumentLink();
        link.setTenantId("tenant-a");
        link.setDocumentId(30L);
        link.setTargetType("CERTIFICATION_RECORD");
        link.setTargetId(10L);
        Document document = new Document();
        document.setId(30L);
        document.setTenantId("tenant-a");
        document.setDocumentType("CERTIFICATION_EVIDENCE");
        document.setLegalHoldFlag(0);
        DocumentVersion version = new DocumentVersion();
        version.setId(31L);
        version.setTenantId("tenant-a");
        version.setDocumentId(30L);
        version.setVersionNo(2);
        version.setScanStatus("CLEAN");

        when(certificationMapper.selectOne(any())).thenReturn(record);
        when(linkMapper.selectList(any())).thenReturn(List.of(link));
        when(documentMapper.selectOne(any())).thenReturn(document);
        when(versionMapper.selectList(any())).thenReturn(List.of(version));

        CertificationEvidenceRestrictedResolver resolver = new CertificationEvidenceRestrictedResolver(
                certificationMapper, linkMapper, documentMapper, versionMapper, eventMapper,
                dataScopeService, fileScopeValidationService);

        List<CertificationEvidenceRestrictedResolver.ResolvedEvidence> result =
                AccountingTenantContextHolder.runWithTenant("tenant-a", () -> resolver.listForVerification(10L));

        assertEquals(1, result.size());
        assertEquals(31L, result.get(0).version().getId());
        assertTrue(result.get(0).version().getStorageKey() == null);
        verify(dataScopeService).assertAllowedEngineer(20L);
        verify(eventMapper, org.mockito.Mockito.never()).selectByTenantAndRecordId(any(), any());
    }

    @Test
    void displayUsesVerifiedExactPathForActiveRecord() {
        EngineerCertification record = certificationRecord("tenant-a", "ACTIVE");
        when(certificationMapper.selectOne(any())).thenReturn(record);
        when(linkMapper.selectList(any())).thenReturn(List.of());

        CertificationEvidenceRestrictedResolver resolver = new CertificationEvidenceRestrictedResolver(
                certificationMapper, linkMapper, documentMapper, versionMapper, eventMapper,
                dataScopeService, fileScopeValidationService);

        List<CertificationEvidenceRestrictedResolver.ResolvedEvidence> result =
                AccountingTenantContextHolder.runWithTenant("tenant-a", () -> resolver.listForDisplay(10L));

        assertTrue(result.isEmpty());
    }

    private EngineerCertification certificationRecord(String tenantId, String state) {
        EngineerCertification record = new EngineerCertification();
        record.setId(10L);
        record.setTenantId(tenantId);
        record.setEngineerId(20L);
        record.setRecordState(state);
        record.setCurrentFlag(1);
        return record;
    }
}
