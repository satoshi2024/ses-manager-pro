package com.ses.service.certification;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationEvidenceValidatorTest {

    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentVersionMapper documentVersionMapper;
    @Mock private DocumentLinkMapper documentLinkMapper;

    @Test
    void 証憑三組がすべてnullの場合は拒否する() {
        CertificationEvidenceValidator validator = new CertificationEvidenceValidator(documentMapper,
                documentVersionMapper, documentLinkMapper);
        assertThrows(BusinessException.class, () -> validator.validate(20L, null, null, null));
    }

    @Test
    void typedLinkとCLEANと版hashが一致する場合だけ許可する() {
        Document document = document("CERTIFICATION_EVIDENCE");
        DocumentVersion version = version(100L, 10L, "abc123", "CLEAN");
        DocumentLink link = new DocumentLink();
        link.setDocumentId(10L);
        link.setTargetType("CERTIFICATION_RECORD");
        link.setTargetId(20L);
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(documentVersionMapper.selectById(100L)).thenReturn(version);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(link));

        CertificationEvidenceValidator validator = new CertificationEvidenceValidator(documentMapper,
                documentVersionMapper, documentLinkMapper);
        assertDoesNotThrow(() -> validator.validate(20L, 10L, 100L, "ABC123"));
    }

    @Test
    void versionHashscan未一致とgenericLinkだけは拒否する() {
        Document document = document("CERTIFICATION_EVIDENCE");
        DocumentVersion version = version(100L, 10L, "abc123", "PENDING");
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(documentVersionMapper.selectById(100L)).thenReturn(version);
        CertificationEvidenceValidator validator = new CertificationEvidenceValidator(documentMapper,
                documentVersionMapper, documentLinkMapper);
        assertThrows(BusinessException.class, () -> validator.validate(20L, 10L, 100L, "abc123"));

        version.setScanStatus("CLEAN");
        DocumentLink generic = new DocumentLink();
        generic.setTargetType("ENGINEER");
        generic.setTargetId(20L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(generic));
        assertThrows(BusinessException.class, () -> validator.validate(20L, 10L, 100L, "abc123"));
    }

    private Document document(String type) {
        Document document = new Document();
        document.setId(10L);
        document.setDocumentType(type);
        return document;
    }

    private DocumentVersion version(Long id, Long documentId, String hash, String scan) {
        DocumentVersion version = new DocumentVersion();
        version.setId(id);
        version.setDocumentId(documentId);
        version.setSha256(hash);
        version.setScanStatus(scan);
        return version;
    }
}
