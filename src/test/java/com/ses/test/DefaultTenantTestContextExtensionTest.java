package com.ses.test;

import com.ses.config.LoginUser;
import com.ses.common.exception.BusinessException;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** service loaderで全体適用されるdefault tenant拡張のfail-closed契約。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DefaultTenantTestContextExtensionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void authenticationAbsent_doesNotCreateAdministrator() {
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
        BusinessException error = assertThrows(
                BusinessException.class, AccountingTenantContextHolder::requireTenantContext);
        assertEquals(403, error.getCode());
    }

    @Test
    @WithMockUser(username = "7", roles = "営業")
    void withMockUser_isUpgradedToTenantAwarePrincipal() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        LoginUser loginUser = assertInstanceOf(LoginUser.class, principal);
        assertEquals(7L, loginUser.getSysUser().getId());
        assertEquals("default", loginUser.getTenantId());
    }

    @Test
    @WithAnonymousUser
    void anonymousAuthentication_remainsAnonymous() {
        assertInstanceOf(AnonymousAuthenticationToken.class,
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @EnableDefaultTenantTestContext
    void explicitOptIn_createsAdministratorAndLegalEntityFixture() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertInstanceOf(LoginUser.class, principal);
        assertEquals("default", AccountingTenantContextHolder.getExplicitTenantId());
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM t_user_organization uo
                  JOIN m_organization_unit ou ON ou.id = uo.organization_id
                 WHERE uo.user_id = 1 AND ou.legal_entity_id = 1
                   AND uo.deleted_flag = 0 AND ou.deleted_flag = 0
                """, Integer.class);
        assertEquals(1, count);
    }

    @Test
    @DisableDefaultTenantTestContext
    @WithMockUser(username = "plain", roles = "管理者")
    void disabled_doesNotUpgradePrincipalOrBindTenant() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertInstanceOf(UserDetails.class, principal);
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }
}
