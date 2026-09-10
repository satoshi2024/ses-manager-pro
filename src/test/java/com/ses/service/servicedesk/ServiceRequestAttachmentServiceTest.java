package com.ses.service.servicedesk;

import com.ses.common.exception.BusinessException;
import com.ses.config.UploadProperties;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceComment;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.mapper.ServiceCommentMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.service.DocumentService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceRequestAttachmentServiceTest {

    @Mock private ServiceRequestMapper requestMapper;
    @Mock private ServiceCommentMapper commentMapper;
    @Mock private ServiceAttachmentLinkMapper attachmentLinkMapper;
    @Mock private DocumentVersionMapper documentVersionMapper;
    @Mock private DocumentService documentService;
    @Mock private DataScopeService dataScopeService;

    private ServiceRequestAttachmentService service;
    private ServiceRequest request;
    private Document document;
    private DocumentVersion version;

    @BeforeEach
    void setUp() {
        service = new ServiceRequestAttachmentService(requestMapper, commentMapper, attachmentLinkMapper,
                documentVersionMapper, documentService, dataScopeService, new UploadProperties(),
                Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Tokyo")));
        request = new ServiceRequest();
        request.setId(10L);
        request.setCustomerId(20L);
        when(requestMapper.selectById(10L)).thenReturn(request);
        lenient().when(dataScopeService.isScoped()).thenReturn(false);

        document = new Document();
        document.setId(30L);
        lenient().when(documentService.registerReceived(any(), any())).thenReturn(document);
        version = new DocumentVersion();
        version.setId(31L);
        version.setDocumentId(30L);
        version.setVersionNo(1);
        version.setScanStatus("CLEAN");
        lenient().when(documentVersionMapper.findLatestByDocumentId(30L)).thenReturn(version);
    }

    @Test
    void 内部uploadは文書台帳とSERVICE_REQUESTtypedLinkとmetadataを同一経路で登録する() {
        ServiceAttachmentLink result = service.uploadInternal(10L, null,
                new MockMultipartFile("file", "report.pdf", "application/pdf", "PDF".getBytes()),
                "INTERNAL", 100L);

        assertEquals(10L, result.getServiceRequestId());
        assertEquals("INTERNAL", result.getVisibility());
        ArgumentCaptor<com.ses.dto.document.DocumentRegisterRequest> requestCaptor =
                ArgumentCaptor.forClass(com.ses.dto.document.DocumentRegisterRequest.class);
        verify(documentService).registerReceived(requestCaptor.capture(), any());
        assertEquals("SERVICE_REQUEST_ATTACHMENT", requestCaptor.getValue().getDocumentType());
        assertEquals("SERVICE_REQUEST", requestCaptor.getValue().getTargetType());
        assertEquals(10L, requestCaptor.getValue().getTargetId());
        verify(documentService).link(30L, "SERVICE_REQUEST", 10L);
        verify(attachmentLinkMapper).insert(any(ServiceAttachmentLink.class));
    }

    @Test
    void Portalの顧客scope内uploadは公開リンクとして登録する() {
        ServiceAttachmentLink result = service.uploadPortal(10L, null,
                new MockMultipartFile("file", "reply.pdf", "application/pdf", "PDF".getBytes()), 20L, 200L);

        assertEquals("PORTAL_VISIBLE", result.getVisibility());
        verify(documentService).link(30L, "SERVICE_REQUEST", 10L);
    }

    @Test
    void 顧客が一致しないPortaluploadは文書登録前に拒否する() {
        assertThrows(BusinessException.class, () -> service.uploadPortal(10L, null,
                new MockMultipartFile("file", "reply.pdf", "application/pdf", "PDF".getBytes()), 99L, 200L));
        verify(documentService, never()).registerReceived(any(), any());
    }

    @Test
    void commentが別requestなら文書登録前に拒否する() {
        ServiceComment comment = new ServiceComment();
        comment.setId(40L);
        comment.setServiceRequestId(999L);
        when(commentMapper.selectById(40L)).thenReturn(comment);

        assertThrows(BusinessException.class, () -> service.uploadInternal(10L, 40L,
                new MockMultipartFile("file", "reply.pdf", "application/pdf", "PDF".getBytes()),
                "INTERNAL", 100L));
        verify(documentService, never()).registerReceived(any(), any());
    }

    @Test
    void 同じ内容のretryは既存linkを返して二重linkを作らない() {
        ServiceAttachmentLink existing = ServiceAttachmentLink.builder()
                .id(50L).serviceRequestId(10L).documentId(30L).visibility("INTERNAL")
                .fileName("report.pdf").fileSize(3L).build();
        when(attachmentLinkMapper.selectOne(any())).thenReturn(existing);

        ServiceAttachmentLink result = service.uploadInternal(10L, null,
                new MockMultipartFile("file", "report.pdf", "application/pdf", "PDF".getBytes()),
                "INTERNAL", 100L);

        assertEquals(50L, result.getId());
        verify(attachmentLinkMapper, never()).insert(any(ServiceAttachmentLink.class));
    }

    @Test
    void 非許可MIMEはDocumentServiceに到達しない() {
        assertThrows(BusinessException.class, () -> service.uploadInternal(10L, null,
                new MockMultipartFile("file", "payload.exe", "application/octet-stream", "MZ".getBytes()),
                "INTERNAL", 100L));
        verify(documentService, never()).registerReceived(any(), any());
    }
}
