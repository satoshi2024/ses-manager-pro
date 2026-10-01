package com.ses.test;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

/** tenant必須境界を通るMockMvcテスト用の認証主体を構築する。 */
public final class TenantTestSecurity {

    private TenantTestSecurity() {
    }

    public static void bind(String tenantId) {
        AccountingTenantContextHolder.setTenantId(tenantId);
        Authentication current = TestSecurityContextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated()) {
            bindAs(tenantId, "管理者");
            return;
        }
        String role = current.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("管理者");
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(current.getName());
        user.setPassword("N/A");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user, current.getAuthorities(), tenantId);
        UsernamePasswordAuthenticationToken replacement = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        replacement.setDetails(current.getDetails());
        TestSecurityContextHolder.setAuthentication(replacement);
        SecurityContextHolder.getContext().setAuthentication(replacement);
    }

    public static void bindAs(String tenantId, String role) {
        bindAs(1L, "tenant-test-" + role, tenantId, role);
    }

    public static void bindAs(Long userId, String username, String tenantId, String role) {
        AccountingTenantContextHolder.setTenantId(tenantId);
        var authorities = java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("N/A");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user, authorities, tenantId);
        UsernamePasswordAuthenticationToken replacement = new UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        TestSecurityContextHolder.setAuthentication(replacement);
        SecurityContextHolder.getContext().setAuthentication(replacement);
    }

    public static UsernamePasswordAuthenticationToken authentication(
            Long userId, String username, String tenantId, String role) {
        var authorities = java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("N/A");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user, authorities, tenantId);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    /** 管理者principalが一意の法人を解決できる最小組織fixtureを用意する。 */
    public static void ensureLegalEntity(JdbcTemplate jdbcTemplate, Long legalEntityId) {
        ensureLegalEntity(jdbcTemplate, legalEntityId, 1L);
    }

    /** 指定ユーザーが一意の法人を解決できる最小組織fixtureを用意する。 */
    public static void ensureLegalEntity(JdbcTemplate jdbcTemplate, Long legalEntityId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_organization_unit WHERE legal_entity_id = ? AND deleted_flag = 0",
                Integer.class, legalEntityId);
        if (count != null && count > 0) {
            ensureUserOrganization(jdbcTemplate, legalEntityId, userId);
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO m_organization_unit
                    (tenant_id, legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """,
                1L, legalEntityId, "NF09-LEGAL-" + legalEntityId,
                "NF09テスト法人", "法人", LocalDate.of(2020, 1, 1), "有効");
        ensureUserOrganization(jdbcTemplate, legalEntityId, userId);
    }

    private static void ensureUserOrganization(JdbcTemplate jdbcTemplate, Long legalEntityId, Long userId) {
        Long organizationId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM m_organization_unit WHERE legal_entity_id = ? AND deleted_flag = 0",
                Long.class, legalEntityId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user_organization WHERE user_id = ? AND organization_id = ? "
                        + "AND deleted_flag = 0 AND valid_to IS NULL",
                Integer.class, userId, organizationId);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO t_user_organization
                    (tenant_id, user_id, organization_id, primary_flag, valid_from, deleted_flag)
                VALUES ('default', ?, ?, 1, ?, 0)
                """, userId, organizationId, LocalDate.of(2020, 1, 1));
    }

    public static void clear() {
        AccountingTenantContextHolder.clear();
        TestSecurityContextHolder.clearContext();
        SecurityContextHolder.clearContext();
    }
}
