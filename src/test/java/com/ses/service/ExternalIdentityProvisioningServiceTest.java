package com.ses.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.service.impl.ExternalIdentityProvisioningServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class))).thenReturn(1);
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
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(null, externalIdentity(1L, 99L));
        org.mockito.Mockito.when(externalIdentityMapper.insert(
                org.mockito.ArgumentMatchers.any(UserExternalIdentity.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("concurrent"));

        UserExternalIdentity result = service().provision(10L, request);

        assertEquals(99L, result.getUserId());
    }

    @Test
    void 並行insertで別targetが先行確定した場合は409にする() {
        ExternalIdentityProvisionRequest request = request(99L, "subject-1", null);
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(null, externalIdentity(1L, 100L));
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
                externalIdentityMapper, sysUserMapper, properties);
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
