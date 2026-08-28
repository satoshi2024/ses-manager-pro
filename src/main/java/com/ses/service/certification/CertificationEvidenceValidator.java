package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 資格eventへ保存する証憑版をtyped link・hash・scan状態で固定する。 */
@Service
public class CertificationEvidenceValidator {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentLinkMapper documentLinkMapper;

    public CertificationEvidenceValidator(DocumentMapper documentMapper,
                                          DocumentVersionMapper documentVersionMapper,
                                          DocumentLinkMapper documentLinkMapper) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentLinkMapper = documentLinkMapper;
    }

    public void validate(Long certificationRecordId, Long documentId, Long documentVersionId, String expectedHash) {
        if (documentId == null && documentVersionId == null && expectedHash == null) {
            return;
        }
        if (documentId == null || documentVersionId == null || expectedHash == null || expectedHash.isBlank()) {
            throw BusinessException.of(400, "certification.evidence.versionRequired");
        }
        Document document = documentMapper.selectById(documentId);
        DocumentVersion version = documentVersionMapper.selectById(documentVersionId);
        if (document == null || !"CERTIFICATION_EVIDENCE".equals(document.getDocumentType())
                || version == null || !documentId.equals(version.getDocumentId())) {
            throw BusinessException.of(403, "certification.evidence.invalid");
        }
        if (!"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        if (version.getSha256() == null || !expectedHash.equalsIgnoreCase(version.getSha256())) {
            throw BusinessException.of(403, "error.file.hashMismatch");
        }
        boolean linked = documentLinkMapper.selectList(new QueryWrapper<DocumentLink>()
                        .eq("document_id", documentId).eq("target_type", "CERTIFICATION_RECORD")
                        .eq("target_id", certificationRecordId))
                .stream().anyMatch(link -> "CERTIFICATION_RECORD".equals(link.getTargetType())
                        && Objects.equals(certificationRecordId, link.getTargetId())
                        && !Integer.valueOf(1).equals(link.getDeletedFlag()));
        if (!linked) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
    }
}
