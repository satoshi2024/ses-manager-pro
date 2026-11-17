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
        // subject の ID は値の境界で判定する（乱数の event hash の一部に同じ数字列が現れても誤検出しない）
        assertThat(components.auditUris).allSatisfy(uri -> assertThat(java.net.URLDecoder.decode(uri,
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("ENGINEER")
                .doesNotContainPattern("(^|[^0-9A-Za-z])501([^0-9A-Za-z]|$)"));
    }

    /**
     * RB-NF07-HOLD delta review D-P3-3: app 側の {@code @@server_uuid} 照会（{@link ApplicationServerUuidQuery}）は借りた接続の
     * network timeout を一時的に 15 秒へ上げて finally で戻す。戻した値と、Hikari が返却時に行う復元（pool の既定 0）が実際の
     * Connector/J・Hikari の接続で効き、主 pool へ変更済みの接続が残らないことを、pool の全接続（上限 8）を借りて確かめる。
     */
    @Test
    void app側のserver_uuid照会の後に主poolの全接続を借りてもnetwork_timeoutは既定の0に戻っている() throws Exception {
        assertThat(components.journal.availability()).as("前提: 可用性の判定で app 側の照会を通る")
                .isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        ApplicationServerUuidQuery query = new ApplicationServerUuidQuery(new JdbcTemplate(appPool));
        for (int i = 0; i < 16; i++) {
            assertThat(query.get()).as("前提: 照会が実際に値を返す").isNotBlank();
        }
        assertThat(appPool.getMaximumPoolSize()).isEqualTo(8);
        List<java.sql.Connection> borrowed = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < appPool.getMaximumPoolSize(); i++) {
                java.sql.Connection connection = appPool.getConnection();
                borrowed.add(connection);
                assertThat(connection.getNetworkTimeout()).as("借りた接続 %d の network timeout", i).isZero();
            }
        } finally {
            for (java.sql.Connection connection : borrowed) {
                connection.close();
            }
        }
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

    /**
     * A1H-1 delta review P2-N1: 再送の直前の {@code pending_since} は、追随位置の行の lock を取った後に時計から取る。
     * 別の接続（破壊的経路の {@code evaluateLocked} と同じく行の {@code FOR UPDATE} を transaction の終了まで持つ）が lock を
     * 持つ間に時計を進め、解放の後に再送した append の時点で {@code pending_since} が解放の時刻以後であることを確かめる。
     * 待ちの確認は {@code InnoDbRecordLockWaits}（LOCK_DATA に依存しない）。
     */
    @Test
    void 再送の直前は追随位置の行のlockを待った後に時計を読むのでpending_sinceは解放の時刻より古くならない() throws Exception {
        PrivacyHoldTestFixture.MutableClock clock = new PrivacyHoldTestFixture.MutableClock(
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        PrivacyHoldMySqlComponents local = new PrivacyHoldMySqlComponents(components.tenantId, appPool, production,
                appenderPool, JOURNAL_ID, Duration.ofMinutes(2), clock);
        JdbcTemplate observer = new JdbcTemplate(new DriverManagerDataSource(APP.getJdbcUrl(), "root", ROOT_PASSWORD));
        TransactionTemplate blockerTransaction = new TransactionTemplate(new DataSourceTransactionManager(appPool));
        java.util.concurrent.atomic.AtomicReference<Instant> releasedAt = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Instant> pendingAtResend = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Future<Boolean>> blocker = new java.util.concurrent.atomic.AtomicReference<>();
        local.port.loseAckAfterCommit.set(1);
        local.port.beforeAppend = call -> {
            if (call == 1) {
                blocker.set(executor.submit(() -> blockerTransaction.execute(status -> {
                    JdbcTemplate held = new JdbcTemplate(appPool);
                    long connectionId = held.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    held.queryForList("SELECT tenant_id FROM t_privacy_projection_state WHERE tenant_id = ? "
                            + "AND journal_id = ? FOR UPDATE", local.tenantId, JOURNAL_ID);
                    locked.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    boolean waited = false;
                    while (!waited && System.nanoTime() < deadline) {
                        waited = !com.ses.test.InnoDbRecordLockWaits.waitEdgesOwnedBy(observer, connectionId,
                                "t_privacy_projection_state", "PRIMARY").isEmpty();
                        if (!waited) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        }
                    }
                    // 再送の直前の CAS が lock を待っている間に時間が過ぎる
                    clock.advance(Duration.ofMinutes(3));
                    releasedAt.set(clock.instant());
                    return waited;
                })));
                try {
                    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            } else {
                pendingAtResend.set(local.store.stateRows(local.tenantId).get(0).pendingSince());
            }
        };
        try {
            PrivacyHoldStartResult result = local.service.start(local.subject("ENGINEER:506"));

            assertThat(result.status()).isEqualTo("ACTIVE");
            assertThat(blocker.get().get(60, TimeUnit.SECONDS)).as("再送の直前の CAS が行 lock を待った").isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(local.port.appendCalls.get()).isEqualTo(2);
        assertThat(pendingAtResend.get()).as("pending_since は lock の解放より後の時計の値").isAfterOrEqualTo(releasedAt.get());
        assertThat(journalEvents(local.tenantId)).isEqualTo(1);
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

    // ---- A1H-1 delta Review P3-N3・P3-N4: 起動時の人数の SQL と実行時の判定（AuthorizationServiceImpl）の一致を実 MySQL で ----

    /**
     * 同じ fixture の各利用者について「起動時の人数の SQL に出る ⇔ 実行時に privacy を使える」を実 MySQL で確かめる。拒否の判定は
     * 両側とも同じ表・同じ照合順序（{@code utf8mb4_unicode_ci}）で比べるため、大文字小文字の違う拒否行（{@code PRIVACY.*}・
     * {@code Privacy.Hold.View}）も両側で拒否として効く。無効・論理削除の group、論理削除の所属行・拒否行は両側とも評価しない。
     * 一部だけの拒否（{@code privacy.dsar.view}・{@code PRIVACY.DSAR.VIEW}）は、起動時は数えず実行時はその action だけを拒否する
     * （少なく数える＝起動を止める側の意図した差）。
     */
    @Test
    void 起動時のprivacy管理者SQLと実行時の判定は無効groupと大文字小文字の違う拒否行で一致する() throws Exception {
        String tenant = "nf07-dp34-" + UUID.randomUUID().toString().substring(0, 8);
        String identityTenant = tenant + "-idp";
        long provider = insertProvider(identityTenant);
        long privacyAdmin = insertGroup(tenant, "privacy-admin");
        insertAction(tenant, privacyAdmin, "privacy.*", 0);

        java.util.Map<Long, Boolean> expected = new java.util.LinkedHashMap<>();
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "plain", null), true);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "enabled-deny",
                denyGroup(tenant, "enabled-deny", "privacy.*")), false);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "upper-wildcard",
                denyGroup(tenant, "upper-wildcard", "PRIVACY.*")), false);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "upper-action",
                denyGroup(tenant, "upper-action", "Privacy.Hold.View")), false);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "star-deny",
                denyGroup(tenant, "star-deny", "*")), false);
        long disabled = denyGroup(tenant, "disabled-deny", "PRIVACY.*");
        components.app.update("UPDATE m_permission_group SET enabled = 0 WHERE id = ?", disabled);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "disabled-deny", disabled), true);
        long deletedGroup = denyGroup(tenant, "deleted-group", "privacy.*");
        components.app.update("UPDATE m_permission_group SET deleted_flag = 1 WHERE id = ?", deletedGroup);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "deleted-group", deletedGroup), true);
        long deletedRow = denyGroup(tenant, "deleted-row", "privacy.*");
        components.app.update("UPDATE t_permission_group_action SET deleted_flag = 1 WHERE group_id = ?", deletedRow);
        expected.put(consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "deleted-row", deletedRow), true);
        long deletedMembershipGroup = denyGroup(tenant, "deleted-membership", "privacy.*");
        long deletedMembership = consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "deleted-membership",
                deletedMembershipGroup);
        components.app.update("UPDATE t_user_permission_group SET deleted_flag = 1 WHERE user_id = ? AND group_id = ?",
                deletedMembership, deletedMembershipGroup);
        expected.put(deletedMembership, true);
        long partial = consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "partial",
                denyGroup(tenant, "partial", "privacy.dsar.view"));
        long upperPartial = consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "upper-partial",
                denyGroup(tenant, "upper-partial", "PRIVACY.DSAR.VIEW"));

        List<String> counted = effectivePrivacyAdmins(tenant, identityTenant, provider);
        com.ses.service.security.impl.AuthorizationServiceImpl runtime = runtimeAuthorization(tenant);
        for (java.util.Map.Entry<Long, Boolean> entry : expected.entrySet()) {
            String name = usernameOf(entry.getKey());
            boolean allowed = runtime.isAllowedForActor(tenant, entry.getKey(), "管理者", "privacy.hold.view");
            assertThat(counted.contains(name)).as("起動時の人数に数えるか: %s", name).isEqualTo(entry.getValue());
            assertThat(allowed).as("実行時に privacy を使えるか: %s", name).isEqualTo(entry.getValue());
        }
        for (long user : List.of(partial, upperPartial)) {
            assertThat(counted).as("一部だけの拒否は数えない").doesNotContain(usernameOf(user));
            assertThat(runtime.isAllowedForActor(tenant, user, "管理者", "privacy.hold.view")).isTrue();
            assertThat(runtime.isAllowedForActor(tenant, user, "管理者", "privacy.dsar.view")).isFalse();
        }
    }

    /**
     * 許可の側も大文字小文字を区別せずに揃う: privacy-admin の許可行が {@code PRIVACY.*} でも起動時の group の検査
     * （{@code countPrivacyAdminGroupsGrantingPrivacy}）は許可として数え、実行時も許可する。privacy-admin 以外の group の
     * {@code PRIVACY.VIEW} の許可行は、実行時に開いてしまうため起動時の検査（{@code selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin}）
     * が見つける（起動を止める）。
     */
    @Test
    void 大文字の許可行も起動時の検査と実行時の判定で同じく許可として扱う() throws Exception {
        String tenant = "nf07-dp34-" + UUID.randomUUID().toString().substring(0, 8);
        String identityTenant = tenant + "-idp";
        long provider = insertProvider(identityTenant);
        long privacyAdmin = insertGroup(tenant, "privacy-admin");
        insertAction(tenant, privacyAdmin, "PRIVACY.*", 0);
        long admin = consistencyAdmin(tenant, identityTenant, provider, privacyAdmin, "upper-allow", null);
        long copied = insertGroup(tenant, "copied-upper");
        insertAction(tenant, copied, "PRIVACY.VIEW", 0);
        long outsider = insertUser(tenant, "dp34-copied-only", "管理者");
        member(tenant, outsider, copied);

        com.ses.service.security.impl.AuthorizationServiceImpl runtime = runtimeAuthorization(tenant);
        assertThat(named().queryForObject(sqlOf(PermissionGroupMapper.class, "countPrivacyAdminGroupsGrantingPrivacy",
                String.class), new MapSqlParameterSource("tenantId", tenant), Long.class)).isEqualTo(1L);
        assertThat(runtime.isAllowedForActor(tenant, admin, "管理者", "privacy.hold.view")).isTrue();
        assertThat(effectivePrivacyAdmins(tenant, identityTenant, provider)).containsExactly(usernameOf(admin));

        assertThat(runtime.isAllowedForActor(tenant, outsider, "管理者", "privacy.view"))
                .as("privacy-admin 以外の大文字の許可行も実行時は開く").isTrue();
        assertThat(named().queryForList(sqlOf(PermissionGroupMapper.class,
                        "selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin", String.class),
                new MapSqlParameterSource("tenantId", tenant), String.class))
                .as("その group を起動時の検査が見つける").containsExactly("copied-upper");
    }

    /** 拒否行を1つ持つ有効な group。 */
    private long denyGroup(String tenant, String key, String actionKey) {
        long group = insertGroup(tenant, "consistency-" + key);
        insertAction(tenant, group, actionKey, 1);
        return group;
    }

    /** privacy-admin（と {@code extraGroup}）に所属し、provider に APPROVED の外部 identity を持つ有効な管理者。 */
    private long consistencyAdmin(String tenant, String identityTenant, long provider, long privacyAdmin, String name,
                                  Long extraGroup) {
        long user = insertUser(tenant, "dp34-c-" + name, "管理者");
        member(tenant, user, privacyAdmin);
        if (extraGroup != null) {
            member(tenant, user, extraGroup);
        }
        identity(identityTenant, user, provider, "APPROVED");
        return user;
    }

    private String usernameOf(long userId) {
        return components.app.queryForObject("SELECT username FROM sys_user WHERE id = ?", String.class, userId);
    }

    private List<String> effectivePrivacyAdmins(String tenant, String identityTenant, long provider) throws Exception {
        String sql = sqlOf(UserPermissionGroupMapper.class, "selectEffectivePrivacyAdminUsernames",
                String.class, String.class, String.class, Long.class);
        return named().queryForList(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenant).addValue("adminRole", "管理者")
                .addValue("identityTenantId", identityTenant).addValue("providerId", provider), String.class);
    }

    /**
     * 実行時の判定（本番の {@code AuthorizationServiceImpl}）を、本番の mapper（MyBatis-Plus の wrapper と {@code @TableLogic}）で
     * application container に向けて組み立てる。配備の tenant は fixture の tenant にする（{@code isAllowedForActor} は配備の tenant
     * 以外の利用者を拒否するため）。
     */
    private static com.ses.service.security.impl.AuthorizationServiceImpl runtimeAuthorization(String tenant)
            throws Exception {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(appPool);
        factory.setConfiguration(configuration);
        var sessions = factory.getObject();
        for (Class<?> mapper : List.of(UserPermissionGroupMapper.class, PermissionGroupMapper.class,
                com.ses.mapper.PermissionGroupActionMapper.class)) {
            sessions.getConfiguration().addMapper(mapper);
        }
        var session = new org.mybatis.spring.SqlSessionTemplate(sessions);
        com.ses.config.OidcSecurityProperties oidc = new com.ses.config.OidcSecurityProperties();
        oidc.setTenantId(tenant);
        return new com.ses.service.security.impl.AuthorizationServiceImpl(
                session.getMapper(UserPermissionGroupMapper.class),
                session.getMapper(com.ses.mapper.PermissionGroupActionMapper.class),
                session.getMapper(PermissionGroupMapper.class), oidc);
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
