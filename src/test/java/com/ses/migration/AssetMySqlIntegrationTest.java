package com.ses.migration;

import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.AssetEvent;
import com.ses.entity.AssetInventoryItem;
import com.ses.entity.AssetInventoryRun;
import com.ses.entity.LicensePlan;
import com.ses.entity.LicenseAssignment;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetEventMapper;
import com.ses.mapper.AssetInventoryItemMapper;
import com.ses.mapper.AssetInventoryRunMapper;
import com.ses.mapper.AssetLostIncidentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.mapper.LicensePlanMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetInventoryService;
import com.ses.service.AssetService;
import com.ses.service.ExternalAccountService;
import com.ses.service.LicenseService;
import com.ses.test.MySQLContainer;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.MockExternalAccountProviderClientImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実MySQL 8コンテナ上での資産・アカウント・ライセンスDDL・排他制御・状態整合性テスト
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AssetMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_asset_mysql")
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

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentService assetAssignmentService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private AssetEventMapper assetEventMapper;

    @Autowired
    private ExternalAccountService externalAccountService;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private LicensePlanMapper licensePlanMapper;

    @Autowired
    private LicenseAssignmentMapper licenseAssignmentMapper;

    @Autowired
    private AssetInventoryService assetInventoryService;

    @Autowired
    private AssetInventoryRunMapper assetInventoryRunMapper;

    @Autowired
    private AssetInventoryItemMapper assetInventoryItemMapper;

    @Autowired
    private AssetLostIncidentMapper assetLostIncidentMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private ExternalAccountProviderClient providerClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("MySQL DDL: Asset creation and row lock verification")
    void testAssetCreationAndRowLockOnMySQL() {
        Asset asset = Asset.builder()
                .assetTag("MYSQL-AST-001")
                .assetName("MySQL ThinkPad Test")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        assertNotNull(asset.getId());
        Asset locked = assetMapper.selectByIdForUpdate(asset.getId());
        assertNotNull(locked);
        assertEquals("IN_STOCK", locked.getStatus());
    }

    @Test
    @DisplayName("MySQL DDL: Asset assignment and return lifecycle")
    void testAssetAssignmentLifecycleOnMySQL() {
        Asset asset = Asset.builder()
                .assetTag("MYSQL-AST-002")
                .assetName("MySQL Display Test")
                .category("MONITOR")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 7001L, LocalDate.now(), LocalDate.now().plusMonths(1), null, "MySQL Assign", 1L);
        assertNotNull(assignment.getId());
        assertEquals("ACTIVE", assignment.getStatus());

        Asset assignedAsset = assetService.getById(asset.getId());
        assertEquals("ASSIGNED", assignedAsset.getStatus());

        AssetAssignment returned = assetAssignmentService.returnAssignment(
                assignment.getId(), LocalDate.now(), null, "MySQL Returned", 1L);
        assertEquals("RETURNED", returned.getStatus());

        Asset returnedAsset = assetService.getById(asset.getId());
        assertEquals("IN_STOCK", returnedAsset.getStatus());
    }

    @Test
    @DisplayName("MySQL DDL: External account and license seat CAS")
    void testExternalAccountAndLicenseCasOnMySQL() {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("MYSQL_SYSTEM")
                .systemName("MySQL System")
                .systemType("IDP")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);

        ExternalAccountReference ref = externalAccountService.registerAccountReference(
                system.getId(), "mysql.user@ses-test.jp", "ENGINEER", 7002L, "MEMBER", 1L);
        assertNotNull(ref.getId());
        assertEquals("ACTIVE", ref.getStatus());

        ExternalAccountReference revoked = externalAccountService.confirmRevoke(ref.getId(), 1L);
        assertEquals("REVOKED", revoked.getStatus());
        assertEquals(1L, revoked.getRevokeConfirmedBy());
        assertEquals("MANUAL_API", revoked.getRevokeConfirmedSource());
        assertEquals("HUMAN", revoked.getActorType());
        assertEquals("MANUAL_API", revoked.getConfirmationSource());

        LicensePlan plan = LicensePlan.builder()
                .planCode("MYSQL-LIC-001")
                .planName("MySQL JetBrains Plan")
                .seatLimit(5)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        int updated = licensePlanMapper.incrementAllocatedCountWithCas(plan.getId(), plan.getVersion());
        assertEquals(1, updated);
        LicensePlan updatedPlan = licensePlanMapper.selectById(plan.getId());
        assertEquals(1, updatedPlan.getAllocatedCount());
    }

    @Test
    @DisplayName("MySQL NF-09: System poll attributes to SYSTEM (null confirmedBy) vs manual real user on real MySQL")
    void testSystemVsManualActorAttributionOnMySQL() {
        assertTrue(providerClient instanceof MockExternalAccountProviderClientImpl);
        MockExternalAccountProviderClientImpl mockClient = (MockExternalAccountProviderClientImpl) providerClient;

        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("MYSQL_ATTR_" + System.nanoTime())
                .systemName("MySQL Actor Attribution System")
                .systemType("SAAS_COLLAB")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);

        // 1. 手動確認
        ExternalAccountReference manualRef = externalAccountService.registerAccountReference(
                system.getId(), "mysql.manual@ses-test.jp", "ENGINEER", 7010L, "MEMBER", 9001L);
        ExternalAccountReference manualRevoked = externalAccountService.confirmRevoke(manualRef.getId(), 1L);
        assertEquals("REVOKED", manualRevoked.getStatus());
        assertEquals(1L, manualRevoked.getRevokeConfirmedBy());
        assertEquals("MANUAL_API", manualRevoked.getRevokeConfirmedSource());

        ExternalAccountReference dbManual = externalAccountReferenceMapper.selectById(manualRef.getId());
        assertEquals("REVOKED", dbManual.getStatus());
        assertEquals(1L, dbManual.getRevokeConfirmedBy());
        assertEquals("MANUAL_API", dbManual.getRevokeConfirmedSource());

        // 2. システム自動ポーリング (confirmedBy == null, source == SYSTEM, ユーザー1偽装禁止)
        ExternalAccountReference autoRef = externalAccountService.registerAccountReference(
                system.getId(), "mysql.poll@ses-test.jp", "ENGINEER", 7011L, "MEMBER", 9001L);
        mockClient.setMockStatus(autoRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT);
        externalAccountService.requestRevokeWithIdempotency(autoRef.getId(), "mysql-poll-key-" + autoRef.getId(), 9001L);
        mockClient.setMockStatus(autoRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);

        // 他テストが作成したpending行の件数には依存せず、このテスト自身の行がdueであることだけを確認する。
        jdbcTemplate.update("UPDATE t_external_account_reference SET next_retry_at = CURRENT_TIMESTAMP WHERE id = ?",
                autoRef.getId());
        externalAccountService.processPendingRevokePollJob();

        ExternalAccountReference dbAuto = externalAccountReferenceMapper.selectById(autoRef.getId());
        assertEquals("REVOKED", dbAuto.getStatus());
        assertNotNull(dbAuto.getRevokeConfirmedAt());
        assertNull(dbAuto.getRevokeConfirmedBy(), "自動ポーリングによる失効確認者はNULLでなければならない (ユーザー1の偽装禁止)");
        assertEquals("SCHEDULER_POLL", dbAuto.getRevokeConfirmedSource());
        assertEquals("SYSTEM", dbAuto.getActorType());
        assertEquals("SCHEDULER_POLL", dbAuto.getConfirmationSource());
    }

    @Test
    @DisplayName("MySQL concurrency: same revoke idempotency key claims once and calls provider once")
    void testConcurrentRevokeClaimCallsProviderOnceOnMySQL() throws Exception {
        assertTrue(providerClient instanceof MockExternalAccountProviderClientImpl);
        MockExternalAccountProviderClientImpl mockClient = (MockExternalAccountProviderClientImpl) providerClient;
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("MYSQL_CONCUR_IDEM_" + System.nanoTime())
                .systemName("MySQL concurrent Idempotency")
                .systemType("IDP")
                .build();
        externalAccountSystemMapper.insert(system);
        ExternalAccountReference ref = externalAccountService.registerAccountReference(
                system.getId(), "mysql-concurrent@ses-test.jp", "ENGINEER", 7101L, "MEMBER", 1L);
        mockClient.setMockStatus(ref.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.PENDING);
        mockClient.resetRequestCount();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> runConcurrentRevoke(ref.getId(), "MYSQL-IDEM-" + ref.getId(), ready, start));
            Future<?> second = pool.submit(() -> runConcurrentRevoke(ref.getId(), "MYSQL-IDEM-" + ref.getId(), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ExternalAccountReference current = externalAccountReferenceMapper.selectById(ref.getId());
        assertEquals("MYSQL-IDEM-" + ref.getId(), current.getIdempotencyKey());
        assertEquals(1, mockClient.getRequestCount(), "同一keyのclaim先着1件だけがproviderを呼ぶこと");
    }

    @Test
    @DisplayName("MySQL concurrency: same idempotency key across accounts returns one 409")
    void testConcurrentRevokeClaimSameKeyAcrossAccountsReturns409OnMySQL() throws Exception {
        assertTrue(providerClient instanceof MockExternalAccountProviderClientImpl);
        MockExternalAccountProviderClientImpl mockClient = (MockExternalAccountProviderClientImpl) providerClient;
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("MYSQL_CONCUR_CROSS_ACCOUNT_" + System.nanoTime())
                .systemName("MySQL cross-account Idempotency")
                .systemType("IDP")
                .build();
        externalAccountSystemMapper.insert(system);
        ExternalAccountReference firstRef = externalAccountService.registerAccountReference(
                system.getId(), "mysql-cross-a@ses-test.jp", "ENGINEER", 7104L, "MEMBER", 1L);
        ExternalAccountReference secondRef = externalAccountService.registerAccountReference(
                system.getId(), "mysql-cross-b@ses-test.jp", "ENGINEER", 7105L, "MEMBER", 1L);
        mockClient.setMockStatus(firstRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.PENDING);
        mockClient.setMockStatus(secondRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.PENDING);
        mockClient.resetRequestCount();

        String key = "MYSQL-CROSS-ACCOUNT-IDEM-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ExternalAccountReference> first = pool.submit(() -> {
            ready.countDown();
            start.await();
            return externalAccountService.requestRevokeWithIdempotency(firstRef.getId(), key, 1L);
        });
        Future<ExternalAccountReference> second = pool.submit(() -> {
            ready.countDown();
            start.await();
            return externalAccountService.requestRevokeWithIdempotency(secondRef.getId(), key, 1L);
        });
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            int conflicts = 0;
            for (Future<ExternalAccountReference> future : List.of(first, second)) {
                try {
                    assertNotNull(future.get(20, TimeUnit.SECONDS));
                    successes++;
                } catch (ExecutionException ex) {
                    assertInstanceOf(BusinessException.class, ex.getCause());
                    assertEquals(409, ((BusinessException) ex.getCause()).getCode());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, mockClient.getRequestCount(), "同一keyは異なるaccount間でもproviderを1回だけ呼ぶこと");
        assertEquals(1, externalAccountReferenceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExternalAccountReference>()
                        .eq(ExternalAccountReference::getIdempotencyKey, key)).size());
    }

    private void runConcurrentRevoke(Long refId, String idempotencyKey,
                                     CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            externalAccountService.requestRevokeWithIdempotency(refId, idempotencyKey, 1L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @DisplayName("MySQL concurrency: return and waiver have one terminal event")
    void testConcurrentReturnAndWaiveOnMySQL() throws Exception {
        Asset asset = Asset.builder()
                .assetTag("MYSQL-RETURN-WAIVE-" + System.nanoTime())
                .assetName("MySQL return/waive PC")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 7102L, LocalDate.now(), LocalDate.now().plusDays(30), null, "MySQL concurrency", 1L);
        ApprovalRequest approval = ApprovalRequest.builder()
                .requestNo("AR-RW-" + assignment.getId() + "-" + (System.nanoTime() % 1000000))
                .requestType("LIFECYCLE_EXCEPTION")
                .targetType("ASSET_ASSIGNMENT")
                .targetId(assignment.getId())
                .applicantId(1L)
                .payloadJson("{}")
                .routeSnapshotJson("[]")
                .status("APPROVED")
                .version(1)
                .build();
        approvalRequestMapper.insert(approval);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        try {
            pool.submit(() -> {
                awaitAndRun(start, success, failure,
                        () -> assetAssignmentService.returnAssignment(assignment.getId(), LocalDate.now(), null, "返却", 1L));
            });
            pool.submit(() -> {
                awaitAndRun(start, success, failure,
                        () -> assetAssignmentService.waiveAssignment(assignment.getId(), "承認済み例外", approval.getId(), 1L));
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
        assertEquals(1, failure.get());
        assertTrue(List.of("RETURNED", "WAIVED").contains(assetAssignmentMapper.selectById(assignment.getId()).getStatus()));
        Long terminalEvents = assetEventMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, asset.getId())
                .in(AssetEvent::getEventType, List.of("RETURNED", "WAIVED")));
        assertEquals(1L, terminalEvents);
    }

    private void awaitAndRun(CountDownLatch start, AtomicInteger success, AtomicInteger failure, Runnable operation) {
        try {
            start.await();
            operation.run();
            success.incrementAndGet();
        } catch (Exception ex) {
            failure.incrementAndGet();
        }
    }

    @Test
    @DisplayName("MySQL concurrency: releasing one license assignment decrements one seat")
    void testConcurrentLicenseReleaseDecrementsOnceOnMySQL() throws Exception {
        LicensePlan plan = LicensePlan.builder()
                .planCode("MYSQL-LIC-CONCUR-" + System.nanoTime())
                .planName("MySQL concurrent release")
                .seatLimit(2)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);
        LicenseAssignment assignment = licenseService.assignLicense(
                plan.getId(), "ENGINEER", 7103L, null, LocalDate.now(), 1L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> awaitAndRelease(start, assignment.getId()));
            Future<?> second = pool.submit(() -> awaitAndRelease(start, assignment.getId()));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("RELEASED", licenseAssignmentMapper.selectById(assignment.getId()).getStatus());
        assertEquals(0, licensePlanMapper.selectById(plan.getId()).getAllocatedCount(), "二重解放でも席数は1回だけ減算");
    }

    private void awaitAndRelease(CountDownLatch start, Long assignmentId) {
        try {
            start.await();
            licenseService.releaseLicense(assignmentId, LocalDate.now(), 1L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @DisplayName("MySQL concurrency: duplicate lost reports create one incident and one notification per recipient")
    void testConcurrentLostReportPublishesOnceOnMySQL() throws Exception {
        Asset asset = Asset.builder()
                .assetTag("MYSQL-LOST-DEDUPE-" + System.nanoTime())
                .assetName("MySQL lost report device")
                .category("PC")
                .build();
        assetService.createAsset(asset, 1L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> awaitAndRun(start, success, failure,
                        () -> assetService.reportLost(asset.getId(), "MySQL concurrent lost report", 1L, null)));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, success.get());
        assertEquals(0, failure.get());
        assertEquals(1, assetLostIncidentMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.AssetLostIncident>()
                        .eq(com.ses.entity.AssetLostIncident::getAssetId, asset.getId())));
        com.ses.entity.AssetLostIncident incident = assetLostIncidentMapper.selectLatestByAssetId(asset.getId());
        long notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type = 'ASSET_LOST_INCIDENT' AND dedupe_key LIKE ?",
                Long.class, "asset:lost:" + incident.getId() + "%");
        long expectedRecipients = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE status = 1 AND role IN ('管理者', 'HR')",
                Long.class);
        long reporterIsManagement = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = 1 AND status = 1 AND role IN ('管理者', 'HR')",
                Long.class);
        if (reporterIsManagement == 0) {
            expectedRecipients++;
        }
        assertEquals(expectedRecipients, notificationCount,
                "同一incidentの通知は各recipient一件だけであること");

        long beforeResend = notificationCount;
        assetService.reportLost(asset.getId(), "MySQL sequential resend", 1L, null);
        long afterResend = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE type = 'ASSET_LOST_INCIDENT' AND dedupe_key LIKE ?",
                Long.class, "asset:lost:" + incident.getId() + "%");
        assertEquals(beforeResend, afterResend, "再送で通知件数が増えないこと");
    }

    @Test
    @DisplayName("MySQL concurrency: inventory completion has one winner and freezes details")
    void testConcurrentInventoryCompletionOnMySQL() throws Exception {
        AssetInventoryRun run = assetInventoryService.startInventoryRun(
                "MYSQL-INV-CONCUR-" + System.nanoTime(), "MySQL concurrent inventory", LocalDate.now(), 1L);
        List<AssetInventoryItem> items = assetInventoryItemMapper.selectByRunId(run.getId());
        assertFalse(items.isEmpty());
        assetInventoryService.recordItemCheck(items.get(0).getId(), "IN_STOCK", "MYSQL", "MATCH", null, null, 1L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> awaitAndRun(start, success, failure,
                        () -> assetInventoryService.completeInventoryRun(run.getId(), 1L)));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
        assertEquals(1, failure.get());
        assertEquals("COMPLETED", assetInventoryRunMapper.selectById(run.getId()).getStatus());
        assertEquals("MATCH", assetInventoryItemMapper.selectById(items.get(0).getId()).getDiscrepancyType());
    }

    @Test
    @DisplayName("MySQL schema: V132/V133/V136 waiver, lost incident, attribution scope, FK, unique key and append-only triggers")
    void testV132AndV133SchemaAndAppendOnlyGuardsOnMySQL() {
        String latest = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertEquals("136", latest);
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_offboarding_waiver' AND column_name = 'lifecycle_case_id'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_offboarding_waiver' AND column_name = 'lifecycle_task_id'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_asset_offboarding_waiver' AND index_name = 'uk_asset_offboarding_waiver_request'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_asset_offboarding_waiver_case'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_asset_offboarding_waiver_task'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'trg_asset_event_no_update'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'trg_asset_event_no_delete'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 't_asset_lost_incident'"));
        for (String column : new String[]{
                "reported_at", "remote_wipe_status", "remote_wipe_requested_at", "remote_wipe_executed_at",
                "remote_wipe_confirmed_at", "police_report_number", "insurance_claim_status", "insurance_claimed_at"}) {
            assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_lost_incident' AND column_name = '" + column + "'"));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_asset_lost_incident' AND index_name = 'uq_asset_lost_incident_asset' AND non_unique = 0"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_asset_lost_incident_asset'"));
        // V136 external account revoke system actor attribution
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND column_name = 'revoke_confirmed_source'"));
        for (String column : new String[]{"revoke_requested_by", "actor_type", "confirmation_source"}) {
            assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND column_name = '" + column + "'"));
        }
        assertEquals(1, count("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND index_name = 'idx_ext_acc_revoke_confirmed'"));
        for (String constraint : new String[]{"ck_ext_revoke_actor_type", "ck_ext_revoke_confirmation_source", "ck_ext_revoke_attribution", "ck_ext_revoke_status_attribution"}) {
            assertEquals(1, count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_external_account_reference' AND constraint_name = '" + constraint + "'"));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_asset_event' AND constraint_name = 'ck_asset_event_actor_pair'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_audit_log' AND constraint_name = 'ck_audit_actor_pair'"));
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
