package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.*;

/**
 * 要員削除時の割当解放の順序（review-fixes G3）と法人fail-closedを検証する単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EngineerServiceImplTest {

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

    private EngineerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EngineerServiceImpl(contractMapper, proposalMapper, engineerSalesService,
                engineerAccountLinkService, sysUserMapper, tenantOwnershipResolver);
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
        when(engineerMapper.deleteByIdForTenant(1L, "default", 0)).thenReturn(1);

        assertTrue(service.removeById(1L));
        verify(legalEntityContextService, atLeastOnce()).assertCurrent(10L);
        verify(engineerSalesService, times(1)).releaseAllByEngineerId(1L);
    }

    @Test
    void removeById_削除失敗時は割当を解除しない() {
        Engineer current = engineerWithLe(1L, 10L, 0);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(current);
        when(engineerMapper.deleteByIdForTenant(1L, "default", 0)).thenReturn(0);

        assertFalse(service.removeById(1L));
        verify(engineerSalesService, never()).releaseAllByEngineerId(any());
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
    void updateWithStatusGuard_所属組織変更時はscope世代を進める() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L).build();
        old.setId(1L);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(old);
        when(engineerMapper.updateByIdForTenant(any(Engineer.class), eq("default"), eq(0))).thenReturn(1);

        Engineer changed = Engineer.builder().fullName("要員A").organizationId(200L).legalEntityId(10L).build();
        changed.setId(1L);
        changed.setVersion(0);
        when(engineerMapper.selectOne(any())).thenReturn(null);

        service.updateWithStatusGuard(changed);

        verify(legalEntityContextService).assertCurrent(10L);
        verify(scopeChangeInvalidator, times(1)).invalidate();
    }

    @Test
    void updateWithStatusGuard_所属組織が変わらなければscope世代を進めない() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L).build();
        old.setId(1L);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(old);
        when(engineerMapper.updateByIdForTenant(any(Engineer.class), eq("default"), eq(0))).thenReturn(1);

        Engineer unchanged = Engineer.builder().fullName("要員A").organizationId(100L).legalEntityId(10L).build();
        unchanged.setId(1L);
        unchanged.setVersion(0);

        service.updateWithStatusGuard(unchanged);

        verify(scopeChangeInvalidator, never()).invalidate();
    }

    @Test
    void updateWithStatusGuard_legalEntityNullは403() {
        Engineer old = Engineer.builder().fullName("要員A").legalEntityId(null).build();
        old.setId(1L);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(old);

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
        Engineer old = Engineer.builder().fullName("要員A").legalEntityId(10L).build();
        old.setId(1L);
        when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(old);

        Engineer patch = Engineer.builder().fullName("要員A").build();
        patch.setId(1L);
        patch.setVersion(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateWithStatusGuard(patch));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    private static Engineer engineerWithLe(Long id, Long legalEntityId, int version) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setLegalEntityId(legalEntityId);
        e.setVersion(version);
        return e;
    }
}
