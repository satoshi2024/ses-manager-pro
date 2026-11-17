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
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.anyString;
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
        AccountingTenantContextHolder.setTenantId("default");
        service = new ServiceRequestAttachmentService(requestMapper, commentMapper, attachmentLinkMapper,
                documentVersionMapper, documentService, dataScopeService, new UploadProperties(),
                Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Tokyo")));
        request = new ServiceRequest();
        request.setId(10L);
        request.setCustomerId(20L);
        request.setTenantId("default");
        lenient().when(requestMapper.selectById(10L)).thenReturn(request);
        lenient().when(requestMapper.selectByIdAndTenant(10L, "default")).thenReturn(request);
        lenient().when(dataScopeService.isScoped()).thenReturn(false);

        document = new Document();
        document.setId(30L);
        document.setTenantId("default");
        lenient().when(documentService.registerReceived(any(), any())).thenReturn(document);
        version = new DocumentVersion();
        version.setId(31L);
        version.setTenantId("default");
        version.setDocumentId(30L);
        version.setVersionNo(1);
        version.setScanStatus("CLEAN");
        lenient().when(documentVersionMapper.findLatestByDocumentId(30L)).thenReturn(version);
        lenient().when(documentVersionMapper.findByIdempotencyKey(
                any(), anyString(), anyString(), anyString())).thenReturn(version);
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
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
        // portal起票の問い合わせ（portalへ出してよい範囲。policy未注入の既定はportal起票のみ）
        request.setChannel("PORTAL");
        request.setPortalUserId(200L);

        ServiceAttachmentLink result = service.uploadPortal(10L, null,
                new MockMultipartFile("file", "reply.pdf", "application/pdf", "PDF".getBytes()), 20L, 200L);

        assertEquals("PORTAL_VISIBLE", result.getVisibility());
        verify(documentService).link(30L, "SERVICE_REQUEST", 10L);
    }

    @Test
    void 社内登録の問い合わせへのPortaluploadは公開範囲外として文書登録前に404で拒否する() {
        // 既定（公開開始時刻なし）では、社内起票の問い合わせはportalから扱えない。
        BusinessException error = assertThrows(BusinessException.class, () -> service.uploadPortal(10L, null,
                new MockMultipartFile("file", "reply.pdf", "application/pdf", "PDF".getBytes()), 20L, 200L));

        assertEquals(404, error.getCode());
        verify(documentService, never()).registerReceived(any(), any());
        verify(attachmentLinkMapper, never()).insert(any(ServiceAttachmentLink.class));
    }

    @Test
    void 社内uploadは公開範囲policyの影響を受けない() {
        // 内部経路は社内起票の問い合わせにも従来どおり登録できる（policyを使わない）。
        org.springframework.test.util.ReflectionTestUtils.setField(service, "portalVisibilityPolicy",
                PortalServiceRequestVisibilityPolicy.PORTAL_ORIGINATED_ONLY);

        ServiceAttachmentLink result = service.uploadInternal(10L, null,
                new MockMultipartFile("file", "memo.pdf", "application/pdf", "PDF".getBytes()), "INTERNAL", 100L);

        assertEquals("INTERNAL", result.getVisibility());
        verify(attachmentLinkMapper).insert(any(ServiceAttachmentLink.class));
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
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.uploadInternal(10L, null,
                new MockMultipartFile("file", "payload.exe", "application/octet-stream", "MZ".getBytes()),
                "INTERNAL", 100L));
        // P3-N2-1: 415はSafeErrorPolicyで500へ丸めずに返す（GlobalExceptionHandlerTestで確認）
        assertEquals(415, rejected.getCode());
        assertEquals("error.file.typeNotAllowed", rejected.getMessageKey());
        verify(documentService, never()).registerReceived(any(), any());
    }

    /** W2 r2 P3-4: 空ファイルは「上限超過」の413ではなく入力誤りの400で、空であることを示す文言にする。 */
    @Test
    void 空ファイルは400のerror_file_emptyで文書登録前に拒否する() {
        for (String endpoint : java.util.List.of("internal", "portal")) {
            MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
            BusinessException rejected = assertThrows(BusinessException.class, () -> {
                if ("internal".equals(endpoint)) {
                    service.uploadInternal(10L, null, empty, "INTERNAL", 100L);
                } else {
                    request.setChannel("PORTAL");
                    request.setPortalUserId(200L);
                    service.uploadPortal(10L, null, empty, 20L, 200L);
                }
            }, endpoint);
            assertEquals(400, rejected.getCode(), endpoint);
            assertEquals("error.file.empty", rejected.getMessageKey(), endpoint);
        }
        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.uploadInternal(10L, null, null, "INTERNAL", 100L));
        assertEquals(400, missing.getCode());
        assertEquals("error.file.empty", missing.getMessageKey());
        verify(documentService, never()).registerReceived(any(), any());
    }

    @Test
    void 上限を超えるファイルは413のerror_file_sizeExceededで文書登録前に拒否する() {
        UploadProperties properties = new UploadProperties();
        properties.setServiceRequestMaxFileSizeBytes(4L);
        ServiceRequestAttachmentService limited = new ServiceRequestAttachmentService(requestMapper, commentMapper,
                attachmentLinkMapper, documentVersionMapper, documentService, dataScopeService, properties,
                Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Tokyo")));

        BusinessException rejected = assertThrows(BusinessException.class, () -> limited.uploadInternal(10L, null,
                new MockMultipartFile("file", "large.pdf", "application/pdf", "12345".getBytes()), "INTERNAL", 100L));
        assertEquals(413, rejected.getCode());
        assertEquals("error.file.sizeExceeded", rejected.getMessageKey());
        verify(documentService, never()).registerReceived(any(), any());

        // 上限ちょうどは受け付ける（境界の取り違えが無いこと）
        limited.uploadInternal(10L, null,
                new MockMultipartFile("file", "exact.pdf", "application/pdf", "1234".getBytes()), "INTERNAL", 100L);
        verify(documentService).registerReceived(any(), any());
    }
}
