package com.ses.config;

import com.ses.entity.SysUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.security.core.CredentialsContainer;

import java.util.Collection;

public class LoginUser implements UserDetails, CredentialsContainer {

    private final SysUser sysUser;
    private final Collection<? extends GrantedAuthority> authorities;

    public LoginUser(SysUser sysUser, Collection<? extends GrantedAuthority> authorities) {
        this.sysUser = sysUser;
        this.authorities = authorities;
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
