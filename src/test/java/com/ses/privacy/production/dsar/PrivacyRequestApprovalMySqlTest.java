package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.dto.file.StoredFile;
import com.ses.entity.privacy.PrivacyExportPackage;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyExportPackageMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.redaction.ThirdPartyRedactor;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.service.FileStorageService;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlConstraintRejections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NF07-A2b: 実 MySQL（Flyway で V190 以降を適用）で DSAR の承認・交付記録の直列化と、DB 層の作成者≠承認者・承認の束縛を確かめる
 * （production-bridge-phased-plan.md §3.5.2 の対象限定テスト）。
 *
 * <ul>
 *   <li>二重承認・二重交付記録: 同じ版の要求を2つの transaction が出すと、後の要求の CAS は先の transaction の request 行の X lock を
 *       待ち、先の commit 後に0件となって 409 になる（lock の待機は {@link InnoDbRecordLockWaits#waitEdgesOwnedBy} で確かめる。
 *       {@code data_locks.LOCK_DATA} の値には依存しない）。package の PURGED と実体の削除は1回だけ。</li>
 *   <li>DB の CHECK: {@code prepared_by} が NULL のままの承認、作成者と同じ承認者、承認4項目の一部だけの記録、package の作成者による
 *       承認を拒否する。複合FK: 別の request の package を承認対象にできない。</li>
 *   <li>承認後に作った別の package へ承認を付け替えても、内容 hash が承認時と違えば専用ダウンロードを拒否する。</li>
 * </ul>
 * 開示 package の実体は in-memory の {@link FileStorageService} に置く（file の quarantine → published は H2 の
 * {@code PrivacyExportPackageServiceTest} が実装で確かめる）。各 test は専用の tenant を使い、commit した行は class 専用の
 * container DB に残る（他の test の行は読まない）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class PrivacyRequestApprovalMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf07_dsar_approval")
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
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final long ADMIN_A = 1_516_401L;
    private static final long ADMIN_B = 1_516_402L;
    private static final long ADMIN_C = 1_516_403L;

    @Autowired
    private PrivacyRequestMapper requestMapper;
    @Autowired
    private PrivacyRequestSubjectLinkMapper linkMapper;
    @Autowired
    private PrivacyActionMapper actionMapper;
    @Autowired
    private PrivacyExportPackageMapper packageMapper;
    @Autowired
    private PiiDataElementMapper dataElementMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenant;
    private InMemoryFileStorage storage;
    private ProductionPrivacyProviderRegistry registry;

    @BeforeEach
    void setUp() {
        tenant = "dsar-approval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        storage = new InMemoryFileStorage();
        registry = new ProductionPrivacyProviderRegistry(List.of(new FixtureProvider()));
    }

    @Test
    void 同じ草案の二重承認はrequest行のCASで直列化され後の承認は409になる() throws Exception {
        long id = previewed("T-DOUBLE-APPROVE");
        PrivacyExportPackage pkg = onlyPackage(id);
        PrivacyExportPackageService approverB = service(ADMIN_B);
        PrivacyExportPackageService approverC = service(ADMIN_C);

        Outcome outcome = race(() -> approverB.approve(id, approval(pkg)), () -> approverC.approve(id, approval(pkg)));

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("APPROVED");
        assertThat(row.getApprovedBy()).isEqualTo(ADMIN_B);
        assertThat(row.getRowVersion()).isEqualTo(6L);
        assertThat(packageMapper.selectById(pkg.getId()).getApprovedBy()).isEqualTo(ADMIN_B);
    }

    @Test
    void 二重の交付記録はrequest行のCASで直列化され後の記録は409でpackageは1回だけPURGEDになる() throws Exception {
        long id = approved("T-DOUBLE-DELIVERY");
        PrivacyExportPackage pkg = onlyPackage(id);
        PrivacyExportPackageService recorderA = service(ADMIN_A);
        PrivacyExportPackageService recorderB = service(ADMIN_B);

        Outcome outcome = race(() -> recorderA.recordDelivery(id, delivery(pkg, "SEND-A")),
                () -> recorderB.recordDelivery(id, delivery(pkg, "SEND-B")));

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("CONFIRMED");
        assertThat(row.getDeliveredBy()).isEqualTo(ADMIN_A);
        assertThat(row.getDeliveryEvidenceRef()).isEqualTo("SEND-A");
        assertThat(packageMapper.selectById(pkg.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(storage.deletes.get()).as("実体の削除は1回だけ").isEqualTo(1);
        assertThat(storage.files).doesNotContainKey(pkg.getStoredName());
    }

    @Test
    void 作成者NULLの承認と作成者と同じ承認者と束縛の一部だけの記録とpackageの作成者による承認はDBが拒否する() {
        long id = previewed("T-DB-CHECK");
        PrivacyExportPackage pkg = onlyPackage(id);
        String sha = pkg.getContentSha256();

        // MySQL の CHECK 違反（3819）は SQLState HY000 で UncategorizedSQLException に翻訳されるため、例外の型ではなく
        // error code と制約名で DB の拒否を判定する（統合候補 174632c8 の Runner で検出）
        jdbc.update("UPDATE t_privacy_request SET prepared_by = NULL WHERE tenant_id = ? AND id = ?", tenant, id);
        MySqlConstraintRejections.assertRejectedBy(() -> bindApproval(id, ADMIN_B, pkg.getId(), sha),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_request_sod");
        jdbc.update("UPDATE t_privacy_request SET prepared_by = ? WHERE tenant_id = ? AND id = ?", ADMIN_A, tenant, id);
        MySqlConstraintRejections.assertRejectedBy(() -> bindApproval(id, ADMIN_A, pkg.getId(), sha),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_request_sod");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_privacy_request SET approved_by = ?, "
                        + "approved_at = ? WHERE tenant_id = ? AND id = ?", ADMIN_B, NOW, tenant, id),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_request_approval_binding");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_privacy_export_package SET status = "
                        + "'APPROVED', approved_by = ?, approved_at = ? WHERE tenant_id = ? AND id = ?",
                        ADMIN_A, NOW, tenant, pkg.getId()),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_export_package_sod");

        long otherRequest = previewed("T-DB-CHECK-OTHER");
        PrivacyExportPackage otherPackage = onlyPackage(otherRequest);
        MySqlConstraintRejections.assertRejectedBy(
                () -> bindApproval(id, ADMIN_B, otherPackage.getId(), otherPackage.getContentSha256()),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_privacy_request_approved_package");

        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getApprovedBy()).isNull();
        assertThat(row.getStatus()).isEqualTo("PREVIEWED");
        // service 経由の承認は作成者以外なら通り、DB の CHECK と同じ条件を満たす
        service(ADMIN_B).approve(id, approval(pkg));
        assertThat(requestMapper.selectById(id).getApprovedPackageId()).isEqualTo(pkg.getId());
    }

    @Test
    void 承認後に作った別のpackageへ承認を付け替えても内容hashが違えば専用ダウンロードを拒否する() {
        long id = approved("T-REBIND");
        PrivacyExportPackage approvedPackage = onlyPackage(id);
        PrivacyExportPackage other = new PrivacyExportPackage();
        other.setTenantId(tenant);
        other.setRequestId(id);
        other.setStoredName(storage.put("{\"forged\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        other.setContentSha256(PrivacyExportPackageContent.sha256("{\"forged\":true}"));
        other.setSizeBytes(15L);
        other.setStatus("DRAFT");
        other.setPurgeAfter(NOW.plusDays(30));
        other.setCreatedBy(ADMIN_C);
        other.setCreatedAt(NOW);
        packageMapper.insert(other);
        // 承認の package だけを別の package へ付け替える（同じ request の package なので複合FK・CHECK は通る）
        jdbc.update("UPDATE t_privacy_request SET approved_package_id = ? WHERE tenant_id = ? AND id = ?",
                other.getId(), tenant, id);

        PrivacyExportPackageService downloader = service(ADMIN_A);
        assertNotApproved(() -> downloader.download(id, other.getId()));
        assertNotApproved(() -> downloader.download(id, approvedPackage.getId()));
        assertNotApproved(() -> downloader.recordDelivery(id, new PrivacyDsarCommands.Delivery(6L, other.getId(),
                approvedPackage.getContentSha256(), "EMAIL", "SEND-1")));
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- helpers

    private long previewed(String ticketRef) {
        PrivacyRequestService requests = requestService();
        long id = requests.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", null, ticketRef, null)).requestId();
        requests.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-" + ticketRef));
        requests.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        long linkId = linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenant)
                .eq(PrivacyRequestSubjectLink::getRequestId, id)).get(0).getId();
        requests.resolveCandidate(id, linkId, new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        requests.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));
        service(ADMIN_A).preview(id, new PrivacyDsarCommands.Preview(4L));
        return id;
    }

    private long approved(String ticketRef) {
        long id = previewed(ticketRef);
        service(ADMIN_B).approve(id, approval(onlyPackage(id)));
        return id;
    }

    private void bindApproval(long requestId, long approver, long packageId, String sha) {
        jdbc.update("UPDATE t_privacy_request SET approved_by = ?, approved_at = ?, approved_package_id = ?, "
                + "approved_content_sha256 = ? WHERE tenant_id = ? AND id = ?", approver, NOW, packageId, sha, tenant,
                requestId);
    }

    private PrivacyExportPackage onlyPackage(long requestId) {
        List<PrivacyExportPackage> rows = packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenant)
                .eq(PrivacyExportPackage::getRequestId, requestId)
                .orderByAsc(PrivacyExportPackage::getId));
        assertThat(rows).isNotEmpty();
        return rows.get(0);
    }

    private static PrivacyDsarCommands.Approval approval(PrivacyExportPackage pkg) {
        return new PrivacyDsarCommands.Approval(5L, pkg.getId(), pkg.getContentSha256());
    }

    private static PrivacyDsarCommands.Delivery delivery(PrivacyExportPackage pkg, String evidence) {
        return new PrivacyDsarCommands.Delivery(6L, pkg.getId(), pkg.getContentSha256(), "EMAIL", evidence);
    }

    private PrivacyApprovalProperties decisions() {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD06().setDecisionId("D06-NF07-2026-0006");
        approval.getD06().setSignedOn("2026-11-05");
        approval.getD06().setExpiresOn("2027-11-04");
        return approval;
    }

    private PrivacyFeatureProperties features() {
        PrivacyFeatureProperties features = new PrivacyFeatureProperties();
        features.getDsar().setDueDays(30);
        features.getDsar().setPackageMaxAge(Duration.ofDays(30));
        return features;
    }

    private OidcSecurityProperties oidc() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenant);
        return oidc;
    }

    private PrivacyRequestService requestService() {
        // 本番と同じ REQUIRED（呼出し側の transaction があれば参加し、無ければ自分で commit する）
        return new PrivacyRequestService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(decisions(), features(), CLOCK), requestMapper, linkMapper, actionMapper,
                registry, new PrivacyDsarAudit(new PrivacyRequestServiceTest.CapturingAuditLogService()),
                new TransactionTemplate(transactionManager), CLOCK,
                () -> new PrivacyRequestService.Actor(tenant, ADMIN_A, "privacy-admin-a"));
    }

    private PrivacyExportPackageService service(long actorId) {
        PrivacyApprovalProperties approval = decisions();
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivacyApprovalProperties> approvalProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThirdPartyRedactor.Rules> rulesProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(approvalProvider.getIfAvailable()).thenReturn(approval);
        return new PrivacyExportPackageService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(approval, features(), CLOCK), requestMapper, linkMapper, actionMapper,
                packageMapper, new PrivacySearchService(registry, dataElementMapper), registry,
                new ThirdPartyRedactor(approvalProvider, rulesProvider), storage, () -> null,
                new PrivacyDsarAudit(new PrivacyRequestServiceTest.CapturingAuditLogService()),
                new TransactionTemplate(transactionManager), CLOCK,
                () -> new PrivacyRequestService.Actor(tenant, actorId, "privacy-admin-" + actorId));
    }

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

    private static void assertNotApproved(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageNotApproved");
        });
    }

    private record Outcome(Throwable first, Throwable second) {
    }

    /** 開示 package の実体を memory に置く {@link FileStorageService}（削除の回数を数える）。 */
    private static final class InMemoryFileStorage implements FileStorageService {

        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final AtomicInteger deletes = new AtomicInteger();

        String put(byte[] data) {
            String name = UUID.randomUUID().toString().replace("-", "") + ".json";
            files.put(name, data.clone());
            return name;
        }

        @Override
        public StoredFile store(MultipartFile file, FileKind kind) {
            throw new UnsupportedOperationException("upload は扱わない");
        }

        @Override
        public StoredFile store(byte[] data, String originalName, FileKind kind) {
            return new StoredFile(put(data), originalName, data.length);
        }

        @Override
        public Resource load(String storedName) {
            byte[] data = files.get(storedName);
            if (data == null) {
                throw BusinessException.of("error.file.notFound");
            }
            return new ByteArrayResource(data.clone());
        }

        @Override
        public void delete(String storedName) {
            deletes.incrementAndGet();
            files.remove(storedName);
        }

        @Override
        public boolean rescan(String storedName) {
            return false;
        }
    }

    /** どの tenant でも ENGINEER:101 に、第三者混在の1行と本人だけの1行を返す fixture provider。 */
    private final class FixtureProvider implements ProductionPrivacyProvider {

        @Override
        public String providerId() {
            return "dsar-approval-mysql";
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            List<SubjectLink> links = List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"));
            return List.of(ProviderDataElement.exact("APPROVAL_MYSQL_PROFILE", SubjectAttribution.ATTRIBUTABLE, links),
                    ProviderDataElement.exact("APPROVAL_MYSQL_SKILL", SubjectAttribution.ATTRIBUTABLE,
                            DsarDeliveryClass.SELF, links));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(new ProviderTable("t_engineer", List.of("id", "tenant_id", "name"), List.of()));
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            if (subject.type() != PrivacySubjectType.ENGINEER || subject.id() != 101L) {
                return List.of();
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", 101L);
            fields.put("tenant_id", tenantId);
            fields.put("name", "承認 試験");
            return List.of(new PrivacyRecord(providerId(), "t_engineer", 101L,
                            List.of("APPROVAL_MYSQL_PROFILE", "APPROVAL_MYSQL_SKILL"), false, fields, Set.of()),
                    new PrivacyRecord(providerId(), "t_engineer", 1010L, List.of("APPROVAL_MYSQL_SKILL"), false, fields,
                            Set.of()));
        }
    }
}
