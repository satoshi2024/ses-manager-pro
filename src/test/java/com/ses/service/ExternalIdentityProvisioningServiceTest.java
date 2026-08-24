package com.ses.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.service.impl.ExternalIdentityProvisioningServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalIdentityProvisioningServiceTest {

    @Mock
    private IdentityProviderMapper identityProviderMapper;
    @Mock
    private UserExternalIdentityMapper externalIdentityMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private AuditLogService auditLogService;

    private OidcSecurityProperties properties;
    private IdentityProvider provider;
    private SysUser target;

    @BeforeEach
    void setUp() {
        properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        provider = new IdentityProvider();
        provider.setId(10L);
        provider.setTenantId("tenant-a");
        provider.setProviderType("OIDC");
        provider.setEnabled(1);
        target = user(99L, "alice", null);
        lenient().when(identityProviderMapper.selectById(10L)).thenReturn(provider);
        lenient().when(sysUserMapper.selectById(99L)).thenReturn(target);
        lenient().when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(null);
        lenient().when(externalIdentityMapper.insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class))).thenAnswer(invocation -> {
            UserExternalIdentity link = invocation.getArgument(0);
            if (link.getId() == null) {
                link.setId(42L);
            }
            return 1;
        });
        lenient().when(externalIdentityMapper.selectByIdForUpdate(
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return externalIdentity(id, 99L);
        });
        lenient().when(externalIdentityMapper.approveIfNotApproved(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        authenticateAdmin(1L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAdmin(Long adminId) {
        SysUser admin = user(adminId, "admin", "管理者");
        LoginUser principal = new LoginUser(admin,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void 明示承認されたsubjectを登録できる() {
        ExternalIdentityProvisionRequest request = request(99L, "subject-1", "alice@example.test");
        when(sysUserMapper.selectByEmail("alice@example.test")).thenReturn(List.of());

        ExternalIdentityProvisioningServiceImpl service = service();
        service.provision(10L, request);

        ArgumentCaptor<com.ses.entity.UserExternalIdentity> captor =
                ArgumentCaptor.forClass(com.ses.entity.UserExternalIdentity.class);
        verify(externalIdentityMapper).insert(
                (com.ses.entity.UserExternalIdentity) captor.capture());
        assertEquals("tenant-a", captor.getValue().getTenantId());
        assertEquals(99L, captor.getValue().getUserId());
        assertEquals("subject-1", captor.getValue().getSubject());
        assertEquals("APPROVED", captor.getValue().getReviewStatus());
        assertEquals(1L, captor.getValue().getReviewedBy());
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getReviewedAt());
        verify(auditLogService).recordRequired(
                org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.eq("APPROVE"),
                org.mockito.ArgumentMatchers.contains("/internal/oidc-bindings/42/approve?from=NEW&to=APPROVED"),
                org.mockito.ArgumentMatchers.eq(200),
                org.mockito.ArgumentMatchers.eq("OIDC_BINDING_APPROVED"),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void 隔離済み同一subjectは管理者再承認でAPPROVEDになる() {
        UserExternalIdentity quarantined = externalIdentity(7L, 99L);
        quarantined.setReviewStatus("QUARANTINED");
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(quarantined);
        when(externalIdentityMapper.selectByIdForUpdate(7L)).thenReturn(quarantined);

        UserExternalIdentity result = service().provision(10L, request(99L, "subject-1", null));

        assertEquals("APPROVED", result.getReviewStatus());
        assertEquals(1L, result.getReviewedBy());
        org.junit.jupiter.api.Assertions.assertNotNull(result.getReviewedAt());
        assertEquals(7L, result.getId());
        verify(externalIdentityMapper).approveIfNotApproved(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any());
        verify(externalIdentityMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class));
        verify(auditLogService).recordRequired(
                org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.eq("APPROVE"),
                org.mockito.ArgumentMatchers.contains("/internal/oidc-bindings/7/approve?from=QUARANTINED&to=APPROVED"),
                org.mockito.ArgumentMatchers.eq(200),
                org.mockito.ArgumentMatchers.eq("OIDC_BINDING_APPROVED"),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void 承認監査の永続化失敗時は承認を進めない() {
        UserExternalIdentity quarantined = externalIdentity(7L, 99L);
        quarantined.setReviewStatus("QUARANTINED");
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(quarantined);
        when(externalIdentityMapper.selectByIdForUpdate(7L)).thenReturn(quarantined);
        org.mockito.Mockito.doThrow(new IllegalStateException("重要security監査を永続化できません"))
                .when(auditLogService).recordRequired(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean());

        assertThrows(IllegalStateException.class,
                () -> service().provision(10L, request(99L, "subject-1", null)));
        verify(externalIdentityMapper).approveIfNotApproved(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 既にAPPROVEDならreviewerを上書きせず追加監査もしない() {
        UserExternalIdentity approved = externalIdentity(7L, 99L);
        approved.setReviewStatus("APPROVED");
        approved.setReviewedBy(9L);
        approved.setReviewedAt(java.time.LocalDateTime.of(2026, 1, 2, 3, 4));
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(approved);
        when(externalIdentityMapper.selectByIdForUpdate(7L)).thenReturn(approved);

        UserExternalIdentity result = service().provision(10L, request(99L, "subject-1", null));

        assertEquals(9L, result.getReviewedBy());
        assertEquals(java.time.LocalDateTime.of(2026, 1, 2, 3, 4), result.getReviewedAt());
        verify(externalIdentityMapper, never()).approveIfNotApproved(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(auditLogService, never()).recordRequired(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void 監査のDuplicateKeyExceptionはbinding競合として扱わない() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("audit dup"))
                .when(auditLogService).recordRequired(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.eq("OIDC_BINDING_APPROVED"),
                        org.mockito.ArgumentMatchers.anyBoolean());

        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> service().provision(10L, request(99L, "subject-1", null)));
        verify(externalIdentityMapper).insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class));
        verify(externalIdentityMapper, never()).selectByIdForUpdate(
                org.mockito.ArgumentMatchers.anyLong());
        verify(externalIdentityMapper, never()).approveIfNotApproved(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 管理者userIdを解決できない場合は承認を拒否する() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().provision(10L, request(99L, "subject-1", null)));

        assertEquals(403, exception.getCode());
        assertEquals("error.identity.reviewerRequired", exception.getMessageKey());
        verify(externalIdentityMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class));
    }

    @Test
    void HRは外部identity承認を実行できない() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("hr", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_HR"))));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().provision(10L, request(99L, "subject-1", null)));

        assertEquals(403, exception.getCode());
        verify(externalIdentityMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(com.ses.entity.UserExternalIdentity.class));
    }

    @Test
    void マネージャーは外部identity承認を実行できない() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_マネージャー"))));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().provision(10L, request(99L, "subject-1", null)));

        assertEquals(403, exception.getCode());
        verify(externalIdentityMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(com.ses.entity.UserExternalIdentity.class));
    }

    @Test
    void emailが別ユーザーに一致する場合は承認を拒否する() {
        ExternalIdentityProvisionRequest request = request(99L, "subject-1", "bob@example.test");
        when(sysUserMapper.selectByEmail("bob@example.test")).thenReturn(List.of(user(100L, "bob", "営業")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().provision(10L, request));

        assertEquals(409, exception.getCode());
        verify(externalIdentityMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(com.ses.entity.UserExternalIdentity.class));
    }

    @Test
    void emailが複数ユーザーに一致する場合は承認を拒否する() {
        ExternalIdentityProvisionRequest request = request(99L, "subject-1", "shared@example.test");
        when(sysUserMapper.selectByEmail("shared@example.test")).thenReturn(
                List.of(user(100L, "bob", "営業"), user(101L, "carol", "HR")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().provision(10L, request));

        assertEquals(409, exception.getCode());
    }

    @Test
    void 並行insertで同一targetが先行確定した場合は冪等成功にする() {
        ExternalIdentityProvisionRequest request = request(99L, "subject-1", null);
        UserExternalIdentity concurrent = externalIdentity(1L, 99L);
        concurrent.setReviewStatus("QUARANTINED");
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(null);
        when(externalIdentityMapper.selectByTenantProviderAndSubjectForUpdate("tenant-a", 10L, "subject-1"))
                .thenReturn(concurrent);
        when(externalIdentityMapper.selectByIdForUpdate(1L)).thenReturn(concurrent);
        org.mockito.Mockito.when(externalIdentityMapper.insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("concurrent"));

        UserExternalIdentity result = service().provision(10L, request);

        assertEquals(99L, result.getUserId());
        assertEquals("APPROVED", result.getReviewStatus());
    }

    @Test
    void 並行insertで別targetが先行確定した場合は409にする() {
        ExternalIdentityProvisionRequest request = request(99L, "subject-1", null);
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(null);
        when(externalIdentityMapper.selectByTenantProviderAndSubjectForUpdate("tenant-a", 10L, "subject-1"))
                .thenReturn(externalIdentity(1L, 100L));
        org.mockito.Mockito.when(externalIdentityMapper.insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("concurrent"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().provision(10L, request));

        assertEquals(409, exception.getCode());
    }

    @Test
    void localLogin無効時はbreakGlass以外を拒否する() {
        properties.setLocalLoginEnabled(false);
        properties.getBreakGlassUsernames().add("break-glass");
        com.ses.service.security.BreakGlassService breakGlassService =
                org.mockito.Mockito.mock(com.ses.service.security.BreakGlassService.class);
        com.ses.config.CustomUserDetailsService service =
                new com.ses.config.CustomUserDetailsService(sysUserMapper, properties, breakGlassService);
        when(sysUserMapper.selectByUsername("alice")).thenReturn(user(99L, "alice", "営業"));

        assertThrows(org.springframework.security.authentication.DisabledException.class,
                () -> service.loadUserByUsername("alice"));

        when(sysUserMapper.selectByUsername("break-glass")).thenReturn(
                user(98L, "break-glass", "管理者"));
        when(breakGlassService.isLoginAllowed("break-glass")).thenReturn(true);
        org.springframework.security.core.userdetails.UserDetails breakGlass =
                service.loadUserByUsername("break-glass");
        assertEquals("break-glass", breakGlass.getUsername());

        when(breakGlassService.isLoginAllowed("break-glass")).thenReturn(false);
        assertThrows(org.springframework.security.authentication.DisabledException.class,
                () -> service.loadUserByUsername("break-glass"));
    }

    private ExternalIdentityProvisioningServiceImpl service() {
        return new ExternalIdentityProvisioningServiceImpl(identityProviderMapper,
                externalIdentityMapper, sysUserMapper, properties, auditLogService);
    }

    private ExternalIdentityProvisionRequest request(Long userId, String subject, String email) {
        ExternalIdentityProvisionRequest request = new ExternalIdentityProvisionRequest();
        request.setUserId(userId);
        request.setSubject(subject);
        request.setEmailSnapshot(email);
        return request;
    }

    private UserExternalIdentity externalIdentity(Long id, Long userId) {
        UserExternalIdentity identity = new UserExternalIdentity();
        identity.setId(id);
        identity.setUserId(userId);
        identity.setTenantId("tenant-a");
        identity.setProviderId(10L);
        identity.setSubject("subject-1");
        return identity;
    }

    private SysUser user(Long id, String username, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }
}
