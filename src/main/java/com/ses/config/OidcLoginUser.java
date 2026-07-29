package com.ses.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Collection;
import java.util.Map;

import com.ses.entity.SysUser;

/**
 * OIDCの外部subjectを内部SysUserへ解決したprincipal。
 * getName()は外部subjectではなく既存監査・AccountLock契約の内部usernameを返す。
 */
public class OidcLoginUser extends LoginUser implements OidcUser {

    private final OidcUser delegate;

    public OidcLoginUser(SysUser sysUser, OidcUser delegate,
                         Collection<? extends GrantedAuthority> authorities) {
        super(sysUser, authorities);
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return getUsername();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }
}
