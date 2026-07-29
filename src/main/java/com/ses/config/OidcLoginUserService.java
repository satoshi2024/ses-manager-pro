package com.ses.config;

import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;

/**
 * OIDC login時のprovider検証、subject解決、内部role付与を行う。
 * emailだけではlinkせず、明示的に登録済みの外部identityだけを許可する。
 */
@Service
public class OidcLoginUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final SysUserMapper sysUserMapper;
    private final IdentityProviderMapper identityProviderMapper;
    private final UserExternalIdentityMapper externalIdentityMapper;
    private final OidcSecurityProperties properties;
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    @Autowired
    public OidcLoginUserService(SysUserMapper sysUserMapper,
                                IdentityProviderMapper identityProviderMapper,
                                UserExternalIdentityMapper externalIdentityMapper,
                                OidcSecurityProperties properties) {
        this(sysUserMapper, identityProviderMapper, externalIdentityMapper, properties, new OidcUserService());
    }

    OidcLoginUserService(SysUserMapper sysUserMapper,
                         IdentityProviderMapper identityProviderMapper,
                         UserExternalIdentityMapper externalIdentityMapper,
                         OidcSecurityProperties properties,
                         OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.sysUserMapper = sysUserMapper;
        this.identityProviderMapper = identityProviderMapper;
        this.externalIdentityMapper = externalIdentityMapper;
        this.properties = properties;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        String issuer = userRequest.getClientRegistration().getProviderDetails().getIssuerUri();
        IdentityProvider provider = identityProviderMapper.selectEnabledByTenantAndIssuer(
                properties.getTenantId(), issuer);
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1
                || !"OIDC".equalsIgnoreCase(provider.getProviderType())) {
            throw authError("oidc_provider_not_allowed", "許可されていないOIDC providerです");
        }

        OidcUser oidcUser;
        try {
            oidcUser = delegate.loadUser(userRequest);
        } catch (OAuth2AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            // issuer停止・timeout等を外部例外のまま画面へ漏らさず、認証失敗へ統一する。
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("oidc_provider_unavailable"), "OIDC providerへ接続できません", e);
        }

        String tokenIssuer = oidcUser.getClaimAsString("iss");
        if (StringUtils.hasText(tokenIssuer) && !issuer.equals(tokenIssuer)) {
            throw authError("oidc_issuer_mismatch", "OIDC issuerが登録内容と一致しません");
        }
        String subject = oidcUser.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw authError("oidc_subject_missing", "OIDC subjectがありません");
        }

        UserExternalIdentity link = externalIdentityMapper.selectByTenantProviderAndSubject(
                provider.getTenantId(), provider.getId(), subject);
        if (link == null || link.getUserId() == null) {
            throw authError("oidc_identity_not_linked", "この外部アカウントは管理者承認が必要です");
        }
        if (!provider.getId().equals(link.getProviderId())) {
            throw authError("oidc_provider_mismatch", "OIDC providerの紐付けが一致しません");
        }

        SysUser user = sysUserMapper.selectById(link.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw authError("oidc_user_inactive", "内部ユーザーが無効です");
        }

        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.getRole());
        return new OidcLoginUser(user, oidcUser, Collections.singletonList(authority));
    }

    private OAuth2AuthenticationException authError(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code), message);
    }
}
