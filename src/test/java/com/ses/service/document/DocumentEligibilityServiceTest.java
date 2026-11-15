package com.ses.service.document;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
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

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * U9（NF01細粒度epoch計画 §2.2 document行・§4.3・§6.2）: {@link DocumentEligibilityService} の各writerが、
 * 文書行を先にX lockしてから書き、利用可否が変わった時だけ文書自身のtenantでepochを1回進めることを、実際の
 * {@link AuthorizationEpochFence} とmapper mockで確かめる（transactionは同期機構を直接操作して再現する）。
 */
class DocumentEligibilityServiceTest {

    private static final String TENANT = "tenant-b";
    private static final long DOCUMENT_ID = 20L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private AuthorizationEpochMapper epochMapper;
    private DocumentMapper documentMapper;
    private DocumentDisposalRequestMapper disposalMapper;
    private DocumentEligibilityService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Document.class);
        TableInfoHelper.initTableInfo(assistant, DocumentDisposalRequest.class);
    }

    @BeforeEach
    void setUp() {
        epochMapper = mock(AuthorizationEpochMapper.class);
        documentMapper = mock(DocumentMapper.class);
        disposalMapper = mock(DocumentDisposalRequestMapper.class);
        service = new DocumentEligibilityService(documentMapper, disposalMapper, new AuthorizationEpochFence(epochMapper));
        when(epochMapper.selectDocumentForUpdate(anyLong(), anyString())).thenReturn(new EpochRow(5L, 0));
        when(epochMapper.incrementDocument(anyLong(), anyString())).thenReturn(1);
        beginTransaction();
    }

    @AfterEach
    void tearDown() {
        completeTransaction();
        TransactionSynchronizationManager.clear();
        AccountingTenantContextHolder.clear();
    }

    // ===== 法的hold =====

    @Test
    void 法的holdの設定は文書自身のtenantでlockしてからCASし1回進める() {
        when(documentMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.changeLegalHold(document("CONFIRMED", 0), true));

        InOrder order = inOrder(epochMapper, documentMapper);
        order.verify(epochMapper).selectDocumentForUpdate(DOCUMENT_ID, TENANT);
        order.verify(documentMapper).update(any(), any());
        order.verify(epochMapper).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 法的holdの解除も1回進める() {
        when(documentMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.changeLegalHold(document("CONFIRMED", 1), false));

        verify(epochMapper, times(1)).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 同じ値の法的holdは更新もepoch増分もしない() {
        assertFalse(service.changeLegalHold(document("CONFIRMED", 1), true));
        assertFalse(service.changeLegalHold(document("CONFIRMED", 0), false));

        verify(documentMapper, never()).update(any(), any());
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void 法的holdのCASが0件なら409でepochを進めない() {
        when(documentMapper.update(any(), any())).thenReturn(0);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.changeLegalHold(document("CONFIRMED", 0), true));

        assertEquals(409, failure.getCode());
        assertEquals("error.document.optimisticLock", failure.getMessageKey());
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void epochが上限なら業務更新ごと409にする() {
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(epochMapper.incrementDocument(DOCUMENT_ID, TENANT)).thenReturn(0);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.changeLegalHold(document("CONFIRMED", 0), true));

        assertEquals(409, failure.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, failure.getMessageKey());
    }

    // ===== 廃棄申請 =====

    @Test
    void 廃棄申請はlock後に読み直した行で条件を確かめ1回進める() {
        when(documentMapper.selectOne(any())).thenReturn(disposable());

        DocumentDisposalRequest request = service.requestDisposal(TENANT, DOCUMENT_ID, 3L, "理由");

        assertEquals("PENDING", request.getStatus());
        assertEquals(3L, request.getRequestedBy());
        InOrder order = inOrder(epochMapper, documentMapper, disposalMapper);
        order.verify(epochMapper).selectDocumentForUpdate(DOCUMENT_ID, TENANT);
        order.verify(documentMapper).selectOne(any());
        order.verify(disposalMapper).insert(any(DocumentDisposalRequest.class));
        order.verify(epochMapper).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void lock後に法的holdが付いていれば廃棄申請を登録しない() {
        Document held = disposable();
        held.setLegalHoldFlag(1);
        when(documentMapper.selectOne(any())).thenReturn(held);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.requestDisposal(TENANT, DOCUMENT_ID, 3L, "理由"));

        assertEquals("error.document.legalHoldActive", failure.getMessageKey());
        verify(disposalMapper, never()).insert(any(DocumentDisposalRequest.class));
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void 同一transactionで法的hold解除と廃棄申請を行っても増分は1回() {
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(documentMapper.selectOne(any())).thenReturn(disposable());

        assertTrue(service.changeLegalHold(document("CONFIRMED", 1), false));
        service.requestDisposal(TENANT, DOCUMENT_ID, 3L, "理由");

        verify(epochMapper, times(1)).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 廃棄申請の承認と却下はlock後のCASが成功した時だけ進める() {
        when(disposalMapper.update(any(), any())).thenReturn(1);
        service.approveDisposal(TENANT, DOCUMENT_ID, 70L, 2L);
        completeTransaction();
        beginTransaction();
        service.rejectDisposal(TENANT, DOCUMENT_ID, 71L, 2L, "却下理由: 不要");

        verify(epochMapper, times(2)).selectDocumentForUpdate(DOCUMENT_ID, TENANT);
        verify(epochMapper, times(2)).incrementDocument(DOCUMENT_ID, TENANT);

        when(disposalMapper.update(any(), any())).thenReturn(0);
        completeTransaction();
        beginTransaction();
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.approveDisposal(TENANT, DOCUMENT_ID, 72L, 2L));
        assertEquals("error.document.disposalConcurrentUpdate", failure.getMessageKey());
        verify(epochMapper, times(2)).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 廃棄執行はDISPOSEDにして進め既にDISPOSEDなら何もしない() {
        when(documentMapper.selectOne(any())).thenReturn(document("CONFIRMED", 0));
        when(disposalMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.executeDisposal(TENANT, DOCUMENT_ID, 80L));
        verify(documentMapper).update(any(), any());
        verify(epochMapper, times(1)).incrementDocument(DOCUMENT_ID, TENANT);

        completeTransaction();
        beginTransaction();
        when(documentMapper.selectOne(any())).thenReturn(document("DISPOSED", 0));
        assertFalse(service.executeDisposal(TENANT, DOCUMENT_ID, 80L));
        verify(epochMapper, times(1)).incrementDocument(DOCUMENT_ID, TENANT);
    }

    @Test
    void 廃棄執行後のstorage削除失敗の記録はepochを進めない() {
        service.markDisposalStorageFailed(80L, "Storage物理削除失敗");

        verify(disposalMapper).update(any(), any());
        verifyNoInteractions(epochMapper);
    }

    // ===== 状態の内側の遷移 =====

    @Test
    void 確定とAMENDEDは文書行をlockするがepochを進めない() {
        when(documentMapper.update(any(), any())).thenReturn(1);

        service.confirm(document("DRAFT", 0), LocalDate.of(2036, 1, 1));
        assertTrue(service.markAmended(document("CONFIRMED", 0)));
        assertFalse(service.markAmended(document("DRAFT", 0)));

        verify(epochMapper, times(3)).selectDocumentForUpdate(DOCUMENT_ID, TENANT);
        verify(documentMapper, times(2)).update(any(), any());
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    @Test
    void 確定のCASが0件なら409() {
        when(documentMapper.update(any(), any())).thenReturn(0);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.confirm(document("DRAFT", 0), null));

        assertEquals("error.document.optimisticLock", failure.getMessageKey());
    }

    // ===== 保存期限job =====

    @Test
    void 保存期限の廃棄候補はtenant_contextなしで文書自身のtenantを使い1回進める() {
        AccountingTenantContextHolder.clear();
        when(documentMapper.selectOne(any())).thenReturn(expired("tenant-c", 31L));
        when(disposalMapper.selectCount(any())).thenReturn(0L);

        assertTrue(service.registerRetentionDisposalCandidate("tenant-c", 31L, TODAY));

        assertNull(AccountingTenantContextHolder.getExplicitTenantId(), "tenant contextを設定しない");
        ArgumentCaptor<DocumentDisposalRequest> inserted = ArgumentCaptor.forClass(DocumentDisposalRequest.class);
        InOrder order = inOrder(epochMapper, documentMapper, disposalMapper);
        order.verify(epochMapper).selectDocumentForUpdate(31L, "tenant-c");
        order.verify(documentMapper).selectOne(any());
        order.verify(disposalMapper).insert(inserted.capture());
        order.verify(epochMapper).incrementDocument(31L, "tenant-c");
        assertEquals(-1L, inserted.getValue().getRequestedBy(), "申請者はシステム");
        assertEquals("PENDING", inserted.getValue().getStatus());
    }

    @Test
    void 保存期限の廃棄候補はlock後に条件を満たさなければ登録しない() {
        Document held = expired(TENANT, DOCUMENT_ID);
        held.setLegalHoldFlag(1);
        Document notYet = expired(TENANT, DOCUMENT_ID);
        notYet.setRetentionUntil(TODAY.plusDays(1));
        Document amended = expired(TENANT, DOCUMENT_ID);
        amended.setStatus("AMENDED");
        for (Document current : java.util.Arrays.asList(null, held, notYet, amended)) {
            when(documentMapper.selectOne(any())).thenReturn(current);
            assertFalse(service.registerRetentionDisposalCandidate(TENANT, DOCUMENT_ID, TODAY));
        }
        when(documentMapper.selectOne(any())).thenReturn(expired(TENANT, DOCUMENT_ID));
        when(disposalMapper.selectCount(any())).thenReturn(1L);
        assertFalse(service.registerRetentionDisposalCandidate(TENANT, DOCUMENT_ID, TODAY), "承認待ちの申請が既にある");

        verify(disposalMapper, never()).insert(any(DocumentDisposalRequest.class));
        verify(epochMapper, never()).incrementDocument(anyLong(), anyString());
    }

    private static Document document(String status, int hold) {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setTenantId(TENANT);
        document.setStatus(status);
        document.setLegalHoldFlag(hold);
        document.setVersion(4L);
        return document;
    }

    private static Document disposable() {
        Document document = document("CONFIRMED", 0);
        document.setRetentionUntil(TODAY.minusDays(1));
        return document;
    }

    private static Document expired(String tenantId, long id) {
        Document document = disposable();
        document.setTenantId(tenantId);
        document.setId(id);
        return document;
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
