package com.ses.service.security;

import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.PermissionGroup;
import com.ses.entity.SysUser;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.impl.AuthorizationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceImplTest {

    @Mock
    private UserPermissionGroupMapper userPermissionGroupMapper;
    @Mock
    private PermissionGroupActionMapper permissionGroupActionMapper;
    @Mock
    private PermissionGroupMapper permissionGroupMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 未割当ユーザーはlegacyRoleへfallbackする() {
        authenticate(7L, "営業");
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of());

        AuthorizationServiceImpl service = service();

        assertTrue(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "proposal.view"));
        assertFalse(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "payroll.view"));
        assertFalse(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "mfa.reset"));
        assertFalse(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "future-sensitive.view"));
    }

    @Test
    void 管理者専用actionはpermissionGroup判定より先に拒否する() {
        authenticate(7L, "営業");

        assertFalse(service().isAllowed(
                SecurityContextHolder.getContext().getAuthentication(), "mfa.reset"));
        assertFalse(service().isAllowed(
                SecurityContextHolder.getContext().getAuthentication(), "identity-provider.create"));
        assertFalse(service().isAllowed(
                SecurityContextHolder.getContext().getAuthentication(), "system-config.view"));
        verifyNoInteractions(userPermissionGroupMapper, permissionGroupActionMapper, permissionGroupMapper);
    }

    @Test
    void 未割当の営業は既存roleMenu相当のcandidateProfileAutocompleteを維持する() {
        authenticate(7L, "営業");
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of());

        AuthorizationServiceImpl service = service();
        assertTrue(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "candidate.create"));
        assertTrue(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "profile.update"));
        assertTrue(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "autocomplete.view"));
    }

    @Test
    void group割当済みユーザーはlegacyRoleとの和集合にならない() {
        authenticate(7L, "営業");
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setGroupId(10L);
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment));
        PermissionGroup group = new PermissionGroup();
        group.setId(10L);
        group.setEnabled(1);
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group));
        when(permissionGroupActionMapper.selectCount(any())).thenReturn(0L);

        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "proposal.view"));
    }

    @Test
    void 管理者はaction設定による自己lockoutを受けない() {
        authenticate(1L, "管理者");
        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "user.delete"));
        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "identity-provider.create"));
        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "system-config.view"));
        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "mfa.reset"));
    }

    @Test
    void resourceWildcardは同一resourceのactionだけ許可する() {
        authenticate(7L, "営業");
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setGroupId(10L);
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment));
        PermissionGroup group = new PermissionGroup();
        group.setId(10L);
        group.setEnabled(1);
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group));
        // 1 actionあたり「拒否 → 許可」の2回問い合わせる。
        // invoice.view: 拒否0・許可1 → 許可。organization.view: 拒否0・許可0 → 拒否。
        when(permissionGroupActionMapper.selectCount(any())).thenReturn(0L, 1L, 0L, 0L);

        AuthorizationServiceImpl service = service();
        assertTrue(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "invoice.view"));
        assertFalse(service.isAllowed(SecurityContextHolder.getContext().getAuthentication(), "organization.view"));
    }

    @Test
    void 明示拒否はbaseline許可より優先される() {
        authenticate(7L, "営業");
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setGroupId(10L);
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment));
        PermissionGroup group = new PermissionGroup();
        group.setId(10L);
        group.setEnabled(1);
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group));
        // 拒否1件がヒットした時点で許可問い合わせへ進まない。
        when(permissionGroupActionMapper.selectCount(any())).thenReturn(1L);

        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "payroll.view"));
    }

    @Test
    void permissionDB障害時はlegacyRoleへfailOpenしない() {
        authenticate(7L, "営業");
        when(userPermissionGroupMapper.selectList(any())).thenThrow(new IllegalStateException("db unavailable"));

        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "proposal.view"));
    }

    private AuthorizationServiceImpl service() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("default");
        return new AuthorizationServiceImpl(userPermissionGroupMapper, permissionGroupActionMapper,
                permissionGroupMapper, properties);
    }

    private void authenticate(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(user, List.of()), null, List.of()));
    }
}
