package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.AdministratorStartupConditions;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.ProductionSecurityConfigurationValidator;
import com.ses.entity.SysUser;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.mapper.UserMfaMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.privacy.production.PrivacyProductionConfigurationValidator;
import com.ses.service.SysUserService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doReturn;

/**
 * RB-ADMIN-GUARD: 実MySQL 8で、2名の管理者が同時に別々の管理者を無効化する競合を管理者の継続性guardが直列化することを確かめる。
 *
 * <p>OIDCでログインできる管理者が4名（起動時検査は3名以上）のとき、先の無効化（4→3）がcommitする前に後の無効化を始める。
 * 後の無効化は全管理者の {@code sys_user} 行のX lockで先の操作を待ち（{@link InnoDbRecordLockWaits#waitEdgesOwnedBy} で
 * 待機を確認する）、先のcommit後に READ_COMMITTED で数え直して（3→2）拒否される。lockが無ければ、両方が4名を見て
 * 通り、2名になる。拒否した変更はrollbackされ、拒否の監査 {@code ADMIN_CONTINUITY_DENIED} は独立transactionで残る。</p>
 *
 * <p>起動時検査（{@code @Profile("prod")}）はfixtureのtenant・issuerで試験用に作って登録し、prodの判定だけを
 * {@code isActive} の差替えで有効にする（{@code AdministratorContinuityGuardH2Test} と同じ）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class AdministratorContinuityGuardMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_admin_continuity_guard")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final String TENANT = "default";
    private static final String ISSUER = "https://idp.example.invalid/rb-admin-guard-mysql";
    private static final long WAIT_SECONDS = 30;
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @SpyBean
    private AdministratorContinuityGuard guard;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SysUserService sysUserService;
    @Autowired
    private SysUserMapper sysUserMapper;

    private SysUser operator;
    private long providerId;

    @TestConfiguration
    static class StartupConditionsConfiguration {

        @Bean
        AdministratorStartupConditions securityStartupConditions(SysUserMapper sysUserMapper,
                                                                 UserMfaMapper userMfaMapper,
                                                                 PermissionGroupMapper permissionGroupMapper,
                                                                 IdentityProviderMapper identityProviderMapper,
                                                                 UserExternalIdentityMapper userExternalIdentityMapper) {
            ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(
                    conditionProperties(), null, null, sysUserMapper, userMfaMapper, permissionGroupMapper, null, null);
            validator.setIdentityProviderMapper(identityProviderMapper);
            validator.setUserExternalIdentityMapper(userExternalIdentityMapper);
            return validator::checkAdministratorConditions;
        }

        @Bean
        AdministratorStartupConditions privacyStartupConditions(PermissionGroupMapper permissionGroupMapper,
                                                                UserPermissionGroupMapper userPermissionGroupMapper,
                                                                IdentityProviderMapper identityProviderMapper) {
            PrivacyProductionConfigurationValidator validator = new PrivacyProductionConfigurationValidator(null, null,
                    conditionProperties(), permissionGroupMapper, userPermissionGroupMapper, identityProviderMapper, null);
            return validator::checkAdministratorConditions;
        }

        private static OidcSecurityProperties conditionProperties() {
            OidcSecurityProperties properties = new OidcSecurityProperties();
            properties.setTenantId(TENANT);
            properties.setIssuerUri(ISSUER);
            properties.setBreakGlassUsernames(Set.of());
            return properties;
        }
    }

    @BeforeEach
    void setUp() {
        doReturn(true).when(guard).isActive();
        operator = insertUser("管理者");
        Long existing = jdbc.query("SELECT id FROM m_identity_provider WHERE tenant_id = ? AND issuer_uri = ?",
                rs -> rs.next() ? rs.getLong(1) : null, TENANT, ISSUER);
        if (existing == null) {
            jdbc.update("INSERT INTO m_identity_provider (tenant_id, provider_type, issuer_uri, client_id, enabled, "
                    + "deleted_flag) VALUES (?, 'OIDC', ?, 'rb-guard-mysql-client', 1, 0)", TENANT, ISSUER);
            existing = jdbc.queryForObject("SELECT id FROM m_identity_provider WHERE tenant_id = ? AND issuer_uri = ?",
                    Long.class, TENANT, ISSUER);
        }
        providerId = existing;
        // 試験の順は無作為。前の試験のlinkを外し、この試験が作る管理者だけをOIDC管理者として数えさせる（専用DB）。
        jdbc.update("UPDATE t_user_external_identity SET deleted_flag = 1 WHERE provider_id = ?", providerId);
        bindContexts();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 二人の管理者が同時に別々の管理者を無効化しても後の操作は先のcommitを待って数え直し片方だけが通る() throws Exception {
        SysUser first = linkedAdmin();
        SysUser second = linkedAdmin();
        linkedAdmin();
        linkedAdmin();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstHoldsLocks = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstConnection = new AtomicLong();
        try {
            Future<?> firstDisable = pool.submit(() -> {
                bindContexts();
                try {
                    readCommitted().executeWithoutResult(status -> {
                        firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        sysUserService.updateUserStatus(first.getId(), 0, authentication());
                        firstHoldsLocks.countDown();
                        awaitQuietly(releaseFirst);
                    });
                } finally {
                    clearContexts();
                }
                return null;
            });
            assertTrue(firstHoldsLocks.await(WAIT_SECONDS, TimeUnit.SECONDS), "先の無効化がlockを取ること");

            Future<?> secondDisable = pool.submit(() -> {
                bindContexts();
                try {
                    sysUserService.updateUserStatus(second.getId(), 0, authentication());
                } finally {
                    clearContexts();
                }
                return null;
            });
            awaitWaitOnSysUser(firstConnection.get(), secondDisable);

            releaseFirst.countDown();
            firstDisable.get(WAIT_SECONDS, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> secondDisable.get(WAIT_SECONDS, TimeUnit.SECONDS));
            BusinessException rejected = assertInstanceOf(BusinessException.class, failure.getCause());
            assertEquals(400, rejected.getCode());
            assertEquals("error.user.adminContinuity.oidcAdministrators", rejected.getMessageKey());
            assertArrayEquals(new Object[] {2, 3}, rejected.getArgs(), "先のcommit後の3名から数え直して2名になる");
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }

        assertEquals(0, status(first.getId()), "先の無効化はcommitされる");
        assertEquals(1, status(second.getId()), "後の無効化はrollbackされる");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code = ? AND method = ? "
                        + "AND uri LIKE ?", Integer.class, AdministratorContinuityGuard.AUDIT_APPLICATION_CODE, "SECURITY",
                "%operation=USER_STATUS&targets=" + second.getId() + "&%"), "拒否の監査はrollback後に残る");
    }

    @Test
    void 拒否した削除はrollbackされ起動時検査を満たす人数が残る() {
        SysUser first = linkedAdmin();
        linkedAdmin();
        linkedAdmin();
        int before = oidcAdministrators();

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> sysUserService.deleteUser(first.getId(), authentication()));

        assertEquals("error.user.adminContinuity.oidcAdministrators", rejected.getMessageKey());
        assertEquals(0, jdbc.queryForObject("SELECT deleted_flag FROM sys_user WHERE id = ?", Integer.class,
                first.getId()), "削除はrollbackされる");
        assertEquals(before, oidcAdministrators());
        assertFalse(before < 3, "fixtureの管理者は3名以上");
    }

    /** 後の無効化が、先の無効化のtransactionが持つ {@code sys_user} 行のlockを待っていることを確かめる。 */
    private void awaitWaitOnSysUser(long blockerConnection, Future<?> waiter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                waiter.get();
                fail("後の無効化が先の無効化のlockを待たずに終わりました");
            }
            if (!InnoDbRecordLockWaits.waitEdgesOwnedBy(jdbc, blockerConnection, "sys_user", "PRIMARY").isEmpty()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        fail("後の無効化が先の無効化の sys_user 行のlockを待つことを確認できませんでした");
    }

    private int oidcAdministrators() {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT u.username) FROM t_user_external_identity x "
                + "JOIN sys_user u ON u.id = x.user_id WHERE x.tenant_id = ? AND x.provider_id = ? "
                + "AND x.review_status = 'APPROVED' AND x.deleted_flag = 0 AND u.deleted_flag = 0 AND u.status = 1 "
                + "AND u.role = '管理者'", Integer.class, TENANT, providerId);
    }

    private TransactionTemplate readCommitted() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout((int) (WAIT_SECONDS * 4));
        return template;
    }

    private void bindContexts() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        SecurityContextHolder.getContext().setAuthentication(authentication());
    }

    private static void clearContexts() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    private Authentication authentication() {
        LoginUser principal = new LoginUser(operator, List.of(new SimpleGrantedAuthority("ROLE_管理者")), TENANT);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    /** fixtureのproviderにAPPROVEDの外部identity linkを持ち、OIDCでログインできる有効な管理者。 */
    private SysUser linkedAdmin() {
        SysUser user = insertUser("管理者");
        jdbc.update("INSERT INTO t_user_external_identity (tenant_id, user_id, provider_id, subject, review_status, "
                        + "reviewed_at, reviewed_by, deleted_flag) VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, 0)",
                TENANT, user.getId(), providerId, "rb-guard-mysql-subject-" + user.getId(),
                LocalDateTime.of(2026, 11, 1, 10, 0), operator.getId());
        return user;
    }

    private SysUser insertUser(String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(TENANT).username("rb_guard_" + seq).password("pass")
                .realName("RB-ADMIN-GUARD試験").email("rb-guard-" + seq + "@example.com").role(role).status(1).build();
        sysUserMapper.insert(user);
        return sysUserMapper.selectById(user.getId());
    }

    private int status(long userId) {
        return jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS * 2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("先の無効化の解放を待てませんでした");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
