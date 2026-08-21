package com.ses.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.entity.Document;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.DocumentService;
import com.ses.service.impl.AcceptanceServiceImpl;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S09-P2-01: 検収書uploadの行ロック＋document_id IS NULL条件更新（L0）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AcceptanceUploadDocumentUnitTest {

    @Mock private AcceptanceMapper acceptanceMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private WorkRecordMapper workRecordMapper;
    @Mock private CustomerContactMapper customerContactMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private DocumentService documentService;
    @Mock private ObjectProvider<com.ses.service.portal.PortalNotificationService> portalNotificationServiceProvider;

    @InjectMocks
    private AcceptanceServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", acceptanceMapper);
        when(dataScopeService.isScoped()).thenReturn(false);
        when(portalNotificationServiceProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    @DisplayName("uploadはselectByIdForUpdate後、document_id IS NULLの条件更新を行う")
    void uploadUsesForUpdateAndNullGuard() {
        Acceptance locked = accepted(1L);
        when(acceptanceMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(acceptanceMapper.selectById(1L)).thenReturn(locked);
        when(contractMapper.selectById(10L)).thenReturn(contract(10L));

        Document doc = new Document();
        doc.setId(55L);
        when(documentService.registerReceived(any(), any())).thenReturn(doc);
        when(acceptanceMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf",
                "pdf".getBytes(StandardCharsets.UTF_8));
        Acceptance result = service.uploadDocument(1L, file);

        verify(acceptanceMapper).selectByIdForUpdate(1L);
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(acceptanceMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .containsIgnoringCase("document_id")
                .containsIgnoringCase("IS NULL");
        assertThat(result).isSameAs(locked);
    }

    @Test
    @DisplayName("条件更新が0件なら二重登録として409")
    void uploadRejectsWhenConditionalUpdateMisses() {
        Acceptance locked = accepted(2L);
        when(acceptanceMapper.selectByIdForUpdate(2L)).thenReturn(locked);
        when(acceptanceMapper.selectById(2L)).thenReturn(locked);
        when(contractMapper.selectById(10L)).thenReturn(contract(10L));

        Document doc = new Document();
        doc.setId(56L);
        when(documentService.registerReceived(any(), any())).thenReturn(doc);
        when(acceptanceMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf",
                "pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.uploadDocument(2L, file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.acceptance.documentAlreadyRegistered");
    }

    @Test
    @DisplayName("既にdocument_idがある場合は登録前に拒否する")
    void uploadRejectsWhenDocumentAlreadySet() {
        Acceptance locked = accepted(3L);
        locked.setDocumentId(99L);
        when(acceptanceMapper.selectByIdForUpdate(3L)).thenReturn(locked);
        when(acceptanceMapper.selectById(3L)).thenReturn(locked);

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf",
                "pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.uploadDocument(3L, file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.acceptance.documentAlreadyRegistered");
        verify(documentService, never()).registerReceived(any(), any());
    }

    private static Acceptance accepted(Long id) {
        Acceptance a = new Acceptance();
        a.setId(id);
        a.setContractId(10L);
        a.setWorkMonth("2026-07");
        a.setStatus(StatusConstants.ACCEPTANCE_ACCEPTED);
        a.setAcceptedAt(java.time.LocalDateTime.of(2026, 7, 31, 12, 0));
        return a;
    }

    private static Contract contract(Long id) {
        Contract c = new Contract();
        c.setId(id);
        c.setCustomerId(20L);
        c.setContractNo("C-UT-1");
        return c;
    }
}
