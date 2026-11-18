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

    // ---------------------------------------------------------------- NF07 W-5（§6.2）

    @Test
    void privacy系actionは管理者role以外ならgroupを見ずに拒否する() {
        for (String role : List.of("営業", "HR", "マネージャー", "要員")) {
            authenticate(7L, role);
            assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy.view"), role);
            assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(),
                    "privacy.dsar.export"), role);
        }
        verifyNoInteractions(userPermissionGroupMapper, permissionGroupActionMapper, permissionGroupMapper);
    }

    @Test
    void privacy系actionは管理者でも短絡許可せずgroup評価で拒否優先になる() {
        authenticate(7L, "管理者");
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setGroupId(1L);
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment));
        PermissionGroup group = new PermissionGroup();
        group.setId(1L);
        group.setEnabled(1);
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group));
        // role-admin 相当: privacy.* の deny に当たる
        when(permissionGroupActionMapper.selectCount(any())).thenReturn(1L);

        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy.policy.view"));
        org.mockito.Mockito.verify(permissionGroupActionMapper).selectCount(any());
    }

    @Test
    void privacy系actionは拒否が無く明示許可がある管理者だけ許可する() {
        authenticate(7L, "管理者");
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setGroupId(6L);
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment));
        PermissionGroup group = new PermissionGroup();
        group.setId(6L);
        group.setEnabled(1);
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group));
        // 1回目: 拒否照合（0件）、2回目: 許可照合（privacy-admin の privacy.* が1件）
        when(permissionGroupActionMapper.selectCount(any())).thenReturn(0L, 1L);

        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy.policy.view"));
        org.mockito.Mockito.verify(permissionGroupActionMapper, org.mockito.Mockito.times(2)).selectCount(any());
    }

    @Test
    void group未割当の管理者はlegacy_fallbackでもprivacyを使えず他の管理者actionは維持する() {
        authenticate(7L, "管理者");
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of());

        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy.view"));
        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy.unknown.view"));
        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "dashboard.view"));
    }

    @Test
    void privacy系actionの権限DB障害は拒否する() {
        authenticate(7L, "管理者");
        when(userPermissionGroupMapper.selectList(any())).thenThrow(new IllegalStateException("db unavailable"));

        assertFalse(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy.view"));
    }

    @Test
    void privacy接頭辞は区切りまで一致した場合だけ適用し別resourceを巻き込まない() {
        authenticate(7L, "管理者");
        // privacy-x は privacy ではないので管理者の短絡許可がそのまま効く（DBを見ない）
        assertTrue(service().isAllowed(SecurityContextHolder.getContext().getAuthentication(), "privacy-x.view"));
        verifyNoInteractions(userPermissionGroupMapper, permissionGroupActionMapper, permissionGroupMapper);
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
