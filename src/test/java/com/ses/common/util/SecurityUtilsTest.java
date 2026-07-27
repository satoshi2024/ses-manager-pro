package com.ses.common.util;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testCurrentUserIdWithLoginUser() {
        SysUser sysUser = new SysUser();
        sysUser.setId(100L);
        sysUser.setUsername("testuser");
        LoginUser loginUser = new LoginUser(sysUser, Collections.emptyList());

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertEquals(100L, SecurityUtils.currentUserId());
        assertEquals("testuser", SecurityUtils.currentUsername());
    }

    @Test
    void testCurrentUserIdWithoutAuthentication() {
        assertNull(SecurityUtils.currentUserId());
        assertNull(SecurityUtils.currentUsername());
    }

    @Test
    void testCurrentUsernameWithStringPrincipal() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("stringuser", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertNull(SecurityUtils.currentUserId());
        assertEquals("stringuser", SecurityUtils.currentUsername());
    }

    @Test
    void testStandardPrincipalUsesAuthoritiesAndNumericLocalId() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "200", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_営業")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertEquals(200L, SecurityUtils.currentUserId());
        assertEquals("営業", SecurityUtils.currentRole());
        assertEquals("200", SecurityUtils.currentUsername());
    }

    @Test
    void testStandardAdminPrincipalIsRecognizedByRole() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertNull(SecurityUtils.currentUserId());
        assertEquals("管理者", SecurityUtils.currentRole());
    }
}
