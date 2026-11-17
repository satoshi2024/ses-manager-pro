package com.ses.privacy.production.retention.policy;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.service.AuditLogService;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlConstraintRejections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NF07-REG: 実 MySQL（Flyway で V190 以降を適用）で current の並行切替を確かめる（production-bridge-phased-plan.md §4 の対象限定
 * テスト「MySQL で current 切替の並行」）。
 *
 * <ul>
 *   <li>同じ key の2件の CANDIDATE を2人の privacy-admin が同時に有効化する（どちらも current 無しを確認済み）: 後の有効化は先の
 *       transaction が lock した key の行を待ち（{@link InnoDbRecordLockWaits#waitEdgesOwnedBy} で確かめる。
 *       {@code data_locks.LOCK_DATA} の値には依存しない）、先の commit 後に実際の current が確認した値と違うため 409
 *       （{@code policyCurrentChanged}）になる。current は1件だけ。</li>
 *   <li>current がある key で、2人が別の CANDIDATE を同じ current を確認して同時に有効化する: 先だけが旧 current を SUPERSEDED に
 *       して切り替わり、後は 409。確認していない policy を SUPERSEDED にしない。</li>
 *   <li>DB の制約: 生成列 {@code current_key} の UNIQUE が2件目の CURRENT を、{@code chk_retention_policy_activation} が登録者と
 *       同じ有効化者を拒否する。</li>
 * </ul>
 * 鍵は test-only（{@link PolicyEnvelopeTestSigner}）、時計は固定。各 test は専用の tenant を使い、commit した行は class 専用の
 * container DB に残る（他の test の行は読まない）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class PrivacyPolicyRegistrationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf07_policy_registration")
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

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final long REGISTRANT = 201L;
    private static final long ACTIVATOR_A = 202L;
    private static final long ACTIVATOR_B = 203L;
    private static final String POLICY_ID = "nf08-ai-prompt-log";

    @Autowired
    private RetentionPolicyMapper policyMapper;
    @Autowired
    private PiiDataElementMapper dataElementMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenant;
    private Clock clock;
    private PolicyEnvelopeTestSigner signer;
    private PolicyRegistrationVerifier verifier;

    @BeforeEach
    void setUp() {
        tenant = "nf07-policy-mysql-" + UUID.randomUUID();
        clock = Clock.fixed(NOW, ZoneId.of("Asia/Tokyo"));
        signer = new PolicyEnvelopeTestSigner(tenant, NOW);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        verifier = ProductionPrivacyComponents.policyRegistrationVerifier(prod, signer.registry(),
                ref -> RevocationStatus.ACTIVE, clock);
        jdbc.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref) "
                + "VALUES (?, ?, 'AI_PAYLOAD', 't_ai_recommendation_run.prompt')", tenant, PolicyEnvelopeTestSigner.ELEMENT);
    }

    @Test
    void current無しの2件を同時に有効化すると後の有効化はkeyの行のlockを待ち先のcommit後に409となりcurrentは1件() throws Exception {
        long r1 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r1").request()).id();
        long r2 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r2").request()).id();

        Outcome outcome = race(() -> service(ACTIVATOR_A).activate(r1, new PrivacyPolicyActivationRequest(null)),
                () -> service(ACTIVATOR_B).activate(r2, new PrivacyPolicyActivationRequest(null)));

        assertThat(outcome.first()).isNull();
        assertConflict(outcome.second(), "error.privacy.policyCurrentChanged");
        assertThat(status(r1)).isEqualTo("CURRENT");
        assertThat(status(r2)).isEqualTo("CANDIDATE");
        assertThat(currentRows()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT activated_by FROM m_retention_policy WHERE id = ?", Long.class, r1))
                .isEqualTo(ACTIVATOR_A);
    }

    @Test
    void 同じcurrentを確認した2件の切替は先だけが旧currentをSUPERSEDEDにし後は409() throws Exception {
        long r1 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r1").request()).id();
        long r2 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r2").request()).id();
        long r3 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r3").request()).id();
        service(ACTIVATOR_A).activate(r1, new PrivacyPolicyActivationRequest(null));

        Outcome outcome = race(() -> service(ACTIVATOR_A).activate(r2, new PrivacyPolicyActivationRequest(r1)),
                () -> service(ACTIVATOR_B).activate(r3, new PrivacyPolicyActivationRequest(r1)));

        assertThat(outcome.first()).isNull();
        assertConflict(outcome.second(), "error.privacy.policyCurrentChanged");
        assertThat(status(r1)).isEqualTo("SUPERSEDED");
        assertThat(status(r2)).isEqualTo("CURRENT");
        assertThat(status(r3)).isEqualTo("CANDIDATE");
        assertThat(currentRows()).isEqualTo(1L);

        // 後の有効化者が新しい current を確認し直せば切り替えられる
        PrivacyPolicyRegistrationResult retried = service(ACTIVATOR_B).activate(r3, new PrivacyPolicyActivationRequest(r2));
        assertThat(retried.supersededId()).isEqualTo(r2);
        assertThat(status(r2)).isEqualTo("SUPERSEDED");
        assertThat(currentRows()).isEqualTo(1L);
    }

    @Test
    void DBは同じkeyの2件目のCURRENTと登録者と同じ有効化者を拒否する() {
        long r1 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r1").request()).id();
        long r2 = service(REGISTRANT).register(signer.sign(POLICY_ID, "r2").request()).id();
        service(ACTIVATOR_A).activate(r1, new PrivacyPolicyActivationRequest(null));

        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE m_retention_policy SET status = 'CURRENT', "
                + "activated_by = ?, activated_at = NOW(6) WHERE id = ?", ACTIVATOR_B, r2), 1062,
                "uk_retention_policy_current");
        jdbc.update("UPDATE m_retention_policy SET status = 'SUPERSEDED' WHERE id = ?", r1);
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE m_retention_policy SET status = 'CURRENT', "
                        + "activated_by = ?, activated_at = NOW(6) WHERE id = ?", REGISTRANT, r2),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_retention_policy_activation");
        assertThat(status(r2)).isEqualTo("CANDIDATE");
    }

    // --- helpers ---

    private PrivacyPolicyRegistrationService service(long userId) {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        decision(approval.getD01(), "D01-NF07-TEST-001");
        decision(approval.getD02(), "D02-NF07-TEST-001");
        decision(approval.getD03(), "D03-NF07-TEST-001");
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenant);
        return new PrivacyPolicyRegistrationService(mock(PrivacyAccessGuard.class), oidc, approval, verifier,
                policyMapper, dataElementMapper, new PrivacyPolicyAudit(mock(AuditLogService.class)),
                new TransactionTemplate(transactionManager), clock,
                () -> new PrivacyPolicyRegistrationService.Actor(tenant, userId, "privacy-admin-" + userId));
    }

    private static void decision(PrivacyApprovalProperties.DecisionReference reference, String decisionId) {
        reference.setDecisionId(decisionId);
        reference.setSignedOn("2026-11-01");
        reference.setExpiresOn("2027-06-01");
    }

    /**
     * 先の操作を transaction の中で実行して commit 前に止め、後の操作が {@code m_retention_policy} の行の lock を待つことを確かめて
     * から先を commit する。戻り値は各操作の例外（成功なら null）。
     */
    private Outcome race(Supplier<?> firstOperation, Supplier<?> secondOperation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong blocker = new AtomicLong();
        try {
            Future<Throwable> first = pool.submit(() -> capture(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        blocker.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        firstOperation.get();
                        firstApplied.countDown();
                        await(releaseFirst);
                    })));
            assertTrue(firstApplied.await(20, TimeUnit.SECONDS), "先の有効化が commit 前の状態に届く");
            Future<Throwable> second = pool.submit(() -> capture(secondOperation::get));
            awaitWaitOn(blocker.get());
            releaseFirst.countDown();
            return new Outcome(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitWaitOn(long blockerConnectionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!InnoDbRecordLockWaits.waitEdgesOwnedBy(jdbc, blockerConnectionId, "m_retention_policy", null).isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("後の有効化が m_retention_policy の行の lock を待っていない: "
                + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, blockerConnectionId));
    }

    private static Throwable capture(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("解放待ちが時間切れ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertConflict(Throwable error, String key) {
        assertThat(error).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo(key);
        });
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM m_retention_policy WHERE id = ?", String.class, id);
    }

    private long currentRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM m_retention_policy WHERE tenant_id = ? AND status = 'CURRENT'",
                Long.class, tenant);
        return count == null ? 0L : count;
    }

    private record Outcome(Throwable first, Throwable second) {
    }
}
