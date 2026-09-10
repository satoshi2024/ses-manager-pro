package com.ses.config;

import com.ses.entity.SysUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.security.core.CredentialsContainer;

import java.util.Collection;

public class LoginUser implements UserDetails, CredentialsContainer {

    private final SysUser sysUser;
    private final Collection<? extends GrantedAuthority> authorities;
    /** 認証時に束縛されたtenant。未束縛の手動principalは推測せずfail-closedする。 */
    private final String tenantId;

    public LoginUser(SysUser sysUser, Collection<? extends GrantedAuthority> authorities) {
        this(sysUser, authorities, null);
    }

    public LoginUser(SysUser sysUser, Collection<? extends GrantedAuthority> authorities, String tenantId) {
        this.sysUser = sysUser;
        this.authorities = authorities;
        this.tenantId = tenantId;
    }

    @Override
    public void eraseCredentials() {
        if (sysUser != null) {
            sysUser.setPassword(null);
        }
    }

    public SysUser getSysUser() {
        return sysUser;
    }

    public String getTenantId() {
        return tenantId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return sysUser.getPassword();
    }

    @Override
    public String getUsername() {
        return sysUser.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // locked_until が未来日時ならロック中
        return sysUser.getLockedUntil() == null
                || !sysUser.getLockedUntil().isAfter(java.time.LocalDateTime.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return sysUser.getStatus() != null && sysUser.getStatus() == 1;
    }
}
