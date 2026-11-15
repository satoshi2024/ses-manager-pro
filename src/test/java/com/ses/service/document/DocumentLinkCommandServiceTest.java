package com.ses.service.document;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.DocumentLink;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U9（NF01細粒度epoch計画 §4.3・§6.2）: {@link DocumentLinkCommandService} が文書行を先にX lockし、
 * 実際にlinkを挿入した時だけ文書自身のtenantで証跡利用可否epochを1回進めることを、実際の
 * {@link AuthorizationEpochFence}（同一transactionの重複除去を含む）とmapper mockで確かめる。
 */
class DocumentLinkCommandServiceTest {

    private static final String TENANT = "tenant-b";
    private static final long DOCUMENT_ID = 40L;

    private AuthorizationEpochMapper epochMapper;
    private DocumentLinkMapper linkMapper;
    private DocumentLinkCommandService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DocumentLink.class);
    }

    @BeforeEach
    void setUp() {
        epochMapper = mock(AuthorizationEpochMapper.class);
        linkMapper = mock(DocumentLinkMapper.class);
        service = new DocumentLinkCommandService(linkMapper, new AuthorizationEpochFence(epochMapper));
        when(epochMapper.selectDocumentForUpdate(DOCUMENT_ID, TENANT)).thenReturn(new EpochRow(3L, 0));
        when(epochMapper.incrementDocument(DOCUMENT_ID, TENANT)).thenReturn(1);
        beginTransaction();
    }

    @AfterEach
    void tearDown() {
        completeTransaction();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void 新しいlinkは文書行をlockしてから文書のtenantで挿入しepochを1回進める() {
        assertTrue(service.link(TENANT, DOCUMENT_ID, "ENGINEER", 7L));

        InOrder order = inOrder(epochMapper, linkMapper);
        order.verify(epochMapper).selectDocumentForUpdate(DOCUMENT_ID, TENANT);
        order.verify(linkMapper).selectByLinkKeyForUpdate(DOCUMENT_ID, "ENGINEER", 7L);
        ArgumentCaptor<DocumentLink> inserted = ArgumentCaptor.forClass(DocumentLink.class);
        order.verify(linkMapper).insert(inserted.capture());
        order.verify(epochMapper).incrementDocument(DOCUMENT_ID, TENANT);
        assertEquals(TENANT, inserted.getValue().getTenantId(), "linkのtenantは文書のtenant（F-3）");
        assertEquals(DOCUMENT_ID, inserted.getValue().getDocumentId());
        assertEquals("ENGINEER", inserted.getValue().getTargetType());
        assertEquals(7L, inserted.getValue().getTargetId());
    }

    @Test
    void 同じlinkが既にあれば挿入もepoch増分もしない() {
        when(linkMapper.selectByLinkKeyForUpdate(DOCUMENT_ID, "ENGINEER", 7L)).thenReturn(link(TENANT, 0));

        assertFalse(service.link(TENANT, DOCUMENT_ID, "ENGINEER", 7L));

        verify(epochMapper).selectDocumentForUpdate(DOCUMENT_ID, TENANT);
        verify(linkMapper, never()).insert(any(DocumentLink.class));
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void 同一transactionで2件のlinkを作ってもepochは1回だけ進む() {
        assertTrue(service.link(TENANT, DOCUMENT_ID, "ENGINEER", 7L));
        assertTrue(service.link(TENANT, DOCUMENT_ID, "EXPENSE_REQUEST", 9L));

        verify(linkMapper, times(2)).insert(any(DocumentLink.class));
        verify(epochMapper, times(1)).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 別transactionのlinkはそれぞれepochを進める() {
        service.link(TENANT, DOCUMENT_ID, "ENGINEER", 7L);
        completeTransaction();
        beginTransaction();
        service.link(TENANT, DOCUMENT_ID, "SERVICE_REQUEST", 11L);

        verify(epochMapper, times(2)).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 別tenantの同じlinkが残っていれば403で挿入もepoch増分もしない() {
        when(linkMapper.selectByLinkKeyForUpdate(DOCUMENT_ID, "ENGINEER", 7L)).thenReturn(link("default", 0));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.link(TENANT, DOCUMENT_ID, "ENGINEER", 7L));

        assertEquals(403, failure.getCode());
        assertEquals("error.tenant.mismatch", failure.getMessageKey());
        verify(linkMapper, never()).insert(any(DocumentLink.class));
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void 論理削除済みの同じlinkとは衝突として409で止める() {
        when(linkMapper.selectByLinkKeyForUpdate(DOCUMENT_ID, "ENGINEER", 7L)).thenReturn(link(TENANT, 1));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.link(TENANT, DOCUMENT_ID, "ENGINEER", 7L));

        assertEquals(409, failure.getCode());
        verify(linkMapper, never()).insert(any(DocumentLink.class));
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void 文書行が指定tenantに無ければ409で挿入しない() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.link("tenant-other", DOCUMENT_ID, "ENGINEER", 7L));

        assertEquals(409, failure.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, failure.getMessageKey());
        verify(linkMapper, never()).selectByLinkKeyForUpdate(anyLong(), anyString(), anyLong());
        verify(linkMapper, never()).insert(any(DocumentLink.class));
    }

    @Test
    void link先の指定が無ければlockを取らず400() {
        for (Runnable call : List.<Runnable>of(
                () -> service.link(TENANT, DOCUMENT_ID, null, 7L),
                () -> service.link(TENANT, DOCUMENT_ID, " ", 7L),
                () -> service.link(TENANT, DOCUMENT_ID, "ENGINEER", null))) {
            BusinessException failure = assertThrows(BusinessException.class, call::run);
            assertEquals(400, failure.getCode());
        }
        verifyNoInteractions(epochMapper, linkMapper);
    }

    @Test
    void スキルシート確認の付帯情報は列限定で更新しepochを進めない() {
        when(linkMapper.update(any(), any())).thenReturn(1);

        assertEquals(1, service.recordSkillSheetConfirmation(TENANT, DOCUMENT_ID, 7L,
                LocalDateTime.of(2026, 11, 15, 10, 0), "fp-1"));

        verify(linkMapper).update(any(), any());
        verify(linkMapper, never()).updateById(any(DocumentLink.class));
        verifyNoInteractions(epochMapper);
    }

    private static DocumentLink link(String tenantId, int deletedFlag) {
        DocumentLink link = new DocumentLink();
        link.setId(90L);
        link.setTenantId(tenantId);
        link.setDocumentId(DOCUMENT_ID);
        link.setTargetType("ENGINEER");
        link.setTargetId(7L);
        link.setDeletedFlag(deletedFlag);
        return link;
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void completeTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_COMMITTED);
    }
}
