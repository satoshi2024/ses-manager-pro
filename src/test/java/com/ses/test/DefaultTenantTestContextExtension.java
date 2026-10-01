package com.ses.test;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.entity.SysUser;
import com.ses.portal.PortalLoginUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.lang.reflect.Method;

/**
 * 既存の単一tenantテストへ {@code default} tenant contextを与える。
 *
 * <p>本番filterのfail-closed契約は変更せず、{@code @WithMockUser} が生成するtenant非対応principalだけを
 * test用 {@link LoginUser} へ置換する。認証の自動生成とDB fixture投入は
 * {@link EnableDefaultTenantTestContext} を付けた旧テストに限定する。</p>
 */
public class DefaultTenantTestContextExtension
        implements BeforeEachCallback, BeforeTestExecutionCallback, InvocationInterceptor, AfterEachCallback {

    private static final String DEFAULT_TENANT = "default";

    @Override
    public void beforeEach(ExtensionContext context) {
        if (disabled(context)) {
            return;
        }
        bindDefaultContext(context);
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        if (disabled(context)) {
            return;
        }
        bindDefaultContext(context);
    }

    /**
     * Spring Securityのtest listenerが{@code @WithMockUser}を反映した後、各fixtureの直前に
     * tenant対応principalへ置換する。これにより{@code @BeforeEach}内のservice呼出しも
     * 本番と同じfail-closed境界を通過できる。
     */
    @Override
    public void interceptBeforeEachMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (!disabled(extensionContext)) {
            bindDefaultContext(extensionContext);
        }
        invocation.proceed();
    }

    /** test method直前にも再適用し、listener間の順序に依存しない。 */
    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (!disabled(extensionContext)) {
            bindDefaultContext(extensionContext);
        }
        invocation.proceed();
    }

    private void bindDefaultContext(ExtensionContext context) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!enabled(context)
                && (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)) {
            return;
        }
        String tenantId = AccountingTenantContextHolder.getExplicitTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = DEFAULT_TENANT;
            AccountingTenantContextHolder.setTenantId(tenantId);
        }
        upgradeMockPrincipal(context, tenantId);
        ensureDefaultLegalEntity(context);
    }

    private void ensureDefaultLegalEntity(ExtensionContext context) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!enabled(context)
                || defaultLegalEntityFixtureDisabled(context)
                || authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof LoginUser)) {
            return;
        }
        try {
            JdbcTemplate jdbcTemplate = SpringExtension.getApplicationContext(context)
                    .getBeanProvider(JdbcTemplate.class)
                    .getIfAvailable();
            if (jdbcTemplate != null) {
                TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
            }
        } catch (IllegalStateException | BadSqlGrammarException ignored) {
            // DBを起動しないslice/unit test、または組織schemaを持たない限定schemaでは不要。
        }
    }

    private void upgradeMockPrincipal(ExtensionContext context, String tenantId) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated()) {
            if (enabled(context)) {
                bindTestAdministrator(tenantId);
            }
            return;
        }
        if (current instanceof AnonymousAuthenticationToken
                || current.getPrincipal() instanceof LoginUser
                || current.getPrincipal() instanceof OidcLoginUser
                || current.getPrincipal() instanceof ExternalApiPrincipal
                || current.getPrincipal() instanceof PortalLoginUser
                || current.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_METRICS_SCRAPER".equals(authority.getAuthority()))) {
            return;
        }

        String role = current.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("管理者");
        SysUser user = new SysUser();
        user.setId(resolveUserId(context, current.getName()));
        user.setTenantId(tenantId);
        user.setUsername(current.getName());
        user.setPassword("N/A");
        user.setRole(role);
        user.setStatus(1);
        LoginUser principal = new LoginUser(user, current.getAuthorities(), tenantId);
        UsernamePasswordAuthenticationToken replacement = new UsernamePasswordAuthenticationToken(
                principal, current.getCredentials(), current.getAuthorities());
        replacement.setDetails(current.getDetails());
        TestSecurityContextHolder.setAuthentication(replacement);
        SecurityContextHolder.getContext().setAuthentication(replacement);
    }

    private void bindTestAdministrator(String tenantId) {
        var authorities = java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_管理者"));
        SysUser user = new SysUser();
        user.setId(1L);
        user.setTenantId(tenantId);
        user.setUsername("tenant-test-admin");
        user.setPassword("N/A");
        user.setRole("管理者");
        user.setStatus(1);
        LoginUser principal = new LoginUser(user, authorities, tenantId);
        UsernamePasswordAuthenticationToken replacement = new UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        TestSecurityContextHolder.setAuthentication(replacement);
        SecurityContextHolder.getContext().setAuthentication(replacement);
    }

    private Long parseUserId(String username) {
        if (username == null || username.isBlank()) {
            return 1L;
        }
        try {
            return Long.valueOf(username);
        } catch (NumberFormatException ignored) {
            return 1L;
        }
    }

    private Long resolveUserId(ExtensionContext context, String username) {
        Long numericId = parseUserId(username);
        if (username == null || username.isBlank() || !numericId.equals(1L)) {
            return numericId;
        }
        try {
            JdbcTemplate jdbcTemplate = SpringExtension.getApplicationContext(context)
                    .getBeanProvider(JdbcTemplate.class)
                    .getIfAvailable();
            if (jdbcTemplate == null) {
                return numericId;
            }
            java.util.List<Long> ids = jdbcTemplate.query(
                    "SELECT id FROM sys_user WHERE username = ? AND deleted_flag = 0",
                    (rs, rowNum) -> rs.getLong(1), username);
            return ids.size() == 1 ? ids.get(0) : numericId;
        } catch (IllegalStateException | DataAccessException ignored) {
            return numericId;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        AccountingTenantContextHolder.clear();
        TestSecurityContextHolder.clearContext();
        SecurityContextHolder.clearContext();
    }

    private boolean disabled(ExtensionContext context) {
        return context.getElement()
                .flatMap(element -> AnnotationSupport.findAnnotation(
                        element, DisableDefaultTenantTestContext.class))
                .isPresent()
                || AnnotationSupport.findAnnotation(
                        context.getRequiredTestClass(), DisableDefaultTenantTestContext.class).isPresent();
    }

    private boolean enabled(ExtensionContext context) {
        return context.getElement()
                .flatMap(element -> AnnotationSupport.findAnnotation(
                        element, EnableDefaultTenantTestContext.class))
                .isPresent()
                || AnnotationSupport.findAnnotation(
                        context.getRequiredTestClass(), EnableDefaultTenantTestContext.class).isPresent();
    }

    private boolean defaultLegalEntityFixtureDisabled(ExtensionContext context) {
        return context.getElement()
                .flatMap(element -> AnnotationSupport.findAnnotation(
                        element, DisableDefaultLegalEntityTestFixture.class))
                .isPresent()
                || AnnotationSupport.findAnnotation(
                        context.getRequiredTestClass(), DisableDefaultLegalEntityTestFixture.class).isPresent();
    }
}
