package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.migration.PrivacyJournalFlywayJobConfig;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Select;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 hold 開始の実 MySQL（A1H-1、production-bridge-phased-plan.md §3.4.4）。application DB（全 migration）と Journal
 * （本番 adapter・本番の索引）の2 container で、ACK 不明時の再送で二重 hold を作らない、同じ tenant の並行開始で後者が409、
 * pending の CAS、Journal と一致した gate、Journal 停止時の gate を確かめる。あわせて W3-14b delta Review D-P3-4 の
 * 3 SQL（{@code PrivacyProductionConfigurationValidator} が使う mapper の {@code @Select}）を、注釈の SQL をそのまま MySQL で
 * 実行して確かめる（{@code t_user_external_identity} の EXISTS を含む）。lock の検査に {@code LOCK_DATA} を使わない。
 */
@Tag("mysql")
@Testcontainers
class PrivacyHoldServiceMySqlTest {

    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String JOURNAL_ID = "journal-a1h-mysql";

    @Container
    static final MySQLContainer<?> JOURNAL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(PrivacyJournalTestPrincipals.SCHEMA)
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");

    @Container
    static final com.ses.test.MySQLContainer<?> APP = new com.ses.test.MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword(ROOT_PASSWORD);

    private static HikariDataSource appPool;
    private static HikariDataSource appenderPool;
    private static JdbcTemplate journalRoot;
    private static PrivacyJournalPort production;
    private static String appender;
    private static String appenderPassword;

    private PrivacyHoldMySqlComponents components;

    @BeforeAll
    static void startContainers() {
        Flyway.configure().dataSource(APP.getJdbcUrl(), "root", ROOT_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        PrivacyJournalFlywayJobConfig.configure(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD).load().migrate();
        journalRoot = new JdbcTemplate(new DriverManagerDataSource(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD));
        appender = PrivacyJournalTestPrincipals.uniqueName("nf07_hold");
        appenderPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, appender, appenderPassword);
        appenderPool = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), appender, appenderPassword);
        appPool = new HikariDataSource();
        appPool.setJdbcUrl(APP.getJdbcUrl());
        appPool.setUsername("root");
        appPool.setPassword(ROOT_PASSWORD);
        appPool.setMaximumPoolSize(8);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        production = ProductionPrivacyComponents.productionPrivacyJournal(prod, appenderPool, Clock.systemUTC());
    }

    @AfterAll
    static void closePools() {
        if (appPool != null) {
            appPool.close();
        }
        if (appenderPool != null) {
            appenderPool.close();
        }
    }

    @BeforeEach
    void setUp() {
        String tenant = "nf07-a1h-" + UUID.randomUUID().toString().substring(0, 8);
        components = new PrivacyHoldMySqlComponents(tenant, appPool, production, appenderPool, JOURNAL_ID,
                Duration.ofMinutes(2), Clock.systemUTC());
        components.seedCatalog();
    }

    @Test
    void holdを開始するとJournalのACKの後にprojectionを書きgateがJournalと一致する() {
        PrivacyHoldStartResult result = components.service.start(components.subject("ENGINEER:501"));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(journalEvents(components.tenantId)).isEqualTo(1);
        assertThat(components.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ? AND hold_id = ? AND status = 'ACTIVE'",
                components.tenantId, result.holdId())).isEqualTo(1);
        PrivacyHoldGate.Decision decision = components.gate.evaluate(components.tenantId);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).containsExactly("ENGINEER:501");
        PrivacyHoldOverview overview = components.service.overview();
        assertThat(overview.holds()).extracting(PrivacyHoldOverview.HoldEntry::holdId).containsExactly(result.holdId());
        assertThat(overview.unattributableElements()).extracting(PrivacyHoldOverview.ElementEntry::dataElementId)
                .containsExactly("INTEGRATION_HUB_AUDIT");
        assertThat(components.auditUris).allSatisfy(uri -> assertThat(uri).doesNotContain("ENGINEER").doesNotContain("501"));
    }

    @Test
    void ACK不明の後の再送は元のACKを返し二重にholdを作らない() {
        components.port.loseAckAfterCommit.set(1);

        PrivacyHoldStartResult result = components.service.start(components.subject("ENGINEER:502"));

        assertThat(components.port.appendCalls.get()).isEqualTo(2);
        assertThat(journalEvents(components.tenantId)).isEqualTo(1);
        assertThat(components.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ?", components.tenantId))
                .isEqualTo(1);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isNull();
        assertThat(result.journalRevision()).isEqualTo(1);
    }

    @Test
    void 同じtenantの並行開始は後者が409で直列化され先の開始は完了する() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        components.port.entered = entered;
        components.port.release = release;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 先の開始は pending を commit した後、Journal への append の直前で止まる
            Future<PrivacyHoldStartResult> first = executor.submit(
                    () -> components.service.start(components.subject("ENGINEER:503")));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            components.port.release = null;

            assertThatThrownBy(() -> components.service.start(components.subject("ENGINEER:504")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getMessage()).isEqualTo("error.privacy.holdStartInProgress");
                    });
            assertThat(components.port.appendCalls.get()).as("後者は Journal に触れない").isEqualTo(1);

            release.countDown();
            assertThat(first.get(60, TimeUnit.SECONDS).status()).isEqualTo("ACTIVE");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(components.service.start(components.subject("ENGINEER:504")).status()).isEqualTo("ACTIVE");
        assertThat(journalEvents(components.tenantId)).isEqualTo(2);
    }

    @Test
    void pendingのCASは同時に1件だけ成功する() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(appPool));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Integer>> attempts = List.of("hs-" + "1".repeat(32), "hs-" + "2".repeat(32)).stream()
                    .map(commandId -> CompletableFuture.supplyAsync(() -> {
                        await(barrier);
                        return transaction.execute(status -> {
                            components.store.ensureStateLocked(components.tenantId, JOURNAL_ID, Instant.now());
                            components.store.lockState(components.tenantId, JOURNAL_ID).orElseThrow();
                            return components.store.recordPending(components.tenantId, JOURNAL_ID, commandId, Instant.now());
                        });
                    }, executor)).toList();
            int succeeded = 0;
            for (CompletableFuture<Integer> attempt : attempts) {
                succeeded += attempt.get(60, TimeUnit.SECONDS);
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(components.count("SELECT COUNT(*) FROM t_privacy_projection_state WHERE tenant_id = ?",
                components.tenantId)).isEqualTo(1);
    }

    @Test
    void Journalに届かなければholdを使ったtenantはUNAVAILABLEで未使用のtenantはholdなし() {
        components.service.start(components.subject("ENGINEER:505"));
        HikariDataSource closed = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), appender, appenderPassword);
        closed.close();
        PrivacyHoldMySqlComponents unreachable = new PrivacyHoldMySqlComponents(components.tenantId, appPool, production,
                closed, JOURNAL_ID, Duration.ofMinutes(2), Clock.systemUTC());

        assertThat(unreachable.gate.evaluate(components.tenantId).reason())
                .isEqualTo(PrivacyHoldGate.REASON_JOURNAL_UNAVAILABLE);
        assertThat(unreachable.gate.evaluate("nf07-a1h-unused-" + UUID.randomUUID()).state())
                .isEqualTo(PrivacyHoldGate.State.NO_HOLDS);
    }

    // ---- W3-14b delta Review D-P3-4: PrivacyProductionConfigurationValidator の3 SQL を MySQL で ----

    @Test
    void privacy_admin_groupの許可判定SQLは許可行を数え拒否行があれば0になる() throws Exception {
        String tenant = "nf07-dp34-" + UUID.randomUUID().toString().substring(0, 8);
        long group = insertGroup(tenant, "privacy-admin");
        insertAction(tenant, group, "*", 0);
        insertAction(tenant, group, "privacy.*", 0);
        String sql = sqlOf(PermissionGroupMapper.class, "countPrivacyAdminGroupsGrantingPrivacy", String.class);

        assertThat(named().queryForObject(sql, new MapSqlParameterSource("tenantId", tenant), Long.class)).isEqualTo(1L);

        insertAction(tenant, group, "privacy.hold.manage", 1);
        assertThat(named().queryForObject(sql, new MapSqlParameterSource("tenantId", tenant), Long.class)).isZero();
    }

    @Test
    void 有効なprivacy管理者SQLは外部identityの承認とproviderの一致と拒否groupの非所属を要求する() throws Exception {
        String tenant = "nf07-dp34-" + UUID.randomUUID().toString().substring(0, 8);
        String identityTenant = tenant + "-idp";
        long provider = insertProvider(identityTenant);
        long otherProvider = insertProvider(identityTenant);
        long privacyAdmin = insertGroup(tenant, "privacy-admin");
        insertAction(tenant, privacyAdmin, "privacy.*", 0);
        long denying = insertGroup(tenant, "role-admin");
        insertAction(tenant, denying, "privacy.*", 1);

        long approved = insertUser(tenant, "dp34-approved", "管理者");
        member(tenant, approved, privacyAdmin);
        identity(identityTenant, approved, provider, "APPROVED");
        long pending = insertUser(tenant, "dp34-pending", "管理者");
        member(tenant, pending, privacyAdmin);
        identity(identityTenant, pending, provider, "PENDING");
        long otherIdp = insertUser(tenant, "dp34-other-idp", "管理者");
        member(tenant, otherIdp, privacyAdmin);
        identity(identityTenant, otherIdp, otherProvider, "APPROVED");
        long alsoDenied = insertUser(tenant, "dp34-denied", "管理者");
        member(tenant, alsoDenied, privacyAdmin);
        member(tenant, alsoDenied, denying);
        identity(identityTenant, alsoDenied, provider, "APPROVED");
        long wrongTenantIdentity = insertUser(tenant, "dp34-wrong-tenant", "管理者");
        member(tenant, wrongTenantIdentity, privacyAdmin);
        identity(tenant, wrongTenantIdentity, provider, "APPROVED");
        long sales = insertUser(tenant, "dp34-sales", "営業");
        member(tenant, sales, privacyAdmin);
        identity(identityTenant, sales, provider, "APPROVED");
        // Review P3-9: 論理削除された identity・無効な利用者は数えず、無効な group の拒否行では除外しない
        long deletedIdentity = insertUser(tenant, "dp34-deleted-identity", "管理者");
        member(tenant, deletedIdentity, privacyAdmin);
        identity(identityTenant, deletedIdentity, provider, "APPROVED");
        components.app.update("UPDATE t_user_external_identity SET deleted_flag = 1 WHERE user_id = ?", deletedIdentity);
        long inactive = insertUser(tenant, "dp34-inactive", "管理者");
        member(tenant, inactive, privacyAdmin);
        identity(identityTenant, inactive, provider, "APPROVED");
        components.app.update("UPDATE sys_user SET status = 0 WHERE id = ?", inactive);
        long disabledDenyGroup = insertGroup(tenant, "disabled-deny");
        insertAction(tenant, disabledDenyGroup, "privacy.*", 1);
        components.app.update("UPDATE m_permission_group SET enabled = 0 WHERE id = ?", disabledDenyGroup);
        long disabledDenyMember = insertUser(tenant, "dp34-disabled-deny", "管理者");
        member(tenant, disabledDenyMember, privacyAdmin);
        member(tenant, disabledDenyMember, disabledDenyGroup);
        identity(identityTenant, disabledDenyMember, provider, "APPROVED");

        String sql = sqlOf(UserPermissionGroupMapper.class, "selectEffectivePrivacyAdminUsernames",
                String.class, String.class, String.class, Long.class);
        List<String> usernames = named().queryForList(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenant).addValue("adminRole", "管理者")
                .addValue("identityTenantId", identityTenant).addValue("providerId", provider), String.class);

        assertThat(usernames).containsExactly(username(tenant, "dp34-approved"),
                username(tenant, "dp34-disabled-deny"));
    }

    @Test
    void privacy_admin以外でprivacyを許可するgroupのSQLは許可行を持つgroupだけを返す() throws Exception {
        String tenant = "nf07-dp34-" + UUID.randomUUID().toString().substring(0, 8);
        long privacyAdmin = insertGroup(tenant, "privacy-admin");
        insertAction(tenant, privacyAdmin, "privacy.*", 0);
        long copied = insertGroup(tenant, "copied-admin");
        insertAction(tenant, copied, "privacy.view", 0);
        long denying = insertGroup(tenant, "role-member");
        insertAction(tenant, denying, "privacy.*", 1);
        long disabled = insertGroup(tenant, "disabled-copy");
        insertAction(tenant, disabled, "privacy.*", 0);
        components.app.update("UPDATE m_permission_group SET enabled = 0 WHERE id = ?", disabled);
        String sql = sqlOf(PermissionGroupMapper.class, "selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin", String.class);

        assertThat(named().queryForList(sql, new MapSqlParameterSource("tenantId", tenant), String.class))
                .containsExactly("copied-admin");
    }

    private int journalEvents(String tenantId) {
        Integer count = journalRoot.queryForObject("SELECT COUNT(*) FROM pj_event WHERE tenant_id = ? "
                + "AND event_type = 'HOLD_STARTED'", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private NamedParameterJdbcTemplate named() {
        return new NamedParameterJdbcTemplate(components.app);
    }

    private static String sqlOf(Class<?> mapper, String method, Class<?>... parameters) throws Exception {
        String sql = String.join("\n", mapper.getMethod(method, parameters).getAnnotation(Select.class).value());
        return sql.replaceAll("#\\{(\\w+)}", ":$1");
    }

    private long insertGroup(String tenant, String groupKey) {
        long id = nextId();
        components.app.update("INSERT INTO m_permission_group (id, tenant_id, group_key, group_name, enabled, deleted_flag) "
                + "VALUES (?, ?, ?, ?, 1, 0)", id, tenant, groupKey, groupKey);
        return id;
    }

    private void insertAction(String tenant, long groupId, String actionKey, int denyFlag) {
        components.app.update("INSERT INTO t_permission_group_action (id, tenant_id, group_id, action_key, deny_flag, "
                + "deleted_flag) VALUES (?, ?, ?, ?, ?, 0)", nextId(), tenant, groupId, actionKey, denyFlag);
    }

    private long insertProvider(String identityTenant) {
        long id = nextId();
        components.app.update("INSERT INTO m_identity_provider (id, tenant_id, provider_type, issuer_uri, client_id, enabled, "
                + "deleted_flag) VALUES (?, ?, 'OIDC', ?, 'nf07-a1h-client', 1, 0)", id, identityTenant,
                "https://idp.example.invalid/" + id);
        return id;
    }

    /** username は tenant の接尾辞を付けて一意にする（{@link #username}）。 */
    private long insertUser(String tenant, String name, String role) {
        long id = nextId();
        components.app.update("INSERT INTO sys_user (id, username, password, real_name, role, status, tenant_id, deleted_flag) "
                + "VALUES (?, ?, 'unused', ?, ?, 1, ?, 0)", id, username(tenant, name), name, role, tenant);
        return id;
    }

    private static String username(String tenant, String name) {
        return name + "-" + tenant.substring(tenant.length() - 8);
    }

    private void member(String tenant, long userId, long groupId) {
        components.app.update("INSERT INTO t_user_permission_group (id, tenant_id, user_id, group_id, assigned_by, "
                + "deleted_flag) VALUES (?, ?, ?, ?, 1, 0)", nextId(), tenant, userId, groupId);
    }

    private void identity(String identityTenant, long userId, long providerId, String reviewStatus) {
        boolean approved = "APPROVED".equals(reviewStatus);
        long id = nextId();
        components.app.update("INSERT INTO t_user_external_identity (id, tenant_id, user_id, provider_id, subject, "
                        + "review_status, reviewed_at, reviewed_by, deleted_flag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, identityTenant, userId, providerId, "nf07-a1h-subject-" + id, reviewStatus,
                approved ? LocalDateTime.of(2026, 11, 1, 10, 0) : null, approved ? 1L : null);
    }

    /** 固定 seed と衝突しない ID（JVM 内で単調増加）。 */
    private static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong(
            9_100_000L + new java.util.Random().nextInt(10_000) * 1_000L);

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
