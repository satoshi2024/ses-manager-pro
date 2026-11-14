package com.ses.service.pwa;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.dto.pwa.PwaRestoreReauthenticateRequest;
import com.ses.entity.SysUser;
import com.ses.service.pwa.PwaRestoreIdentityService.CurrentIdentity;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2上でSpring proxyとREQUIRES_NEWの境界を確認する。MySQLの並行性証明には用いない。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PwaRestoreTransactionIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PwaRestoreFailureRecorder failureRecorder;
    @Autowired private PwaRestoreIdentityService identityService;
    @Autowired private FaultInjectingTransactionManager transactionManager;
    @Autowired private Clock clock;
    @Autowired private PwaUserContextService userContextService;
    @Autowired private PwaRestoreAuthorizationService authorizationService;

    private String username;
    private Long userId;
    private Long engineerId;
    private final String tenant = "restore-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "pwa_restore_tx_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @BeforeEach
    void createIsolatedUserAndLink() {
        username = "restore" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, role, status, failed_count, deleted_flag) "
                + "VALUES (?, ?, 'pw-hash', '要員', 1, 0, 0)", tenant, username);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO t_engineer (tenant_id, full_name, employment_type, status, deleted_flag) "
                + "VALUES (?, ?, '正社員', 'Bench', 0)", tenant, "復元テスト要員" + userId);
        engineerId = jdbc.queryForObject("SELECT id FROM t_engineer WHERE tenant_id = ? AND full_name = ?",
                Long.class, tenant, "復元テスト要員" + userId);
        jdbc.update("INSERT INTO t_engineer_account_link (tenant_id, engineer_id, sys_user_id, linked_at) "
                + "VALUES (?, ?, ?, ?)", tenant, engineerId, userId, LocalDateTime.now(clock).minusDays(1));
    }

    @org.junit.jupiter.api.AfterEach
    void deleteIsolatedRows() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (userId == null) return;
        jdbc.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ?", userId);
        if (engineerId != null) jdbc.update("DELETE FROM t_engineer WHERE id = ?", engineerId);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Test
    void identityTransactionのcommit失敗時は再認証proofをsessionへ発行しない() {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setTenantId(tenant);
        user.setRole("要員");
        user.setStatus(1);
        user.setPassword("pw-hash");
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_要員")), tenant);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, "", principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        AccountingTenantContextHolder.setTenantId(tenant);

        String scope = userContextService.current().userScope();
        var request = new PwaRestoreReauthenticateRequest();
        request.setPassword("pw-hash");
        request.setDocumentNonce("AAAAAAAAAAAAAAAAAAAAAA");
        request.setUserScope(scope);
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(300);
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);

        transactionManager.failNextCommit();
        assertThatThrownBy(() -> authorizationService.reauthenticate(request, session, authentication))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.pwa.restore.unavailable");
        assertThat(session.getAttribute(PwaRestoreAuthorizationService.SESSION_STATE_ATTRIBUTE)).isNull();
    }

    @Test
    void failureRecorderのREQUIRES_NEW更新は呼出元rollback後も残る() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.execute(status -> {
            failureRecorder.record(userId, tenant);
            status.setRollbackOnly();
            return null;
        });
        assertThat(failedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT locked_until FROM sys_user WHERE id = ?", LocalDateTime.class, userId))
                .isNull();
    }

    @Test
    void restoreIdentityは毎回DBのactiveLinkOwnershipとlockを読み直す() {
        CurrentIdentity initial = identityService.load(userId, tenant);
        assertThat(initial).isNotNull();
        assertThat(initial.engineerId()).isEqualTo(engineerId);
        assertThat(initial.locked()).isFalse();

        jdbc.update("UPDATE sys_user SET status = 0 WHERE id = ?", userId);
        assertThat(identityService.load(userId, tenant)).isNull();
        jdbc.update("UPDATE sys_user SET status = 1, locked_until = ? WHERE id = ?",
                LocalDateTime.now(clock).plusMinutes(5), userId);
        assertThat(identityService.load(userId, tenant).locked()).isTrue();
        jdbc.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ?", userId);
        assertThat(identityService.load(userId, tenant)).isNull();
    }

    @Test
    void oidcSessionの復元要求は実AccountLockへ失敗回数を加算せずローカルsessionのlockも起こさない() {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setTenantId(tenant);
        user.setRole("要員");
        user.setStatus(1);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_要員"));
        OidcIdToken idToken = OidcIdToken.withTokenValue("pwa-restore-it-id-token")
                .subject("idp-" + userId).issuer("https://idp.invalid/" + tenant)
                .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
        OAuth2AuthenticationToken oidc = new OAuth2AuthenticationToken(
                new OidcLoginUser(user, new DefaultOidcUser(authorities, idToken), authorities),
                authorities, "enterprise");
        SecurityContext oidcContext = SecurityContextHolder.createEmptyContext();
        oidcContext.setAuthentication(oidc);
        SecurityContextHolder.setContext(oidcContext);
        AccountingTenantContextHolder.setTenantId(tenant);
        String scope = userContextService.current().userScope();
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(300);
        session.setAttribute("SPRING_SECURITY_CONTEXT", oidcContext);

        // lock閾値（5回）を超える回数、IdPのパスワード等を入力しても失敗として数えない。
        for (int attempt = 0; attempt < 6; attempt++) {
            var wrong = new PwaRestoreReauthenticateRequest();
            wrong.setPassword("idp-password-" + attempt);
            wrong.setDocumentNonce("AAAAAAAAAAAAAAAAAAAAAA");
            wrong.setUserScope(scope);
            assertThatThrownBy(() -> authorizationService.reauthenticate(wrong, session, oidc))
                    .isInstanceOf(PwaRestoreReauthUnsupportedException.class)
                    .hasMessage("error.pwa.restore.reauthUnsupported");
        }
        // DBのローカルパスワードと一致する値でもproofを発行しない。
        var matching = new PwaRestoreReauthenticateRequest();
        matching.setPassword("pw-hash");
        matching.setDocumentNonce("AAAAAAAAAAAAAAAAAAAAAA");
        matching.setUserScope(scope);
        assertThatThrownBy(() -> authorizationService.reauthenticate(matching, session, oidc))
                .isInstanceOf(PwaRestoreReauthUnsupportedException.class);
        var confirm = new com.ses.dto.pwa.PwaRestoreConfirmRequest();
        confirm.setProof("p".repeat(43));
        confirm.setDocumentNonce("AAAAAAAAAAAAAAAAAAAAAA");
        confirm.setUserScope(scope);
        assertThatThrownBy(() -> authorizationService.confirm(confirm, session, oidc))
                .isInstanceOf(PwaRestoreReauthUnsupportedException.class);

        assertThat(failedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM sys_user WHERE id = ?", LocalDateTime.class, userId))
                .isNull();
        assertThat(identityService.load(userId, tenant).locked()).isFalse();
        assertThat(session.getAttribute(PwaRestoreAuthorizationService.SESSION_STATE_ATTRIBUTE)).isNull();

        // 同じ要員がローカルパスワードでlogin済みのsessionなら、従来どおり正しいパスワードで再認証できる（lockされていない）。
        LoginUser principal = new LoginUser(user, authorities, tenant);
        var local = UsernamePasswordAuthenticationToken.authenticated(principal, "", principal.getAuthorities());
        SecurityContext localContext = SecurityContextHolder.createEmptyContext();
        localContext.setAuthentication(local);
        SecurityContextHolder.setContext(localContext);
        MockHttpSession localSession = new MockHttpSession();
        localSession.setMaxInactiveInterval(300);
        localSession.setAttribute("SPRING_SECURITY_CONTEXT", localContext);
        var proof = authorizationService.reauthenticate(matching, localSession, local);
        assertThat(proof.proof()).hasSize(43);
        assertThat(failedCount()).isZero();
    }

    private int failedCount() {
        return jdbc.queryForObject("SELECT failed_count FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionFaultConfiguration {
        @Bean
        @Primary
        FaultInjectingTransactionManager transactionManager(DataSource dataSource) {
            return new FaultInjectingTransactionManager(dataSource);
        }
    }

    /** commit境界を一度だけ失敗させるtest-only fault injection。DB engine faultの再現とは区別する。 */
    static class FaultInjectingTransactionManager extends DataSourceTransactionManager {
        private final AtomicBoolean failNextCommit = new AtomicBoolean();

        FaultInjectingTransactionManager(DataSource dataSource) { super(dataSource); }

        void failNextCommit() { failNextCommit.set(true); }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failNextCommit.compareAndSet(true, false)) {
                throw new TransactionSystemException("test-only commit fault");
            }
            super.doCommit(status);
        }
    }
}
