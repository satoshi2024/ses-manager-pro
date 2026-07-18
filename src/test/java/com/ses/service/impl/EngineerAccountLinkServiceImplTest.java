package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngineerAccountLinkServiceImplTest {

    @Mock private EngineerAccountLinkMapper linkMapper;
    @Mock private SysUserMapper sysUserMapper;

    @InjectMocks
    private EngineerAccountLinkServiceImpl service;

    private SysUser user(String role) {
        SysUser u = new SysUser();
        u.setId(3L);
        u.setRole(role);
        return u;
    }

    @Test
    void link_success() {
        when(sysUserMapper.selectById(3L)).thenReturn(user("要員"));
        when(linkMapper.selectByUserId(3L)).thenReturn(null);
        when(linkMapper.selectByEngineerId(1L)).thenReturn(null);
        when(linkMapper.insert(any(EngineerAccountLink.class))).thenReturn(1);

        EngineerAccountLink link = service.link(1L, 3L, 9L);
        assertEquals(1L, link.getEngineerId());
        assertEquals(3L, link.getSysUserId());
    }

    @Test
    void link_roleNotEngineerRejected() {
        when(sysUserMapper.selectById(3L)).thenReturn(user("営業"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.roleNotEngineer"));
    }

    @Test
    void link_userAlreadyLinkedRejected() {
        when(sysUserMapper.selectById(3L)).thenReturn(user("要員"));
        when(linkMapper.selectByUserId(3L)).thenReturn(new EngineerAccountLink());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.userAlreadyLinked"));
    }

    @Test
    void link_engineerAlreadyLinkedRejected() {
        when(sysUserMapper.selectById(3L)).thenReturn(user("要員"));
        when(linkMapper.selectByUserId(3L)).thenReturn(null);
        when(linkMapper.selectByEngineerId(1L)).thenReturn(new EngineerAccountLink());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(1L, 3L, 9L));
        assertTrue(ex.getMessage().contains("error.engineerAccount.engineerAlreadyLinked"));
    }

    @Test
    void findEngineerIdByUserId() {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(7L);
        when(linkMapper.selectByUserId(3L)).thenReturn(link);
        assertEquals(7L, service.findEngineerIdByUserId(3L));
        when(linkMapper.selectByUserId(4L)).thenReturn(null);
        assertNull(service.findEngineerIdByUserId(4L));
    }

    @Test
    void unlink_deletesWhenPresent() {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setId(11L);
        when(linkMapper.selectByEngineerId(1L)).thenReturn(link);
        when(linkMapper.deleteById(11L)).thenReturn(1);
        service.unlinkByEngineerId(1L);
        verify(linkMapper).deleteById(11L);
    }
}
