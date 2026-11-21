package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.migration.PrivacyJournalFlywayJobConfig;
import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseBinding;
import com.ses.privacy.foundation.HoldReleaseJournalPort;
import com.ses.privacy.foundation.HoldReleaseTestAuthorities;
import com.ses.privacy.foundation.HoldReleaseVerifier;
import com.ses.privacy.foundation.JournalConflictException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.foundation.ProductionJournalChainVerification;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.VerifiedHoldRelease;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 hold の解除の実 MySQL（NF07-REL）。application DB（全 migration）と Journal（本番 adapter・本番の索引）の2 container で、
 * 二者の署名付き proof（test-only 鍵）による HOLD_RELEASED の append、同じ command の再送が元の ACK を返すこと、
 * journal head と hold revision の CAS（並行の解除・古い hold revision・別の hold ID）、ACK 不明の後の再送と reconciler の補い、
 * hold 開始との pending の直列化を確かめる。lock の検査に {@code LOCK_DATA} を使わない。
 */
@Tag("mysql")
@Testcontainers
class PrivacyHoldReleaseMySqlTest {

    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String JOURNAL_ID = "journal-rel-mysql";
    private static final String ISSUER = "issuer-a";
    private static final String APPROVER = "approver-b";
    private static final String REASON = "訴訟が終結したため保全を解除する";
    private static final String REASON_CODE = "LITIGATION_CLOSED";
    private static final String TICKET = "TICKET-MYSQL-2";
    /**
     * 固定の時計（決定ID・trust anchor の有効期間・claim の期限を同じ時刻で判定し、実行日によって結果が変わらないようにする。
     * 本番 adapter の event 時刻・hold の service・verifier の全てに同じ時計を渡す）。
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), java.time.ZoneOffset.UTC);

    @Container
    static final MySQLContainer<?> JOURNAL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName(PrivacyJournalTestPrincipals.SCHEMA)
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");

    @Container
    static final com.ses.test.MySQLContainer<?> APP = new com.ses.test.MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword(ROOT_PASSWORD);

    private static HikariDataSource appPool;
    private static HikariDataSource appenderPool;
    private static JdbcTemplate journalRoot;
    private static PrivacyJournalPort production;
    private static MockEnvironment prod;

    private PrivacyHoldMySqlComponents components;
    private HoldReleaseTestAuthorities authorities;
    private HoldReleaseVerifier verifier;
    private PrivacyHoldReleaseService release;

    @BeforeAll
    static void startContainers() {
        Flyway.configure().dataSource(APP.getJdbcUrl(), "root", ROOT_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        PrivacyJournalFlywayJobConfig.configure(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD).load().migrate();
        journalRoot = new JdbcTemplate(new DriverManagerDataSource(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD));
        String appender = PrivacyJournalTestPrincipals.uniqueName("nf07_rel");
        String appenderPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, appender, appenderPassword);
        appenderPool = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), appender, appenderPassword);
        appPool = new HikariDataSource();
        appPool.setJdbcUrl(APP.getJdbcUrl());
        appPool.setUsername("root");
        appPool.setPassword(ROOT_PASSWORD);
        appPool.setMaximumPoolSize(8);
        prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        production = ProductionPrivacyComponents.productionPrivacyJournal(prod, appenderPool, CLOCK);
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
        String tenant = "nf07-rel-" + UUID.randomUUID().toString().substring(0, 8);
        components = new PrivacyHoldMySqlComponents(tenant, appPool, production, appenderPool, JOURNAL_ID,
                Duration.ofMinutes(2), CLOCK);
        components.seedCatalog();
        components.approval.getD03().setDecisionId("D03-NF07-MYSQL-001");
        components.approval.getD03().setSignedOn("2026-11-01");
        components.approval.getD03().setExpiresOn("2027-06-01");
        authorities = new HoldReleaseTestAuthorities();
        authorities.register(ISSUER, "sys_user:20", tenant);
        authorities.register(APPROVER, "sys_user:30", tenant);
        verifier = ProductionPrivacyComponents.holdReleaseVerifier(prod, authorities.registry(),
                ref -> RevocationStatus.ACTIVE, CLOCK);
        release = components.releaseService(verifier, authorities.registry(),
                () -> new PrivacyHoldService.Actor(tenant, 1L, "privacy-admin-mysql"));
    }

    @Test
    void 二者の署名付きproofで本番adapterにHOLD_RELEASEDを1件appendしprojectionとgateがJournalと一致する() {
        PrivacyHoldStartResult started = components.service.start(components.subject("ENGINEER:801"));

        PrivacyHoldReleaseResult result = release.release(started.holdId(), request(started.holdId(), newCommandId()));

        assertThat(result.releaseRevision()).isEqualTo(2);
        assertThat(journalEvents("HOLD_RELEASED")).isEqualTo(1);
        JournalPartition partition = new JournalPartition(components.tenantId, "ENGINEER:801");
        JournalHead head = production.head(partition);
        assertThat(head.held()).isFalse();
        assertThat(ProductionJournalChainVerification.verify(partition, head, production.events(partition))).isTrue();
        Timestamp journalTime = journalRoot.queryForObject("SELECT created_at FROM pj_event WHERE tenant_id = ? "
                + "AND event_type = 'HOLD_RELEASED'", Timestamp.class, components.tenantId);
        LocalDateTime releasedAt = components.app.queryForObject("SELECT released_at FROM t_legal_hold WHERE tenant_id = ? "
                + "AND hold_id = ? AND status = 'RELEASED'", LocalDateTime.class, components.tenantId, started.holdId());
        assertThat(releasedAt.atZone(ZoneId.of("Asia/Tokyo")).toInstant())
                .isEqualTo(production.events(partition).get(1).createdAt());
        assertThat(journalTime).isNotNull();
        // 解除の event は両 claim の digest を action_id に持ち、event の hash に含まれる（REL review P3-6）
        String actionId = journalRoot.queryForObject("SELECT action_id FROM pj_event WHERE tenant_id = ? "
                + "AND event_type = 'HOLD_RELEASED'", String.class, components.tenantId);
        assertThat(actionId).matches("[0-9a-f]{64}").isEqualTo(production.events(partition).get(1).actionId());
        PrivacyHoldGate.Decision decision = components.gate.evaluate(components.tenantId);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).isEmpty();
        // subject の ID は値の境界で判定する（STARTED の監査の event hash の先頭16桁に「801」が現れて誤検出した。Runner 0fd7ef52）
        assertThat(components.auditUris).allSatisfy(uri -> assertThat(java.net.URLDecoder.decode(uri,
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("ENGINEER")
                .doesNotContainPattern("(^|[^0-9A-Za-z])801([^0-9A-Za-z]|$)"));
    }

    @Test
    void 同じcommandの再送はserviceでも本番adapterでも元のACKを返しeventを増やさない() {
        PrivacyHoldStartResult started = components.service.start(components.subject("ENGINEER:802"));
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId());
        VerifiedHoldRelease proof = proofOf(request);

        PrivacyHoldReleaseResult first = release.release(started.holdId(), request);
        PrivacyHoldReleaseResult resent = release.release(started.holdId(), request);
        JournalAck direct = ((HoldReleaseJournalPort) production).appendHoldRelease(proof);

        assertThat(resent.replayed()).isTrue();
        assertThat(resent.releaseRevision()).isEqualTo(first.releaseRevision());
        assertThat(direct.replayed()).isTrue();
        assertThat(direct.revision()).isEqualTo(first.releaseRevision());
        assertThat(journalEvents(null)).isEqualTo(2);
        assertThat(components.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ? AND status = 'RELEASED'",
                components.tenantId)).isEqualTo(1);
    }

    @Test
    void ACK不明の後の再送は元のACKを受け取り二重に解除しない() {
        PrivacyHoldStartResult started = components.service.start(components.subject("ENGINEER:803"));
        int before = components.port.appendCalls.get();
        components.port.loseAckAfterCommit.set(1);

        PrivacyHoldReleaseResult result = release.release(started.holdId(), request(started.holdId(), newCommandId()));

        assertThat(components.port.appendCalls.get() - before).isEqualTo(2);
        assertThat(result.replayed()).isTrue();
        assertThat(journalEvents("HOLD_RELEASED")).isEqualTo(1);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isNull();
    }

    @Test
    void ACK不明が続けばpendingを残し_reconcilerがJournalの解除をprojectionへ補う() {
        PrivacyHoldStartResult started = components.service.start(components.subject("ENGINEER:804"));
        components.port.loseAckAfterCommit.set(2);

        assertThatThrownBy(() -> release.release(started.holdId(), request(started.holdId(), newCommandId())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        assertThat(components.gate.evaluate(components.tenantId).state()).isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);

        Map<PrivacyHoldProjectionReconciler.Outcome, Integer> outcomes = components.reconciler.reconcileAll();

        assertThat(outcomes).containsKey(PrivacyHoldProjectionReconciler.Outcome.PENDING_PROJECTED);
        assertThat(components.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ? AND status = 'RELEASED'",
                components.tenantId)).isEqualTo(1);
        assertThat(components.gate.evaluate(components.tenantId).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
    }

    @Test
    void 同じheadに束縛した2つの解除を並行にappendすると本番adapterのhead_CASで1件だけ確定する() throws Exception {
        PrivacyHoldStartResult started = components.service.start(components.subject("ENGINEER:805"));
        VerifiedHoldRelease first = proofOf(request(started.holdId(), newCommandId()));
        VerifiedHoldRelease second = proofOf(request(started.holdId(), newCommandId()));
        HoldReleaseJournalPort releasePort = (HoldReleaseJournalPort) production;
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (VerifiedHoldRelease proof : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        return releasePort.appendHoldRelease(proof);
                    } catch (JournalConflictException e) {
                        return e;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(JournalAck.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(JournalConflictException.class::isInstance).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(journalEvents("HOLD_RELEASED")).isEqualTo(1);
        JournalPartition partition = new JournalPartition(components.tenantId, "ENGINEER:805");
        assertThat(production.head(partition).revision()).isEqualTo(2);
    }

    @Test
    void 古いhold_revisionと別のhold_IDのproofは本番adapterが拒否しheadを変えない() {
        PrivacyHoldStartResult first = components.service.start(components.subject("ENGINEER:806"));
        release.release(first.holdId(), request(first.holdId(), newCommandId()));
        PrivacyHoldStartResult second = components.service.start(components.subject("ENGINEER:806"));
        assertThat(second.journalRevision()).isEqualTo(3);
        JournalPartition partition = new JournalPartition(components.tenantId, "ENGINEER:806");
        JournalHead head = production.head(partition);
        HoldReleaseJournalPort releasePort = (HoldReleaseJournalPort) production;

        // 現在の head に束縛していても、hold revision が古い（解除前の hold の revision）
        HoldReleaseBinding staleRevision = new HoldReleaseBinding(components.tenantId, "ENGINEER:806", second.holdId(),
                1, HoldReleaseBinding.reasonHash(REASON_CODE, TICKET, REASON), newCommandId(), head.revision(),
                head.eventHash(), "sys_user:1", "sys_user:1");
        assertThatThrownBy(() -> releasePort.appendHoldRelease(mint(staleRevision, "SUBJECT")))
                .isInstanceOf(JournalConflictException.class);
        // 現在の head に束縛していても、解除済みの別の hold ID
        HoldReleaseBinding otherHold = new HoldReleaseBinding(components.tenantId, "ENGINEER:806", first.holdId(),
                3, HoldReleaseBinding.reasonHash(REASON_CODE, TICKET, REASON), newCommandId(), head.revision(),
                head.eventHash(), "sys_user:1", "sys_user:1");
        assertThatThrownBy(() -> releasePort.appendHoldRelease(mint(otherHold, "SUBJECT")))
                .isInstanceOf(JournalConflictException.class);

        JournalHead after = production.head(partition);
        assertThat(after.revision()).isEqualTo(head.revision());
        assertThat(after.eventHash()).isEqualTo(head.eventHash());
        assertThat(after.held()).isTrue();
        assertThat(components.gate.evaluate(components.tenantId).heldSubjects()).containsExactly("ENGINEER:806");
    }

    @Test
    void 解除のappend中の同じtenantのhold開始はpendingのCASで409になる() throws Exception {
        PrivacyHoldStartResult started = components.service.start(components.subject("ENGINEER:807"));
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        components.port.entered = entered;
        components.port.release = resume;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PrivacyHoldReleaseResult> releasing = executor.submit(() -> release.release(started.holdId(), request));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            components.port.release = null;

            assertThatThrownBy(() -> components.service.start(components.subject("ENGINEER:808")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getMessageKey()).isEqualTo("error.privacy.holdStartInProgress");
                    });
            resume.countDown();
            assertThat(releasing.get(60, TimeUnit.SECONDS).status()).isEqualTo("RELEASED");
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
        assertThat(journalRoot.queryForObject("SELECT COUNT(*) FROM pj_event WHERE tenant_id = ? AND subject_ref = ?",
                Integer.class, components.tenantId, "ENGINEER:808")).isZero();
    }

    // --- helpers ---

    private int journalEvents(String type) {
        Integer count = type == null
                ? journalRoot.queryForObject("SELECT COUNT(*) FROM pj_event WHERE tenant_id = ?", Integer.class,
                components.tenantId)
                : journalRoot.queryForObject("SELECT COUNT(*) FROM pj_event WHERE tenant_id = ? AND event_type = ?",
                Integer.class, components.tenantId, type);
        return count == null ? 0 : count;
    }

    private static String newCommandId() {
        return "hr-" + UUID.randomUUID().toString().replace("-", "");
    }

    private PrivacyHoldReleaseRequest request(String holdId, String commandId) {
        PrivacyHoldProjectionStore.HoldRow hold = components.store.hold(components.tenantId, holdId).orElseThrow();
        JournalHead head = production.head(new JournalPartition(components.tenantId, hold.partitionRef()));
        HoldReleaseBinding binding = new HoldReleaseBinding(components.tenantId, hold.partitionRef(), holdId,
                hold.journalRevision(), HoldReleaseBinding.reasonHash(REASON_CODE, TICKET, REASON), commandId,
                head.revision(), head.eventHash(), "sys_user:1", PrivacyHoldReleaseService.starterRef(hold));
        Instant now = CLOCK.instant();
        HoldReleaseAuthorityClaim issuer = authorities.sign(ISSUER, HoldReleaseAuthorityClaim.Role.ISSUER, binding,
                now.minusSeconds(60), now.plus(Duration.ofMinutes(30)));
        HoldReleaseAuthorityClaim approver = authorities.sign(APPROVER, HoldReleaseAuthorityClaim.Role.APPROVER, binding,
                now.minusSeconds(60), now.plus(Duration.ofMinutes(30)));
        return new PrivacyHoldReleaseRequest(REASON_CODE, REASON, TICKET,
                new PrivacyHoldReleaseRequest.Binding(binding.tenantId(), binding.subjectRef(), binding.holdId(),
                        binding.expectedHoldRevision(), binding.reasonHash(), binding.commandId(),
                        binding.expectedJournalRevision(), binding.expectedJournalHash(), binding.requesterRef(),
                        binding.holdStarterRef()),
                claim(issuer), claim(approver));
    }

    private VerifiedHoldRelease proofOf(PrivacyHoldReleaseRequest request) {
        PrivacyHoldReleaseRequest.Binding b = request.binding();
        HoldReleaseBinding binding = new HoldReleaseBinding(b.tenantId(), b.subjectRef(), b.holdId(),
                b.expectedHoldRevision(), b.reasonHash(), b.commandId(), b.expectedJournalRevision(),
                b.expectedJournalHash(), b.requesterRef(), b.holdStarterRef());
        return mint(binding, "SUBJECT");
    }

    private VerifiedHoldRelease mint(HoldReleaseBinding binding, String targetType) {
        Instant now = CLOCK.instant();
        return verifier.verify(binding, binding, targetType, com.ses.privacy.foundation.HoldReleaseRules.strict(),
                authorities.sign(ISSUER, HoldReleaseAuthorityClaim.Role.ISSUER, binding, now.minusSeconds(60),
                        now.plus(Duration.ofMinutes(30))),
                authorities.sign(APPROVER, HoldReleaseAuthorityClaim.Role.APPROVER, binding, now.minusSeconds(60),
                        now.plus(Duration.ofMinutes(30))));
    }

    private static PrivacyHoldReleaseRequest.Claim claim(HoldReleaseAuthorityClaim claim) {
        return new PrivacyHoldReleaseRequest.Claim(claim.authorityId(), claim.keyId(), claim.issuedAt().toString(),
                claim.expiresAt().toString(), Base64.getEncoder().encodeToString(claim.signature()));
    }
}
