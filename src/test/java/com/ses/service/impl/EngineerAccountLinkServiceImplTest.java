package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngineerAccountLinkServiceImplTest {

    @Mock private EngineerAccountLinkMapper linkMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TenantOwnershipResolver tenantOwnershipResolver;
    @Mock private com.ses.service.security.ScopeChangeInvalidator scopeChangeInvalidator;

    @InjectMocks
    private EngineerAccountLinkServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        // @InjectMocksはコンストラクタ注入(linkMapper, sysUserMapper)が成功すると、
        // @Autowired(required=false)の任意フィールドへは注入しないため明示的に設定する。
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scopeChangeInvalidator", scopeChangeInvalidator);
        lenient().when(tenantOwnershipResolver.selectEngineer("default", 1L)).thenReturn(engineer(1L));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    private SysUser user(String role) {
        SysUser u = new SysUser();
        u.setId(3L);
        u.setRole(role);
        u.setTenantId("default");
        return u;
    }

    private com.ses.entity.Engineer engineer(Long id) {
        com.ses.entity.Engineer e = new com.ses.entity.Engineer();
        e.setId(id);
        e.setTenantId("default");
        return e;
    }

    @Test
    void link_success() {
        when(sysUserMapper.selectByIdAndTenant(3L, "default")).thenReturn(user("要員"));
        when(linkMapper.selectByUserIdAndTenant(3L, "default")).thenReturn(null);
        when(linkMapper.selectByEngineerIdAndTenant(1L, "default")).thenReturn(null);
        when(linkMapper.insert(any(EngineerAccountLink.class))).thenReturn(1);

        EngineerAccountLink link = service.link(1L, 3L, 9L);
        assertEquals(1L, link.getEngineerId());
        assertEquals(3L, link.getSysUserId());
        // 要員↔ログインアカウントの紐付けは組織scope解決に影響するため、DataScope世代を進める
        // 必要がある（第十四次Review P1-3）。
        verify(scopeChangeInvalidator).invalidate();
    }

    @Test
    void link_roleNotEngineerRejected() {
        when(sysUserMapper.selectByIdAndTenant(3L, "default")).thenReturn(user("営業"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.roleNotEngineer"));
    }

    @Test
    void link_userAlreadyLinkedRejected() {
        when(sysUserMapper.selectByIdAndTenant(3L, "default")).thenReturn(user("要員"));
        when(linkMapper.selectByUserIdAndTenant(3L, "default")).thenReturn(new EngineerAccountLink());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.userAlreadyLinked"));
    }

    @Test
    void link_engineerAlreadyLinkedRejected() {
        when(sysUserMapper.selectByIdAndTenant(3L, "default")).thenReturn(user("要員"));
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
        EngineerAccountLink link = new EngineerAccountLink();
        link.setId(11L);
        when(linkMapper.selectByEngineerIdAndTenant(1L, "default")).thenReturn(link);
        when(linkMapper.deleteByIdForTenant(11L, "default")).thenReturn(1);
        service.unlinkByEngineerId(1L);
        verify(linkMapper).deleteByIdForTenant(11L, "default");
        verify(scopeChangeInvalidator).invalidate();
    }
}
