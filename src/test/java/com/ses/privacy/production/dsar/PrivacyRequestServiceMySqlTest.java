package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.privacy.PrivacyAction;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NF07-A2a: 実 MySQL（Flyway で V190 まで適用）で DSAR の状態遷移の直列化と、複合FKによる tenant 越えの拒否を確かめる。
 *
 * <ul>
 *   <li>並行裁定: 同じ版の要求を2つの transaction が出すと、後の要求の CAS は先の transaction の request 行の X lock を待ち、
 *       先の commit 後に0件となって 409 になる（lock の待機は {@link InnoDbRecordLockWaits#waitEdgesOwnedBy} で確かめる。
 *       {@code data_locks.LOCK_DATA} の値には依存しない）。範囲確定と候補の裁定の競合でも、SELECTED が2件のまま SCOPED にならない。</li>
 *   <li>複合FK: 他の tenant の request を指す link・action・再申立の行は DB が拒否する。</li>
 * </ul>
 * 各 test は専用の tenant を使い、commit した行は class 専用の container DB に残る（他の test の行は読まない）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class PrivacyRequestServiceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_nf07_dsar_request")
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

    /** JST 2026-11-16 10:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final long ADMIN_ID = 1_516_201L;

    @Autowired
    private PrivacyRequestMapper requestMapper;
    @Autowired
    private PrivacyRequestSubjectLinkMapper linkMapper;
    @Autowired
    private PrivacyActionMapper actionMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenant;
    private PrivacyRequestService service;

    @BeforeEach
    void setUp() {
        tenant = "dsar-" + UUID.randomUUID().toString().replace("-", "");
        service = service(tenant);
    }

    @Test
    void 同じ版の並行裁定はrequest行のCASで直列化され後の裁定は409になる() throws Exception {
        Fixture f = verifiedWithTwoCandidates();

        Outcome outcome = race(
                () -> service.resolveCandidate(f.requestId, f.firstLinkId,
                        new PrivacyDsarCommands.CandidateResolution(f.version, "SELECTED", null)),
                () -> service.resolveCandidate(f.requestId, f.secondLinkId,
                        new PrivacyDsarCommands.CandidateResolution(f.version, "SELECTED", null)));

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        assertThat(linkState(f.firstLinkId)).isEqualTo("SELECTED");
        assertThat(linkState(f.secondLinkId)).as("後の裁定は書かれない").isEqualTo("CANDIDATE");
        PrivacyRequest row = requestMapper.selectById(f.requestId);
        assertThat(row.getRowVersion()).isEqualTo(f.version + 1);
        assertThat(row.getStatus()).isEqualTo("IDENTITY_VERIFIED");
    }

    @Test
    void 範囲確定と候補の裁定が競合しても2件のSELECTEDのまま範囲は確定しない() throws Exception {
        Fixture f = verifiedWithTwoCandidates();
        service.resolveCandidate(f.requestId, f.firstLinkId,
                new PrivacyDsarCommands.CandidateResolution(f.version, "SELECTED", null));
        long version = f.version + 1;

        // 先の transaction が2件目を SELECTED にして commit 前に止まり、その間に範囲確定（その時点の snapshot では1件）を出す
        Outcome outcome = race(
                () -> service.resolveCandidate(f.requestId, f.secondLinkId,
                        new PrivacyDsarCommands.CandidateResolution(version, "SELECTED", null)),
                () -> service.scope(f.requestId, new PrivacyDsarCommands.ScopeConfirmation(version)));

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        PrivacyRequest row = requestMapper.selectById(f.requestId);
        assertThat(row.getStatus()).isEqualTo("IDENTITY_VERIFIED");
        assertThat(row.getScopeSnapshotHash()).isNull();
        assertThat(actionMapper.selectCount(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, tenant).eq(PrivacyAction::getRequestId, f.requestId))).isZero();
        assertThatThrownBy(() -> service.scope(f.requestId, new PrivacyDsarCommands.ScopeConfirmation(version + 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarSubjectNotUnique"));
    }

    @Test
    void 複合FKは他tenantのrequestを指すlinkとactionと再申立を拒否する() {
        long requestId = service.intake(intake("T-FK-A")).requestId();
        String otherTenant = tenant + "-b";

        PrivacyRequestSubjectLink link = new PrivacyRequestSubjectLink();
        link.setTenantId(otherTenant);
        link.setRequestId(requestId);
        link.setSubjectRef("ENGINEER:101");
        link.setLinkState("CANDIDATE");
        assertThatThrownBy(() -> linkMapper.insert(link)).isInstanceOf(DataIntegrityViolationException.class);

        PrivacyAction action = new PrivacyAction();
        action.setTenantId(otherTenant);
        action.setRequestId(requestId);
        action.setActionType("MANUAL_ACTION");
        action.setStatus("SUCCEEDED");
        action.setIdempotencyKey("DSAR-FK:" + requestId);
        action.setRowVersion(0L);
        action.setCreatedAt(LocalDateTime.of(2026, 11, 16, 10, 0));
        assertThatThrownBy(() -> actionMapper.insert(action)).isInstanceOf(DataIntegrityViolationException.class);

        PrivacyRequest reopen = new PrivacyRequest();
        reopen.setTenantId(otherTenant);
        reopen.setRequestNo("DSAR-FK-REOPEN");
        reopen.setRequestType("ACCESS");
        reopen.setStatus("REQUESTED");
        reopen.setChannel("EMAIL");
        reopen.setReceivedAt(LocalDateTime.of(2026, 11, 16, 10, 0));
        reopen.setIntakeTicketRef("T-FK-B");
        reopen.setIdentityState("UNVERIFIED");
        reopen.setReopenOfRequestId(requestId);
        reopen.setRowVersion(0L);
        reopen.setCreatedBy(ADMIN_ID);
        assertThatThrownBy(() -> requestMapper.insert(reopen)).isInstanceOf(DataIntegrityViolationException.class);

        // service 経由でも他 tenant の case は見えない（照会者の tenant で絞る）
        PrivacyRequestService other = service(otherTenant);
        assertThatThrownBy(() -> other.detail(requestId))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        assertThat(linkMapper.selectCount(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getRequestId, requestId))).isZero();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 先の操作を transaction の中で実行して commit 前に止め、後の操作が request 行の lock を待つことを確かめてから先を commit する。
     * 戻り値は各操作の例外（成功なら null）。
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
            assertTrue(firstApplied.await(20, TimeUnit.SECONDS), "先の操作が commit 前の状態に届く");
            Future<Throwable> second = pool.submit(() -> capture(secondOperation::get));
            awaitWaitOn(blocker.get());
            releaseFirst.countDown();
            return new Outcome(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 後の操作が先の transaction の request 行の lock を待つまで待つ。CAS の UPDATE は {@code (tenant_id, id)} の一意索引と
     * PRIMARY のどちらで行を探すかを optimizer が選ぶため、索引は限定しない（表と blocker の transaction で特定する）。
     */
    private void awaitWaitOn(long blockerConnectionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!InnoDbRecordLockWaits.waitEdgesOwnedBy(jdbc, blockerConnectionId, "t_privacy_request", null)
                    .isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("後の操作が request 行の lock を待っていない: "
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

    private static void assertConflict(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarConflict");
        });
    }

    private Fixture verifiedWithTwoCandidates() {
        long id = service.intake(intake("T-RACE")).requestId();
        service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-RACE"));
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:102"));
        List<PrivacyRequestSubjectLink> links = linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenant)
                .eq(PrivacyRequestSubjectLink::getRequestId, id)
                .orderByAsc(PrivacyRequestSubjectLink::getId));
        return new Fixture(id, links.get(0).getId(), links.get(1).getId(), 3L);
    }

    private String linkState(long linkId) {
        return linkMapper.selectById(linkId).getLinkState();
    }

    private PrivacyRequestService service(String actorTenant) {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(actorTenant);
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD06().setDecisionId("D06-NF07-2026-0006");
        approval.getD06().setSignedOn("2026-11-05");
        approval.getD06().setExpiresOn("2027-11-04");
        PrivacyFeatureProperties features = new PrivacyFeatureProperties();
        features.getDsar().setDueDays(30);
        // 本番と同じ REQUIRED（呼出し側の transaction があれば参加し、無ければ自分で commit する）
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return new PrivacyRequestService(mock(PrivacyAccessGuard.class), oidc,
                new PrivacyDsarDecisions(approval, features, CLOCK), requestMapper, linkMapper, actionMapper,
                new ProductionPrivacyProviderRegistry(List.of(new FixtureProvider())),
                new PrivacyDsarAudit(new PrivacyRequestServiceTest.CapturingAuditLogService()), transactions, CLOCK,
                () -> new PrivacyRequestService.Actor(actorTenant, ADMIN_ID, "privacy-admin-mysql"));
    }

    private static PrivacyDsarCommands.Intake intake(String ticketRef) {
        return new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", null, ticketRef, null);
    }

    private record Fixture(long requestId, long firstLinkId, long secondLinkId, long version) {
    }

    private record Outcome(Throwable first, Throwable second) {
    }

    /** どの tenant でも ENGINEER:101・102 に1行ずつ返す fixture provider。 */
    private static final class FixtureProvider implements ProductionPrivacyProvider {

        @Override
        public String providerId() {
            return "dsar-fixture-mysql";
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            return List.of(ProviderDataElement.exact("FIXTURE_MYSQL_PROFILE", SubjectAttribution.ATTRIBUTABLE,
                    List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"))));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(new ProviderTable("t_engineer", List.of("id", "tenant_id"), List.of()));
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            if (subject.type() != PrivacySubjectType.ENGINEER || (subject.id() != 101L && subject.id() != 102L)) {
                return List.of();
            }
            return List.of(new PrivacyRecord(providerId(), "t_engineer", subject.id(), List.of("FIXTURE_MYSQL_PROFILE"),
                    false, Map.of("id", subject.id(), "tenant_id", tenantId), Set.of()));
        }
    }
}
