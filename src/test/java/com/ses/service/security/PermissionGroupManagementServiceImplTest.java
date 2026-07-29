package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.PermissionGroup;
import com.ses.entity.SysUser;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.impl.PermissionGroupManagementServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionGroupManagementServiceImplTest {

    @Mock private PermissionGroupMapper permissionGroupMapper;
    @Mock private UserPermissionGroupMapper userPermissionGroupMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private PersistentSessionService persistentSessionService;

    private PermissionGroupManagementServiceImpl service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        service = new PermissionGroupManagementServiceImpl(permissionGroupMapper,
                userPermissionGroupMapper, sysUserMapper, auditLogMapper,
                persistentSessionService, properties);
        authentication = authenticate(1L, "admin", "管理者");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 自分自身のgroup変更は書込み前に拒否する() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replaceAssignments(1L, Set.of(10L), authentication));

        assertEquals(403, error.getCode());
        verify(userPermissionGroupMapper, never()).deleteAllForUser(any(), any());
    }

    @Test
    void group変更は対象lock_監査_session失効を同じ実行境界で行う() {
        SysUser target = new SysUser();
        target.setId(7L);
        target.setRole("営業");
        PermissionGroup group = new PermissionGroup();
        group.setId(10L);
        group.setTenantId("tenant-a");
        group.setEnabled(1);
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target);
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group));
        when(userPermissionGroupMapper.insert(any(UserPermissionGroup.class))).thenReturn(1);
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);

        service.replaceAssignments(7L, Set.of(10L), authentication);

        verify(userPermissionGroupMapper).deleteAllForUser("tenant-a", 7L);
        verify(persistentSessionService).revokeAllForUser(7L, "PERMISSION_CHANGED");
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(audit.capture());
        assertEquals("PERMISSION_GROUP_CHANGED", audit.getValue().getApplicationCode());
        assertEquals("/api/permission-groups/users/7", audit.getValue().getUri());
    }

    private Authentication authenticate(Long id, String username, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        LoginUser principal = new LoginUser(user, List.of());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }
}
