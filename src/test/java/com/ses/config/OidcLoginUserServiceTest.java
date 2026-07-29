package com.ses.config;

import com.ses.common.util.SecurityUtils;
import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OidcLoginUserServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private IdentityProviderMapper identityProviderMapper;
    @Mock
    private UserExternalIdentityMapper externalIdentityMapper;
    @Mock
    private org.springframework.security.oauth2.client.userinfo.OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    private OidcSecurityProperties properties;
    private IdentityProvider provider;
    private OidcUserRequest request;

    @BeforeEach
    void setUp() {
        properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        provider = new IdentityProvider();
        provider.setId(10L);
        provider.setTenantId("tenant-a");
        provider.setProviderType("OIDC");
        provider.setIssuerUri("https://issuer.example.test");
        provider.setEnabled(1);
        request = new OidcUserRequest(clientRegistration(), accessToken(), idToken());
        when(identityProviderMapper.selectEnabledByTenantAndIssuer("tenant-a", provider.getIssuerUri()))
                .thenReturn(provider);
    }

    @Test
    void knownSubjectは内部ユーザーと既存roleへ解決される() {
        OidcUser oidcUser = oidcUser("subject-1", provider.getIssuerUri());
        UserExternalIdentity link = link(99L, 10L);
        SysUser user = user(99L, "alice", "営業");
        when(delegate.loadUser(request)).thenReturn(oidcUser);
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(link);
        when(sysUserMapper.selectById(99L)).thenReturn(user);

        OidcLoginUser result = (OidcLoginUser) service().loadUser(request);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(result, null, result.getAuthorities()));
        try {
            assertEquals(99L, SecurityUtils.currentUserId());
            assertEquals("alice", SecurityUtils.currentUsername());
            assertEquals("営業", SecurityUtils.currentRole());
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals(99L, result.getSysUser().getId());
        assertEquals("alice", result.getName());
        assertEquals("ROLE_営業", result.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void unknownSubjectはemailが一致しても自動linkしない() {
        OidcUser oidcUser = oidcUser("unknown", provider.getIssuerUri());
        when(delegate.loadUser(request)).thenReturn(oidcUser);
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "unknown"))
                .thenReturn(null);

        OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                () -> service().loadUser(request));

        assertEquals("oidc_identity_not_linked", exception.getError().getErrorCode());
    }

    @Test
    void issuer不一致はprovider解決前に拒否される() {
        when(identityProviderMapper.selectEnabledByTenantAndIssuer("tenant-a", provider.getIssuerUri()))
                .thenReturn(null);

        OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                () -> service().loadUser(request));

        assertEquals("oidc_provider_not_allowed", exception.getError().getErrorCode());
    }

    @Test
    void idpTimeoutは外部例外を漏らさず認証エラーへ変換する() {
        when(delegate.loadUser(request)).thenThrow(new ResourceAccessException("timeout"));

        OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                () -> service().loadUser(request));

        assertEquals("oidc_provider_unavailable", exception.getError().getErrorCode());
    }

    @Test
    void oidc有効時はMfaAssuranceClaim不足を拒否する() {
        properties.setEnabled(true);
        OidcUser oidcUser = mock(OidcUser.class);
        when(delegate.loadUser(request)).thenReturn(oidcUser);

        OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                () -> service().loadUser(request));

        assertEquals("oidc_mfa_assurance_missing", exception.getError().getErrorCode());
    }

    @Test
    void 許可済みMfaAmrは受理する() {
        properties.setEnabled(true);
        OidcUser oidcUser = oidcUser("subject-1", provider.getIssuerUri());
        when(oidcUser.getClaimAsStringList("amr")).thenReturn(List.of("pwd", "mfa"));
        when(delegate.loadUser(request)).thenReturn(oidcUser);
        when(externalIdentityMapper.selectByTenantProviderAndSubject("tenant-a", 10L, "subject-1"))
                .thenReturn(link(99L, 10L));
        when(sysUserMapper.selectById(99L)).thenReturn(user(99L, "alice", "営業"));

        OidcLoginUser result = (OidcLoginUser) service().loadUser(request);

        assertEquals(99L, result.getSysUser().getId());
    }

    @Test
    void tokenIssuerClaim欠落は拒否する() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(delegate.loadUser(request)).thenReturn(oidcUser);

        OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                () -> service().loadUser(request));

        assertEquals("oidc_issuer_mismatch", exception.getError().getErrorCode());
    }

    private OidcLoginUserService service() {
        return new OidcLoginUserService(sysUserMapper, identityProviderMapper,
                externalIdentityMapper, properties, delegate);
    }

    private OidcUser oidcUser(String subject, String issuer) {
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn(subject);
        when(user.getClaimAsString("iss")).thenReturn(issuer);
        return user;
    }

    private UserExternalIdentity link(Long userId, Long providerId) {
        UserExternalIdentity link = new UserExternalIdentity();
        link.setTenantId("tenant-a");
        link.setUserId(userId);
        link.setProviderId(providerId);
        link.setSubject("subject-1");
        return link;
    }

    private SysUser user(Long id, String username, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    private ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId("enterprise")
                .clientId("client-id")
                .clientSecret("secret")
                .authorizationUri("https://issuer.example.test/authorize")
                .tokenUri("https://issuer.example.test/token")
                .jwkSetUri("https://issuer.example.test/jwks")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .issuerUri("https://issuer.example.test")
                .userNameAttributeName("sub")
                .build();
    }

    private OidcIdToken idToken() {
        Instant issuedAt = Instant.now().minusSeconds(1);
        return new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(300),
                Map.of("iss", "https://issuer.example.test", "sub", "subject-1"));
    }

    private OAuth2AccessToken accessToken() {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token",
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(300));
    }
}
