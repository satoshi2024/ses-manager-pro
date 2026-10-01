package com.ses.service.servicedesk;

import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceRequestAttachmentCommitServiceTest {

    @Mock private ServiceRequestMapper requestMapper;
    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentVersionMapper versionMapper;
    @Mock private DocumentLinkMapper documentLinkMapper;
    @Mock private ServiceAttachmentLinkMapper attachmentLinkMapper;

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 同一tenantの短transactioncommitはtypedLinkとCLEANを再検証しtenant付きlinkを一行へ収束する() {
        ServiceRequest request = new ServiceRequest();
        request.setId(10L);
        request.setTenantId("tenant-a");
        Document document = new Document();
        document.setId(30L);
        document.setTenantId("tenant-a");
        DocumentVersion version = new DocumentVersion();
        version.setId(31L);
        version.setDocumentId(30L);
        version.setTenantId("tenant-a");
        version.setScanStatus("CLEAN");
        DocumentLink typedLink = new DocumentLink();
        typedLink.setTenantId("tenant-a");

        when(requestMapper.selectByIdAndTenant(10L, "tenant-a")).thenReturn(request);
        when(documentMapper.selectOne(any())).thenReturn(document);
        when(versionMapper.findLatestByTenantAndDocumentId("tenant-a", 30L)).thenReturn(version);
        when(documentLinkMapper.selectOne(any())).thenReturn(typedLink);
        when(attachmentLinkMapper.selectByBusinessKey("tenant-a", "business-key"))
                .thenReturn(null);

        ServiceRequestAttachmentCommitService service = new ServiceRequestAttachmentCommitService(
                requestMapper, documentMapper, versionMapper, documentLinkMapper, attachmentLinkMapper);

        ServiceAttachmentLink saved = AccountingTenantContextHolder.runWithTenant("tenant-a", () ->
                service.commit("tenant-a", 10L, 11L, 30L, "INTERNAL", "a.pdf", 12L, "business-key"));

        ArgumentCaptor<ServiceAttachmentLink> captor = ArgumentCaptor.forClass(ServiceAttachmentLink.class);
        verify(attachmentLinkMapper).insert(captor.capture());
        assertEquals("tenant-a", captor.getValue().getTenantId());
        assertEquals("tenant-a", saved.getTenantId());
        assertEquals(10L, saved.getServiceRequestId());
    }

    @Test
    void 同一businessKeyのretryは既存linkを返し二重insertしない() {
        ServiceAttachmentLink existing = ServiceAttachmentLink.builder()
                .id(99L).tenantId("tenant-a").serviceRequestId(10L).documentId(30L)
                .visibility("PORTAL_VISIBLE").build();
        when(attachmentLinkMapper.selectByBusinessKey("tenant-a", "business-key"))
                .thenReturn(existing);

        ServiceRequestAttachmentCommitService service = new ServiceRequestAttachmentCommitService(
                requestMapper, documentMapper, versionMapper, documentLinkMapper, attachmentLinkMapper);

        // existing linkの冪等returnは親行の再検証後に限るため、まず同一tenantの親境界を用意する。
        ServiceRequest request = new ServiceRequest();
        request.setId(10L);
        request.setTenantId("tenant-a");
        Document document = new Document();
        document.setId(30L);
        document.setTenantId("tenant-a");
        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(30L);
        version.setTenantId("tenant-a");
        version.setScanStatus("CLEAN");
        when(requestMapper.selectByIdAndTenant(10L, "tenant-a")).thenReturn(request);
        when(documentMapper.selectOne(any())).thenReturn(document);
        when(versionMapper.findLatestByTenantAndDocumentId("tenant-a", 30L)).thenReturn(version);
        DocumentLink typedLink = new DocumentLink();
        typedLink.setTenantId("tenant-a");
        when(documentLinkMapper.selectOne(any())).thenReturn(typedLink);

        ServiceAttachmentLink result = service.commit("tenant-a", 10L, null, 30L,
                "PORTAL_VISIBLE", "a.pdf", 12L, "business-key");

        assertEquals(99L, result.getId());
        verify(attachmentLinkMapper, never()).insert(any(ServiceAttachmentLink.class));
    }
}
