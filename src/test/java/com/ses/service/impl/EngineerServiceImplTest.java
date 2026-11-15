package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.OrganizationShareLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 要員削除時の割当解放の順序（review-fixes G3）と法人fail-closed、NF01 F2（細粒度epoch計画 §4.2・§11 F-5）の
 * lock順・engineer epochの増分条件を検証する単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EngineerServiceImplTest {

    private static final AuthorizationEpochAnchor.Engineer ENGINEER_ANCHOR =
            AuthorizationEpochAnchor.engineer("default", 1L);

    @Mock
    private ContractMapper contractMapper;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private EngineerSalesService engineerSalesService;
    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    @Mock
    private com.ses.mapper.SysUserMapper sysUserMapper;
    @Mock
    private com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;
    @Mock
    private com.ses.service.security.ScopeChangeInvalidator scopeChangeInvalidator;
    @Mock
    private LegalEntityContextService legalEntityContextService;
    @Mock
    private AuthorizationEpochFence authorizationEpochFence;
    @Mock
    private OrganizationShareLock organizationShareLock;

    private EngineerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EngineerServiceImpl(contractMapper, proposalMapper, engineerSalesService,
                engineerAccountLinkService, sysUserMapper, tenantOwnershipResolver, authorizationEpochFence,
                organizationShareLock);
        ReflectionTestUtils.setField(service, "baseMapper", engineerMapper);
        ReflectionTestUtils.setField(service, "scopeChangeInvalidator", scopeChangeInvalidator);
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        when(contractMapper.selectCount(any())).thenReturn(0L);
        when(contractMapper.selectCountForTenant(any(), any())).thenReturn(0L);
        when(proposalMapper.selectCount(any())).thenReturn(0L);
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void removeById_削除成功時のみ割当を解除する() {
        Engineer current = engineerWithLe(1L, 10L, 0);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(current);
        when(engineerMapper.deleteByIdForTenant(1L, "default", 0)).thenReturn(1);

        assertTrue(service.removeById(1L));
        verify(legalEntityContextService, atLeastOnce()).assertCurrent(10L);
        verify(engineerSalesService, times(1)).releaseAllByEngineerId(1L);
        verify(authorizationEpochFence).advance(ENGINEER_ANCHOR);
    }

    @Test
    void removeById_削除失敗時は割当を解除しない() {
        Engineer current = engineerWithLe(1L, 10L, 0);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(current);
        when(engineerMapper.deleteByIdForTenant(1L, "default", 0)).thenReturn(0);

        assertFalse(service.removeById(1L));
        verify(engineerSalesService, never()).releaseAllByEngineerId(any());
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void removeById_legalEntityNullは403() {
        Engineer current = engineerWithLe(1L, null, 0);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.removeById(1L));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
        verify(engineerMapper, never()).deleteByIdForTenant(any(), any(), any());
    }

    @Test
    void removeById_serviceNullは403() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", null);
        Engineer current = engineerWithLe(1L, 10L, 0);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.removeById(1L, 0));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void removeById_連携ユーザーを要員より先にlockしstatus列だけを0にしてactorとengineerを進める() {
        Engineer current = engineerWithLe(1L, 10L, 0);
        EngineerAccountLink link = link(3L);
        SysUser linked = linkedUser(3L, 1);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);
        when(engineerAccountLinkService.findByEngineerId(1L)).thenReturn(link);
        when(sysUserMapper.selectByIdAndTenantForUpdate(3L, "default")).thenReturn(linked);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(current);
        when(engineerMapper.deleteByIdForTenant(1L, "default", 0)).thenReturn(1);
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.removeById(1L, 0));

        // 計画 §4.2: discovery → 3 X{連携ユーザー} → 7 X{要員} → 削除 → 増分 → 9 → 10（子行もrank順）→ 連携ユーザーのstatus
        InOrder order = inOrder(engineerAccountLinkService, sysUserMapper, engineerMapper, authorizationEpochFence,
                engineerSalesService);
        order.verify(engineerAccountLinkService).findByEngineerId(1L);
        order.verify(sysUserMapper).selectByIdAndTenantForUpdate(3L, "default");
        order.verify(engineerMapper).selectByIdForUpdateForTenant(1L, "default");
        order.verify(engineerMapper).deleteByIdForTenant(1L, "default", 0);
        order.verify(authorizationEpochFence).advance(ENGINEER_ANCHOR);
        order.verify(engineerAccountLinkService).unlinkByEngineerId(1L);
        order.verify(engineerSalesService).releaseAllByEngineerId(1L);
        order.verify(sysUserMapper).update(isNull(), any());
        order.verify(authorizationEpochFence).advanceIfChanged(AuthorizationEpochAnchor.actor("default", 3L), 1, 0);

        // F-5: entityの全体更新ではなく status列だけの列限定更新
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<SysUser>> wrapper = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(sysUserMapper).update(isNull(), wrapper.capture());
        String sqlSet = wrapper.getValue().getSqlSet();
        assertTrue(sqlSet.startsWith("status=") && !sqlSet.contains(","), "status列だけを書く: " + sqlSet);
    }

    @Test
    void removeById_lock後に紐付けが別の利用者へ替わっていたら409で削除しない() {
        Engineer current = engineerWithLe(1L, 10L, 0);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);
        when(engineerAccountLinkService.findByEngineerId(1L)).thenReturn(link(3L), link(4L));
        when(sysUserMapper.selectByIdAndTenantForUpdate(3L, "default")).thenReturn(linkedUser(3L, 1));
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(current);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.removeById(1L, 0));
        assertEquals(409, ex.getCode());
        verify(engineerMapper, never()).deleteByIdForTenant(any(), any(), any());
        verify(sysUserMapper, never()).update(any(), any());
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void updateWithStatusGuard_所属組織変更時はscope世代を進める() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L)
                .tenantId("default").build();
        old.setId(1L);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(old);
        when(engineerMapper.updateByIdForTenant(any(Engineer.class), eq("default"), eq(0))).thenReturn(1);

        Engineer changed = Engineer.builder().fullName("要員A").organizationId(200L).legalEntityId(10L).build();
        changed.setId(1L);
        changed.setVersion(0);
        when(engineerMapper.selectOne(any())).thenReturn(null);

        service.updateWithStatusGuard(changed);

        verify(legalEntityContextService).assertCurrent(10L);
        verify(scopeChangeInvalidator, times(1)).invalidate();
        // W3-16b: 新しい所属組織（rank 6）を要員行（rank 7）より先に共有lockし、外部キー検査で 7 → 6 の待ちを作らない。
        // E-2: 要員行のX lockを取り、更新の後で engineer epoch を1回だけ進める
        InOrder order = inOrder(organizationShareLock, engineerMapper, authorizationEpochFence);
        order.verify(organizationShareLock).lockReferenced(200L);
        order.verify(engineerMapper).selectByIdForUpdateForTenant(1L, "default");
        order.verify(engineerMapper).updateByIdForTenant(any(Engineer.class), eq("default"), eq(0));
        order.verify(authorizationEpochFence).advance(ENGINEER_ANCHOR);
        verify(authorizationEpochFence, times(1)).advance(any());
        verify(tenantOwnershipResolver, never()).selectEngineer(any(), any());
    }

    @Test
    void updateWithStatusGuard_所属組織が変わらなければscope世代を進めない() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L)
                .tenantId("default").build();
        old.setId(1L);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(old);
        when(engineerMapper.updateByIdForTenant(any(Engineer.class), eq("default"), eq(0))).thenReturn(1);

        Engineer unchanged = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L).build();
        unchanged.setId(1L);
        unchanged.setVersion(0);

        service.updateWithStatusGuard(unchanged);

        verify(scopeChangeInvalidator, never()).invalidate();
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void updateWithStatusGuard_legalEntityNullは403() {
        Engineer old = Engineer.builder().fullName("要員A").legalEntityId(null).tenantId("default").build();
        old.setId(1L);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(old);

        Engineer patch = Engineer.builder().fullName("要員A").build();
        patch.setId(1L);
        patch.setVersion(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateWithStatusGuard(patch));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
        verify(engineerMapper, never()).updateByIdForTenant(any(), any(), any());
    }

    @Test
    void updateWithStatusGuard_serviceNullは403() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", null);
        Engineer old = Engineer.builder().fullName("要員A").legalEntityId(10L).tenantId("default").build();
        old.setId(1L);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(old);

        Engineer patch = Engineer.builder().fullName("要員A").build();
        patch.setId(1L);
        patch.setVersion(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateWithStatusGuard(patch));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void updateById_要員行をlockして所属の変化でだけ進めentityのtenantはDBの値に固定する() {
        Engineer current = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L)
                .tenantId("default").build();
        current.setId(1L);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(current);
        when(engineerMapper.updateById(any(Engineer.class))).thenReturn(1);

        Engineer sameOrganization = Engineer.builder().status("Bench").organizationId(100L).tenantId("other").build();
        sameOrganization.setId(1L);
        assertTrue(service.updateById(sameOrganization));
        assertEquals("default", sameOrganization.getTenantId(), "tenantの補正は修復経路だけが行う");
        verify(authorizationEpochFence, never()).advance(any());

        Engineer moved = Engineer.builder().organizationId(200L).build();
        moved.setId(1L);
        assertTrue(service.updateById(moved));
        InOrder order = inOrder(organizationShareLock, engineerMapper, authorizationEpochFence);
        order.verify(organizationShareLock).lockReferenced(200L);
        order.verify(engineerMapper).selectByIdForUpdateForTenant(1L, "default");
        order.verify(engineerMapper).updateById(moved);
        order.verify(authorizationEpochFence).advance(ENGINEER_ANCHOR);
    }

    @Test
    void 所属組織を指定しない更新は組織行をlockせず要員行だけを取る() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L)
                .tenantId("default").build();
        old.setId(1L);
        when(engineerMapper.selectByIdForUpdateForTenant(1L, "default")).thenReturn(old);
        when(engineerMapper.updateByIdForTenant(any(Engineer.class), eq("default"), eq(0))).thenReturn(1);
        Engineer patch = Engineer.builder().fullName("要員A改").legalEntityId(10L).build();
        patch.setId(1L);
        patch.setVersion(0);

        service.updateWithStatusGuard(patch);

        // lockReferenced(null) は何もしない（OrganizationShareLock の契約）。呼出しは引数nullの1回だけ。
        verify(organizationShareLock).lockReferenced(isNull());
        verify(organizationShareLock, never()).lockReferenced(eq(100L));
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void save_insert後に自行をlockし新規行のepochは進めない() {
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(10L);
        when(engineerMapper.insert(any(Engineer.class))).thenAnswer(invocation -> {
            Engineer inserted = invocation.getArgument(0);
            inserted.setId(5L);
            return 1;
        });

        assertTrue(service.save(Engineer.builder().fullName("新規要員").build()));

        InOrder order = inOrder(engineerMapper);
        order.verify(engineerMapper).insert(any(Engineer.class));
        order.verify(engineerMapper).selectScopeAnchorForUpdate(5L);
        verify(authorizationEpochFence, never()).advance(any());
    }

    private static Engineer engineerWithLe(Long id, Long legalEntityId, int version) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setTenantId("default");
        e.setLegalEntityId(legalEntityId);
        e.setVersion(version);
        return e;
    }

    private static EngineerAccountLink link(Long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setId(11L);
        link.setTenantId("default");
        link.setEngineerId(1L);
        link.setSysUserId(userId);
        return link;
    }

    private static SysUser linkedUser(Long id, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId("default");
        user.setRole("要員");
        user.setStatus(status);
        return user;
    }
}
