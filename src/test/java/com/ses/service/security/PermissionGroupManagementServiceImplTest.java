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
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private com.ses.mapper.PermissionGroupActionMapper permissionGroupActionMapper;
    @Mock private com.ses.service.NotificationService notificationService;

    private PermissionGroupManagementServiceImpl service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        service = new PermissionGroupManagementServiceImpl(permissionGroupMapper,
                userPermissionGroupMapper, sysUserMapper, auditLogMapper,
                persistentSessionService, properties, permissionGroupActionMapper, notificationService);
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

    // ---------------------------------------------------------------- NF07 W-5（§6.7）

    @Test
    void privacy_adminを管理者role以外へ割り当てると書込み前に400で拒否する() {
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target(7L, "営業"));
        when(permissionGroupMapper.selectList(any())).thenReturn(List.of(group(6L, "privacy-admin")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replaceAssignments(7L, Set.of(6L), authentication));

        assertEquals(400, error.getCode());
        verify(userPermissionGroupMapper, never()).deleteAllForUser(any(), any());
        verify(auditLogMapper, never()).insert(any(AuditLog.class));
        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void privacy_adminとprivacyを拒否するgroupの同時割当は409で拒否する() {
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target(7L, "管理者"));
        when(permissionGroupMapper.selectList(any()))
                .thenReturn(List.of(group(6L, "privacy-admin"), group(1L, "role-admin")));
        when(permissionGroupActionMapper.selectCount(any())).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replaceAssignments(7L, Set.of(6L, 1L), authentication));

        assertEquals(409, error.getCode());
        verify(userPermissionGroupMapper, never()).deleteAllForUser(any(), any());
    }

    @Test
    void privacy_adminの割当はgroup_key付きのrequired監査を書き既存のprivacy_adminへ通知する() {
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target(7L, "管理者"));
        when(permissionGroupMapper.selectList(any()))
                .thenReturn(List.of(group(6L, "privacy-admin")), List.of(group(1L, "role-admin")));
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 1L)));
        when(userPermissionGroupMapper.selectActiveUserIdsByGroupKey("tenant-a", "privacy-admin"))
                .thenReturn(List.of(3L, 4L));
        when(userPermissionGroupMapper.insert(any(UserPermissionGroup.class))).thenReturn(1);
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);

        service.replaceAssignments(7L, Set.of(6L), authentication);

        // 既存の privacy-admin は書込み前に確定する（割り当てられた本人を「既存」に含めない）
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userPermissionGroupMapper);
        order.verify(userPermissionGroupMapper).selectActiveUserIdsByGroupKey("tenant-a", "privacy-admin");
        order.verify(userPermissionGroupMapper).deleteAllForUser("tenant-a", 7L);
        verify(persistentSessionService).revokeAllForUser(7L, "PERMISSION_CHANGED");
        ArgumentCaptor<AuditLog> audits = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, org.mockito.Mockito.times(2)).insert(audits.capture());
        assertEquals("PERMISSION_GROUP_CHANGED", audits.getAllValues().get(0).getApplicationCode());
        AuditLog privacyAudit = audits.getAllValues().get(1);
        assertEquals("PRIVACY_ADMIN_GROUP_CHANGED", privacyAudit.getApplicationCode());
        assertEquals("SYS_USER", privacyAudit.getReferenceType());
        assertEquals(7L, privacyAudit.getReferenceId());
        assertEquals("groups=role-admin", privacyAudit.getBeforeState());
        assertEquals("groups=privacy-admin", privacyAudit.getAfterState());
        for (Long recipient : List.of(3L, 4L)) {
            verify(notificationService).publishToUser(eq(recipient), eq("PRIVACY_ADMIN_CHANGED"),
                    eq("プライバシー管理者の割当が追加されました"), any(), eq("/audit-log"), any(), eq("audit-log"));
        }
    }

    @Test
    void role変更による既定groupへの再割当でprivacy_adminが外れる場合も監査と通知を行う() {
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target(7L, "営業"));
        when(permissionGroupMapper.selectOne(any())).thenReturn(group(2L, "role-sales"));
        when(permissionGroupMapper.selectList(any()))
                .thenReturn(List.of(group(2L, "role-sales")), List.of(group(6L, "privacy-admin")));
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 6L)));
        when(userPermissionGroupMapper.selectActiveUserIdsByGroupKey("tenant-a", "privacy-admin"))
                .thenReturn(List.of(3L, 7L));
        when(userPermissionGroupMapper.insert(any(UserPermissionGroup.class))).thenReturn(1);
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);

        service.replaceAssignments(7L, Set.of(), authentication);

        ArgumentCaptor<AuditLog> audits = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, org.mockito.Mockito.times(2)).insert(audits.capture());
        assertEquals("groups=privacy-admin", audits.getAllValues().get(1).getBeforeState());
        assertEquals("groups=role-sales", audits.getAllValues().get(1).getAfterState());
        verify(notificationService).publishToUser(eq(3L), eq("PRIVACY_ADMIN_CHANGED"),
                eq("プライバシー管理者の割当が解除されました"), any(), eq("/audit-log"), any(), eq("audit-log"));
    }

    @Test
    void privacy_adminの監査を書けなければ割当ごと失敗し通知しない() {
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target(7L, "管理者"));
        when(permissionGroupMapper.selectList(any()))
                .thenReturn(List.of(group(6L, "privacy-admin")), List.of(group(1L, "role-admin")));
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 1L)));
        when(userPermissionGroupMapper.selectActiveUserIdsByGroupKey("tenant-a", "privacy-admin"))
                .thenReturn(List.of(3L));
        when(userPermissionGroupMapper.insert(any(UserPermissionGroup.class))).thenReturn(1);
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1, 0);

        assertThrows(BusinessException.class, () -> service.replaceAssignments(7L, Set.of(6L), authentication));

        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void privacy_adminの所属が変わらない割当では追加の監査と通知をしない() {
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target(7L, "管理者"));
        when(permissionGroupMapper.selectList(any()))
                .thenReturn(List.of(group(6L, "privacy-admin")), List.of(group(6L, "privacy-admin")));
        when(userPermissionGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 6L)));
        when(userPermissionGroupMapper.insert(any(UserPermissionGroup.class))).thenReturn(1);
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);

        service.replaceAssignments(7L, Set.of(6L), authentication);

        verify(auditLogMapper).insert(any(AuditLog.class));
        verify(userPermissionGroupMapper, never()).selectActiveUserIdsByGroupKey(any(), any());
        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any(), any());
    }

    private static SysUser target(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private static PermissionGroup group(Long id, String key) {
        PermissionGroup group = new PermissionGroup();
        group.setId(id);
        group.setTenantId("tenant-a");
        group.setGroupKey(key);
        group.setEnabled(1);
        return group;
    }

    private static UserPermissionGroup assignment(Long userId, Long groupId) {
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setTenantId("tenant-a");
        assignment.setUserId(userId);
        assignment.setGroupId(groupId);
        return assignment;
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
