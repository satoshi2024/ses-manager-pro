package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * U2（NF01細粒度epoch計画 §2.4・§6.2）: epoch fenceとreaderの規則をDB無しで固定する。
 * no-opでは増やさない、同一transactionの重複は1回、上限・行欠落は409、transaction外の呼出しは拒否する。
 * 実transactionの有無は {@link TransactionSynchronizationManager} を直接操作して再現する。
 */
class AuthorizationEpochFenceTest {

    private static final AuthorizationEpochAnchor.Engineer ENGINEER = AuthorizationEpochAnchor.engineer("tenant-a", 7);

    private AuthorizationEpochMapper mapper;
    private AuthorizationEpochFence fence;
    private AuthorizationEpochReader reader;

    @BeforeEach
    void setUp() {
        mapper = mock(AuthorizationEpochMapper.class);
        fence = new AuthorizationEpochFence(mapper);
        reader = new AuthorizationEpochReader(mapper);
        beginTransaction();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            completeTransaction();
        }
        TransactionSynchronizationManager.clear();
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(),
                "transaction完了後にepoch状態の結び付けが残らないこと");
    }

    @Test
    void 述語の値が変わらない更新ではepochを進めずmapperも呼ばない() {
        assertFalse(fence.advanceIfChanged(ENGINEER, Set.of(1L, 2L), Set.of(2L, 1L)));
        assertFalse(fence.advanceIfChanged(AuthorizationEpochAnchor.global(), null, null));
        verifyNoInteractions(mapper);
    }

    @Test
    void 同じtransactionで同じanchorは1回だけ進め別のtransactionでは再び進める() {
        when(mapper.incrementEngineer(7, "tenant-a")).thenReturn(1);

        assertTrue(fence.advance(ENGINEER));
        assertFalse(fence.advance(AuthorizationEpochAnchor.engineer("tenant-a", 7)));
        assertFalse(fence.advanceIfChanged(ENGINEER, "before", "after"));
        verify(mapper, times(1)).incrementEngineer(7, "tenant-a");

        completeTransaction();
        beginTransaction();
        assertTrue(fence.advance(ENGINEER), "前のtransactionの増分済み集合を持ち越さないこと");
        verify(mapper, times(2)).incrementEngineer(7, "tenant-a");
    }

    @Test
    void tenantやIDが異なるanchorは別々に進める() {
        when(mapper.incrementActor(anyLong(), anyString())).thenReturn(1);

        assertTrue(fence.advance(AuthorizationEpochAnchor.actor("tenant-a", 3)));
        assertTrue(fence.advance(AuthorizationEpochAnchor.actor("tenant-b", 3)));
        assertTrue(fence.advance(AuthorizationEpochAnchor.actor("tenant-a", 4)));
        verify(mapper).incrementActor(3, "tenant-a");
        verify(mapper).incrementActor(3, "tenant-b");
        verify(mapper).incrementActor(4, "tenant-a");
    }

    @Test
    void 上限または行欠落で増分できなければ409で止め増分済みとして記録しない() {
        when(mapper.incrementEngineer(7, "tenant-a")).thenReturn(0);
        when(mapper.incrementGlobal()).thenReturn(0);
        when(mapper.incrementTaskGeneration(30, 20, "tenant-a")).thenReturn(0);

        assertEpochUnavailable(() -> fence.advance(ENGINEER));
        assertEpochUnavailable(() -> fence.advance(ENGINEER));
        assertEpochUnavailable(() -> fence.advance(AuthorizationEpochAnchor.global()));
        assertEpochUnavailable(() -> fence.advance(AuthorizationEpochAnchor.taskGeneration("tenant-a", 20, 30)));
        verify(mapper, times(2)).incrementEngineer(7, "tenant-a");
    }

    @Test
    void anchor行のX_lockは現在値を返し行が無ければ409にする() {
        when(mapper.selectEngineerForUpdate(7, "tenant-a")).thenReturn(new EpochRow(4L, 0));
        when(mapper.selectGlobalForUpdate()).thenReturn(null);
        when(mapper.selectActorForUpdate(3, "tenant-a")).thenReturn(new EpochRow(2L, 1));

        assertEquals(4L, fence.lockForUpdate(ENGINEER));
        assertEpochUnavailable(() -> fence.lockForUpdate(AuthorizationEpochAnchor.global()));
        assertEquals(2L, fence.lockForUpdate(AuthorizationEpochAnchor.actor("tenant-a", 3)),
                "削除するwriterは論理削除済みの行もlockできること");
    }

    @Test
    void 複数anchorはlock順に並べて重複を除いて進める() {
        when(mapper.incrementGlobal()).thenReturn(1);
        when(mapper.incrementActor(anyLong(), anyString())).thenReturn(1);
        when(mapper.incrementOrganization(anyLong())).thenReturn(1);
        when(mapper.incrementEngineer(anyLong(), anyString())).thenReturn(1);
        when(mapper.incrementDocument(anyLong(), anyString())).thenReturn(1);

        int advanced = fence.advanceAll(List.of(
                AuthorizationEpochAnchor.document("tenant-a", 50),
                AuthorizationEpochAnchor.organization(12),
                ENGINEER,
                AuthorizationEpochAnchor.organization(5),
                AuthorizationEpochAnchor.actor("tenant-a", 3),
                AuthorizationEpochAnchor.global(),
                AuthorizationEpochAnchor.organization(12)));

        assertEquals(6, advanced);
        InOrder order = inOrder(mapper);
        order.verify(mapper).incrementGlobal();
        order.verify(mapper).incrementActor(3, "tenant-a");
        order.verify(mapper).incrementOrganization(5);
        order.verify(mapper).incrementOrganization(12);
        order.verify(mapper).incrementEngineer(7, "tenant-a");
        order.verify(mapper).incrementDocument(50, "tenant-a");
        assertEquals(0, fence.advanceAll(List.of(ENGINEER, AuthorizationEpochAnchor.global())));
    }

    @Test
    void REQUIRES_NEWで外側をsuspendした内側transactionは外側の増分済み集合を共有しない() {
        when(mapper.incrementEngineer(7, "tenant-a")).thenReturn(1);
        assertTrue(fence.advance(ENGINEER));

        // AbstractPlatformTransactionManager.suspend と同じ手順（同期のsuspend → 同期を外す）
        List<TransactionSynchronization> outer = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        outer.forEach(TransactionSynchronization::suspend);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        beginTransaction();
        assertTrue(fence.advance(ENGINEER), "内側transactionでは別に進めること");
        completeTransaction();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        outer.forEach(TransactionSynchronizationManager::registerSynchronization);
        outer.forEach(TransactionSynchronization::resume);
        assertFalse(fence.advance(ENGINEER), "resume後の外側transactionは自分の増分済み集合を使うこと");
        verify(mapper, times(2)).incrementEngineer(7, "tenant-a");
    }

    @Test
    void transaction外の呼出しはmapperを呼ばずに拒否する() {
        completeTransaction();

        assertThrows(IllegalStateException.class, () -> fence.advance(ENGINEER));
        assertThrows(IllegalStateException.class, () -> fence.advanceIfChanged(ENGINEER, 1, 1));
        assertThrows(IllegalStateException.class, () -> fence.advanceAll(List.of(ENGINEER)));
        assertThrows(IllegalStateException.class, () -> fence.lockForUpdate(ENGINEER));
        assertThrows(IllegalStateException.class, () -> reader.readGlobal());
        assertThrows(IllegalStateException.class, () -> reader.readEngineer("tenant-a", 7));
        verifyNoInteractions(mapper);
    }

    @Test
    void 公開メソッドはすべてMANDATORYで宣言されproxy経由のtransaction外呼出しは拒否される() throws Exception {
        for (Class<?> type : List.of(AuthorizationEpochFence.class, AuthorizationEpochReader.class,
                LifecycleLockExecutor.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                Transactional transactional = method.getAnnotation(Transactional.class);
                assertTrue(transactional != null && transactional.propagation() == Propagation.MANDATORY,
                        type.getSimpleName() + "#" + method.getName() + " は Propagation.MANDATORY であること");
            }
        }

        completeTransaction();
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(mock(DataSource.class)), new AnnotationTransactionAttributeSource());
        AuthorizationEpochFence proxiedFence = proxy(fence, interceptor);
        AuthorizationEpochReader proxiedReader = proxy(reader, interceptor);
        LifecycleLockExecutor proxiedExecutor = proxy(new LifecycleLockExecutor(
                mock(com.ses.mapper.LifecycleLockMapper.class), mapper), interceptor);

        assertThrows(IllegalTransactionStateException.class, () -> proxiedFence.advance(ENGINEER));
        assertThrows(IllegalTransactionStateException.class, () -> proxiedReader.readGlobal());
        assertThrows(IllegalTransactionStateException.class, () -> proxiedExecutor.acquire(
                LifecycleLockPlan.from(List.of(LifecycleLockPlan.LockKey.global(LifecycleLockPlan.Mode.SHARED)))));
        verifyNoInteractions(mapper);
    }

    @Test
    void readerは行の欠落と論理削除を409にし組織をID昇順に1回ずつ読む() {
        when(mapper.selectGlobalForShare()).thenReturn(new EpochRow(9L, 0));
        when(mapper.selectActorForShare(3, "tenant-a")).thenReturn(new EpochRow(2L, 1));
        when(mapper.selectEngineerForShare(7, "tenant-a")).thenReturn(null);
        when(mapper.selectDocumentForShare(50, "tenant-a")).thenReturn(new EpochRow(null, 0));
        when(mapper.selectOrganizationForShare(5)).thenReturn(new EpochRow(1L, 0));
        when(mapper.selectOrganizationForShare(12)).thenReturn(new EpochRow(3L, 0));
        when(mapper.selectTaskGenerationForUpdate(30, 20, "tenant-a")).thenReturn(new EpochRow(4L, 0));

        assertEquals(9L, reader.readGlobal());
        assertEpochUnavailable(() -> reader.readActor("tenant-a", 3));
        assertEpochUnavailable(() -> reader.readEngineer("tenant-a", 7));
        assertEpochUnavailable(() -> reader.readDocument("tenant-a", 50));
        assertEquals(4L, reader.readTaskGeneration("tenant-a", 20, 30));

        SortedMap<Long, Long> organizations = reader.readOrganizations(List.of(12L, 5L, 12L));
        assertEquals(Map.of(5L, 1L, 12L, 3L), organizations);
        assertEquals(List.of(5L, 12L), new ArrayList<>(organizations.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> organizations.put(1L, 1L));
        InOrder order = inOrder(mapper);
        order.verify(mapper).selectOrganizationForShare(5);
        order.verify(mapper).selectOrganizationForShare(12);
        verify(mapper, times(1)).selectOrganizationForShare(12);
        verify(mapper, never()).selectOrganizationForUpdate(anyLong());
    }

    @Test
    void anchorは正のIDと空でないtenantだけを受け付ける() {
        assertThrows(IllegalArgumentException.class, () -> AuthorizationEpochAnchor.engineer(" ", 1));
        assertThrows(NullPointerException.class, () -> AuthorizationEpochAnchor.actor(null, 1));
        assertThrows(IllegalArgumentException.class, () -> AuthorizationEpochAnchor.document("tenant-a", 0));
        assertThrows(IllegalArgumentException.class, () -> AuthorizationEpochAnchor.organization(-1));
        assertThrows(IllegalArgumentException.class, () -> AuthorizationEpochAnchor.taskGeneration("tenant-a", 0, 1));
        assertEquals(LifecycleLockPlan.Rank.LIFECYCLE_TASK,
                AuthorizationEpochAnchor.taskGeneration("tenant-a", 1, 2).rank());
        assertEquals("", AuthorizationEpochAnchor.organization(1).partition());
    }

    private static void assertEpochUnavailable(Runnable call) {
        BusinessException failure = assertThrows(BusinessException.class, call::run);
        assertEquals(409, failure.getCode());
        assertEquals("error.authorization.epochUnavailable", failure.getMessageKey());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, TransactionInterceptor interceptor) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void completeTransaction() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK);
    }
}
