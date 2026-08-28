package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.SkillGapService;
import com.ses.service.security.impl.FileScopeValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** 資格証憑のdownload境界。typed link・版/hash・CLEAN・legal holdを毎回再検証する。 */
@Service
@RequiredArgsConstructor
public class CertificationEvidenceAccessService {

    private final EngineerCertificationMapper certificationMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentService documentService;
    private final FileScopeValidationService fileScopeValidationService;
    private final EngineerAccountLinkService accountLinkService;
    private final CertificationLearningGapQueryService queryService;
    private final Clock clock;

    public EvidenceDownload downloadForManagement(Long engineerId, Long recordId, Long documentId, Integer versionNo,
                                                  Authentication authentication) {
        EngineerCertification record = record(recordId);
        if (!Objects.equals(engineerId, record.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        queryService.detail(record.getEngineerId(), new CertificationLearningGapFilter(record.getEngineerId(), null,
                null, null, null, LocalDate.now(clock), null, SkillGapService.DemandSource.COMBINED), authentication);
        return download(record, documentId, versionNo);
    }

    public EvidenceDownload downloadForSelf(Long actorUserId, Long recordId, Long documentId, Integer versionNo) {
        EngineerCertification record = record(recordId);
        Long ownEngineerId = actorUserId == null ? null : accountLinkService.findEngineerIdByUserId(actorUserId);
        if (!Objects.equals(ownEngineerId, record.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return download(record, documentId, versionNo);
    }

    private EvidenceDownload download(EngineerCertification record, Long documentId, Integer versionNo) {
        if (documentId == null || versionNo == null) {
            throw BusinessException.of(404, "error.document.versionNotFound");
        }
        boolean linked = documentLinkMapper.selectList(new LambdaQueryWrapper<DocumentLink>()
                        .eq(DocumentLink::getDocumentId, documentId)
                        .eq(DocumentLink::getTargetType, "CERTIFICATION_RECORD")
                        .eq(DocumentLink::getTargetId, record.getId()))
                .stream().anyMatch(link -> !Integer.valueOf(1).equals(link.getDeletedFlag()));
        if (!linked) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentId, documentId).eq(DocumentVersion::getVersionNo, versionNo));
        if (version == null || !"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        String storageKey = documentService.getVersionStorageKey(documentId, versionNo);
        if (storageKey == null) {
            throw BusinessException.of(404, "error.document.versionNotFound");
        }
        fileScopeValidationService.assertDownloadAllowed(storageKey, version.getId(), version.getSha256());
        return new EvidenceDownload(documentId, versionNo, version.getOriginalName(), version.getContentType(),
                documentService.download(documentId, versionNo));
    }

    private EngineerCertification record(Long recordId) {
        EngineerCertification record = recordId == null ? null : certificationMapper.selectById(recordId);
        if (record == null) {
            throw BusinessException.of(404, "certification.record.notFound");
        }
        return record;
    }

    public record EvidenceDownload(Long documentId, Integer versionNo, String fileName, String contentType,
                                   InputStream content) { }
}
