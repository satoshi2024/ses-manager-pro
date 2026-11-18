package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.epoch.ActorShareLock;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 要員アカウント紐付けの業務ルールと、NF01 F2（細粒度epoch計画 §4.2・§3.4、W3-15 review P3-8）の lock順
 * （3 S{連携ユーザー} → 7 X{要員} → 9）・lock下の読み直し・engineer epochの増分を検証する単体テスト。
 */
@ExtendWith(MockitoExtension.class)
class EngineerAccountLinkServiceImplTest {

    private static final AuthorizationEpochAnchor.Engineer ENGINEER_ANCHOR =
            AuthorizationEpochAnchor.engineer("default", 1L);

    @Mock private EngineerAccountLinkMapper linkMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private ActorShareLock actorShareLock;
    @Mock private AuthorizationEpochFence authorizationEpochFence;
    @Mock private com.ses.service.security.ScopeChangeInvalidator scopeChangeInvalidator;

    @InjectMocks
    private EngineerAccountLinkServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        // @InjectMocksはコンストラクタ注入が成功すると、@Autowired(required=false)の任意フィールドへは
        // 注入しないため明示的に設定する。
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scopeChangeInvalidator", scopeChangeInvalidator);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    private SysUser user(String role) {
        return user(role, 1);
    }

    private SysUser user(String role, Integer status) {
        SysUser u = new SysUser();
        u.setId(3L);
        u.setRole(role);
        u.setStatus(status);
        u.setTenantId("default");
        return u;
    }

    private com.ses.entity.Engineer engineer(Long id) {
        com.ses.entity.Engineer e = new com.ses.entity.Engineer();
        e.setId(id);
        e.setTenantId("default");
        return e;
    }

    private EngineerAccountLink existingLink(Long id, Long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setId(id);
        link.setTenantId("default");
        link.setEngineerId(1L);
        link.setSysUserId(userId);
        return link;
    }

    @Test
    void link_success() {
        when(actorShareLock.lockActive(3L, "default")).thenReturn(user("要員"));
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(engineer(1L));
        when(linkMapper.selectByUserIdAndTenant(3L, "default")).thenReturn(null);
        when(linkMapper.selectByEngineerIdAndTenant(1L, "default")).thenReturn(null);
        when(linkMapper.insert(any(EngineerAccountLink.class))).thenReturn(1);

        EngineerAccountLink link = service.link(1L, 3L, 9L);
        assertEquals(1L, link.getEngineerId());
        assertEquals(3L, link.getSysUserId());
        // 要員↔ログインアカウントの紐付けは組織scope解決に影響するため、DataScope世代を進める
        // 必要がある（第十四次Review P1-3）。
        verify(scopeChangeInvalidator).invalidate();
        // 3 S{連携ユーザー} → 7 X{要員} → 9 → engineer epoch +1（tenantはlockした要員行の値）
        InOrder order = inOrder(actorShareLock, engineerMapper, linkMapper, authorizationEpochFence);
        order.verify(actorShareLock).lockActive(3L, "default");
        order.verify(engineerMapper).selectByIdForUpdateForTenant(1L, "default");
        order.verify(linkMapper).insert(any(EngineerAccountLink.class));
        order.verify(authorizationEpochFence).advance(ENGINEER_ANCHOR);
    }

    @Test
    void link_roleNotEngineerRejected() {
        when(actorShareLock.lockActive(3L, "default")).thenReturn(user("営業"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.roleNotEngineer"));
        verify(engineerMapper, never()).selectByIdForUpdateForTenant(any(), any());
    }

    @Test
    void link_lock下で削除済みと読めた利用者は紐付けず要員もlockしない() {
        // 並行する deleteUser がcommitした後は、共有lock下の読み直しで行が見えない（W3-15 review P3-8）。
        when(actorShareLock.lockActive(3L, "default")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.userNotFound"));
        verify(engineerMapper, never()).selectByIdForUpdateForTenant(any(), any());
        verify(linkMapper, never()).insert(any(EngineerAccountLink.class));
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void link_lock下で無効化済みと読めた利用者は紐付けない() {
        when(actorShareLock.lockActive(3L, "default")).thenReturn(user("要員", 0));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.userDisabled"));
        verify(linkMapper, never()).insert(any(EngineerAccountLink.class));
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void link_userAlreadyLinkedRejected() {
        when(actorShareLock.lockActive(3L, "default")).thenReturn(user("要員"));
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(engineer(1L));
        when(linkMapper.selectByUserIdAndTenant(3L, "default")).thenReturn(new EngineerAccountLink());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.userAlreadyLinked"));
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void link_engineerAlreadyLinkedRejected() {
        when(actorShareLock.lockActive(3L, "default")).thenReturn(user("要員"));
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(engineer(1L));
        when(linkMapper.selectByUserIdAndTenant(3L, "default")).thenReturn(null);
        when(linkMapper.selectByEngineerIdAndTenant(1L, "default")).thenReturn(new EngineerAccountLink());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.engineerAlreadyLinked"));
    }

    @Test
    void findEngineerIdByUserId() {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(7L);
        when(linkMapper.selectByUserIdAndTenant(3L, "default")).thenReturn(link);
        assertEquals(7L, service.findEngineerIdByUserId(3L));
        when(linkMapper.selectByUserIdAndTenant(4L, "default")).thenReturn(null);
        assertNull(service.findEngineerIdByUserId(4L));
    }

    @Test
    void unlink_deletesWhenPresent() {
        EngineerAccountLink link = existingLink(11L, 3L);
        when(linkMapper.selectByEngineerIdAndLinkTenant(1L, "default")).thenReturn(link);
        when(engineerMapper.selectScopeAnchorForUpdate(1L)).thenReturn(engineer(1L));
        when(linkMapper.deleteByIdForTenant(11L, "default")).thenReturn(1);
        service.unlinkByEngineerId(1L);
        verify(linkMapper).deleteByIdForTenant(11L, "default");
        verify(scopeChangeInvalidator).invalidate();
        // discovery → 3 S{連携ユーザー} → 7 X{要員（削除済みも含む）} → 読み直し → 9 → engineer epoch +1
        InOrder order = inOrder(linkMapper, actorShareLock, engineerMapper, authorizationEpochFence);
        order.verify(linkMapper).selectByEngineerIdAndLinkTenant(1L, "default");
        order.verify(actorShareLock).lockActive(3L, "default");
        order.verify(engineerMapper).selectScopeAnchorForUpdate(1L);
        order.verify(linkMapper).selectByEngineerIdAndLinkTenant(1L, "default");
        order.verify(linkMapper).deleteByIdForTenant(11L, "default");
        order.verify(authorizationEpochFence).advance(ENGINEER_ANCHOR);
    }

    @Test
    void unlink_lock後に別の利用者の紐付けへ替わっていたら409で消さない() {
        when(linkMapper.selectByEngineerIdAndLinkTenant(1L, "default"))
                .thenReturn(existingLink(11L, 3L), existingLink(12L, 4L));
        when(engineerMapper.selectScopeAnchorForUpdate(1L)).thenReturn(engineer(1L));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.unlinkByEngineerId(1L));
        assertEquals(409, ex.getCode());
        verify(linkMapper, never()).deleteByIdForTenant(anyLong(), any());
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void unlink_紐付けが無ければlockも増分もしない() {
        when(linkMapper.selectByEngineerIdAndLinkTenant(1L, "default")).thenReturn(null);
        service.unlinkByEngineerId(1L);
        verifyNoInteractions(actorShareLock, engineerMapper, authorizationEpochFence);
        verify(scopeChangeInvalidator, never()).invalidate();
    }
}
