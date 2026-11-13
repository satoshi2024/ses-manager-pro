package com.ses.report;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportScheduledDeliveryContext;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.InvoiceService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.notification.WebhookNotifier;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportScheduleMetrics;
import com.ses.service.report.ReportScheduleService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.ManagementReportScheduler;
import com.ses.service.scheduler.ReportScheduleClaimService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import com.ses.test.MySQLContainer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransactionRollbackException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF-10 scheduler運用方針（B-NF10-2、scheduler-claim-fence-plan.md §14）の実MySQL 8受入試験。
 *
 * <ul>
 *   <li>A-NF10-5: lease期限切れの引き継ぎによる重複実行と、同一run_keyへの並行投入で、実
 *       {@link ReportSnapshotService}・{@link ReportDeliveryService} のrun_key・delivery dedupeにより配布
 *       （delivery行・アプリ内通知・通知outbox）が1回に収束する。引き継ぎの前に月次締めが確定しても、
 *       引き継いだ側は最初に作られた速報runを使い、確定runを作らない（cutoffの論理run固定）。</li>
 *   <li>retry上限: 連続12回目の失敗で停止し、停止理由を保存して管理者の有効化で同一論理runから再開する
 *       （MySQLのSET句左から評価でも停止判定が加算前の値に基づくことを含む）。</li>
 *   <li>起動guard: token無しlease（legacy lease）がある間は全claimを止め、運用者が設定したdrain確認時刻より前に
 *       取得されたleaseだけを期限後に引き継ぐ。確認時刻または起動時刻以降に取得されたleaseは止める。</li>
 * </ul>
 *
 * <p><b>収束の前提</b>（scheduler-claim-fence-plan.md §14.4）: 同じ論理run（schedule・対象月）の実行の間で、
 * recipient preview（配布先と各配布先のscope）とtemplate版が変わらないこと。cutoffは生成serviceが最初のrunに固定する
 * ため前提にしない。previewが変わった場合は二重配布にはならないが、その論理runは
 * {@code recipientPreviewStale} で進まなくなる（復旧手順は同plan §14.2）。本testはpreviewを固定値にしているため、
 * その場合を検証しない。
 *
 * <p>mockにするのは次の外部入力だけ: recipient preview（配布先の評価。収束の前提として固定）、AR aging正本値
 * （section値）、PDF描画とstorage（{@link ReportDeliveryDocumentRegistrar}）、外部webhook。月次締め状態は実
 * {@link MonthlyClosingService} が {@code t_monthly_closing} から読む。t_report_run・section snapshot・
 * t_report_delivery・t_notification・t_notification_outboxは実serviceが実MySQLへ書く。schedulerはclockを制御するため
 * 実beanを {@code new} で組み立てる（claim serviceはREQUIRES_NEWの実proxy）。Docker必須・零skip
 * （{@code disabledWithoutDocker} は使わない）。fixtureはsyntheticなtemplate/scheduleだけを使う。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class ReportScheduleDispatchPolicyMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf10_dispatch_policy")
            .withUsername("root")
            .withPassword("ses");

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final String TENANT = "default";
    private static final String CRON = "0 0 0 1 * *";
    private static final LocalDateTime SEPT = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime OCT = LocalDateTime.of(2026, 10, 1, 0, 0);
    /** 論理run 2026-09-01の集計対象月。 */
    private static final YearMonth TARGET = YearMonth.of(2026, 8);
    private static final String PREVIEW_HASH = "preview-nf10-dispatch-policy";
    private static final String FIXTURE_PREFIX = "nf10-dispatch-policy-";
    private static final String SCOPE_JSON = "{\"ownerType\":\"COMPANY\",\"ownerId\":null,\"companyWide\":true,"
            + "\"organizationIds\":[],\"directUserIds\":[],\"engineerIds\":[],\"contractIds\":[],"
            + "\"invoiceIds\":[],\"policyVersion\":\"scope-policy-approved-1\"}";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @MockBean
    private ReportRecipientPreviewService recipientPreviewService;

    @MockBean
    private InvoiceService invoiceService;

    @MockBean
    private ReportDeliveryDocumentRegistrar documentRegistrar;

    @MockBean
    private WebhookNotifier webhookNotifier;

    @Autowired
    private ReportScheduleMapper scheduleMapper;

    @Autowired
    private ReportScheduleClaimService claimService;

    @Autowired
    private ReportScheduleService scheduleService;

    @Autowired
    private ReportSnapshotService snapshotService;

    @Autowired
    private ReportDeliveryService deliveryService;

    @Autowired
    private AccountingTimezoneResolver timezoneResolver;

    @Autowired
    private TenantAwareBatchRunner tenantAwareBatchRunner;

    @Autowired
    private MonthlyClosingService monthlyClosingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> templateIds = new ArrayList<>();
    private Long adminId;
    private Long templateVersionId;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        // 対象月は未締めから始める（月次締めは実serviceがt_monthly_closingから判定する）。
        reopenTargetMonth();
        adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE tenant_id = ? AND username = 'admin' "
                + "AND deleted_flag = 0", Long.class, TENANT);
        templateVersionId = insertPublishedTemplateVersion();
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                adminId, "管理者", "ALLOW", "SCOPE_MATCH", "scope-nf10-dispatch-policy");
        ReportRecipientPreviewResult preview = new ReportRecipientPreviewResult(
                PREVIEW_HASH, "APPROVED_SCOPE_CHECKED", SEPT, List.of(recipient));
        when(recipientPreviewService.previewForScope(anyLong(), any(), any())).thenReturn(preview);
        when(recipientPreviewService.previewForRun(any())).thenReturn(preview);
        when(invoiceService.aging(any())).thenReturn(new AgingReportDto());
        when(documentRegistrar.registerArtifact(anyLong(), eq("PDF"))).thenAnswer(invocation ->
                new ReportDocumentArtifact(invocation.getArgument(0), "PDF", "nf10-dispatch-policy-artifact",
                        null, null));
    }

    @AfterEach
    void tearDown() {
        try {
            for (Long scheduleId : scheduleIds) {
                List<Long> runIds = jdbcTemplate.queryForList(
                        "SELECT id FROM t_report_run WHERE tenant_id = ? AND schedule_id = ?",
                        Long.class, TENANT, scheduleId);
                for (Long runId : runIds) {
                    jdbcTemplate.update("DELETE FROM t_notification_outbox WHERE dedupe_key LIKE CONCAT('REPORT:', ?, ':%')",
                            runId);
                    jdbcTemplate.update("DELETE FROM t_notification WHERE dedupe_key LIKE CONCAT('REPORT:', ?, ':%')",
                            runId);
                    jdbcTemplate.update("DELETE FROM t_report_delivery WHERE run_id = ?", runId);
                    jdbcTemplate.update("DELETE FROM t_report_section_attempt WHERE run_id = ?", runId);
                    jdbcTemplate.update("DELETE FROM t_report_section_snapshot WHERE run_id = ?", runId);
                    jdbcTemplate.update("DELETE FROM t_report_run WHERE id = ?", runId);
                }
                // legacy lease行を残すと同classの他testで起動guardが全claimを止めるため、scheduleは必ず消す。
                jdbcTemplate.update("DELETE FROM m_report_schedule WHERE id = ?", scheduleId);
            }
            for (Long templateId : templateIds) {
                jdbcTemplate.update("DELETE FROM m_report_template_version WHERE template_id = ?", templateId);
                jdbcTemplate.update("DELETE FROM m_report_template WHERE id = ?", templateId);
            }
            reopenTargetMonth();
        } finally {
            scheduleIds.clear();
            templateIds.clear();
            SecurityContextHolder.clearContext();
            AccountingTenantContextHolder.clear();
        }
    }

    // =========================================================================
    // A-NF10-5 run_key・delivery dedupeによる重複配布の収束（実service）
    // =========================================================================

    @Test
    void lease期限切れの引き継ぎで同一論理runを重複実行しても実serviceのdedupeで配布は1回に収束する() throws Exception {
        Takeover takeover = takeoverAfterDelivery(false);

        assertThat(takeover.commandB().cutoffKind()).as("締め状態は変わらず、Bも速報を要求する").isEqualTo("速報");
        assertConvergedToSingleDelivery(takeover);
    }

    @Test
    void 速報配布後に月次締めが確定してから引き継いでも確定runを作らず配布は1回に収束する() throws Exception {
        Takeover takeover = takeoverAfterDelivery(true);

        // Bの時点では締め確定済みのため、schedulerは「確定」を要求する。生成serviceは同一論理runで最初に作られた
        // 速報runへ固定し、確定runを作らない（確定版は次の周期で扱う）。
        assertThat(monthlyClosingService.isClosed(TARGET.toString())).isTrue();
        assertThat(takeover.commandB().cutoffKind()).isEqualTo("確定");
        assertThat(takeover.resultB().getRun().getCutoffKind()).isEqualTo("GENERATED_AT");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_report_run WHERE tenant_id = ? "
                        + "AND schedule_id = ? AND cutoff_kind = 'MONTHLY_CLOSING'", Integer.class, TENANT,
                takeover.scheduleId())).as("確定runは作られない").isZero();
        assertConvergedToSingleDelivery(takeover);
    }

    /** Aの実生成・実配布の後、completion前にleaseを超えて停止したAをBが引き継いで同じ論理runを再実行する。 */
    private Takeover takeoverAfterDelivery(boolean closeMonthBeforeTakeover) throws Exception {
        long id = insertSchedule(SEPT);
        AtomicReference<ReportGenerationResult> resultA = new AtomicReference<>();
        AtomicReference<ReportGenerationResult> resultB = new AtomicReference<>();
        AtomicReference<ReportGenerationCommand> commandB = new AtomicReference<>();
        ReportDeliveryService stallingDelivery = mock(ReportDeliveryService.class);
        ReportDeliveryService deliveryB = delegating(deliveryService);
        Harness workerA = new Harness(SEPT.plusSeconds(30), recording(resultA, new AtomicReference<>()),
                stallingDelivery);
        Harness workerB = new Harness(SEPT.plusMinutes(31), recording(resultB, commandB), deliveryB);
        CountDownLatch aDelivered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        when(stallingDelivery.deliverScheduled(anyLong(), any())).thenAnswer(invocation -> {
            ReportDeliveryResult delivered;
            try {
                delivered = deliveryService.deliverScheduled(invocation.getArgument(0), invocation.getArgument(1));
            } finally {
                aDelivered.countDown();
            }
            // 生成と配布の副作用を終えた後、completion前にlease（30分）を超えて停止する（引き継ぎの原因）。
            awaitLatch(releaseA);
            workerA.clock.set(SEPT.plusMinutes(45));
            return delivered;
        });
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> runA = pool.submit(workerA.scheduler::dispatchDue);
            assertThat(aDelivered.await(60, TimeUnit.SECONDS)).as("Aの実生成と実配布: logs=%s", messages(logs)).isTrue();
            assertThat(resultA.get()).as("Aの生成結果").isNotNull();
            assertThat(resultA.get().getRun().getCutoffKind()).as("Aは未締めで速報を作る").isEqualTo("GENERATED_AT");
            long runIdByA = resultA.get().getRun().getId();
            assertThat(countDeliveries(runIdByA)).as("Aの配布でdelivery行が1件").isEqualTo(1);
            if (closeMonthBeforeTakeover) {
                closeTargetMonth();
            }

            // BはAのlease期限切れ後に同じ論理runを引き継ぎ、実serviceで生成と配布をもう一度要求する。
            workerB.scheduler.dispatchDue();
            assertCompletedSuccessfully(id, OCT, SEPT);
            Map<String, Object> completedByB = scheduleRow(id);

            releaseA.countDown();
            runA.get(60, TimeUnit.SECONDS);
            assertThat(scheduleRow(id)).as("旧Aのcompletionは引き継いだBの完了状態を上書きしない").isEqualTo(completedByB);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
            detachSchedulerLog(logs);
        }
        long runId = resultA.get().getRun().getId();
        verify(stallingDelivery, times(1)).deliverScheduled(eq(runId), any());
        verify(deliveryB, times(1)).deliverScheduled(eq(runId), any());
        return new Takeover(id, workerA, workerB, resultA.get(), resultB.get(), commandB.get(), messages(logs));
    }

    private void assertConvergedToSingleDelivery(Takeover takeover) {
        ReportGenerationResult a = takeover.resultA();
        ReportGenerationResult b = takeover.resultB();
        assertThat(a.isReused()).as("Aは新規run").isFalse();
        assertThat(b).as("Bも生成要求を出した（重複実行が実際に起きた）").isNotNull();
        assertThat(b.isReused()).as("Bは同一論理runの成功runを再利用する").isTrue();
        long runId = a.getRun().getId();
        assertThat(b.getRun().getId()).isEqualTo(runId);
        assertThat(countRunsForSchedule(takeover.scheduleId())).isEqualTo(1);
        assertThat(countRunsByKey(a.getRun().getRunKey())).isEqualTo(1);
        assertSingleDelivery(runId);
        verify(documentRegistrar, times(1)).registerArtifact(runId, "PDF");
        assertThat(takeover.workerB().events(ReportScheduleMetrics.Event.STALE_LEASE_TAKEOVER)).isEqualTo(1.0);
        assertThat(takeover.workerB().events(ReportScheduleMetrics.Event.RUN_SUCCEEDED)).isEqualTo(1.0);
        assertThat(takeover.workerA().events(ReportScheduleMetrics.Event.OWNERSHIP_LOST)).isEqualTo(1.0);
        assertThat(takeover.workerA().events(ReportScheduleMetrics.Event.RUN_SUCCEEDED)).isZero();
        assertThat(takeover.messages()).anyMatch(message -> message.contains("期限切れprocessing leaseを引き継ぎました"));
        assertThat(takeover.messages()).anyMatch(message -> message.contains("success completionでclaim所有権を失いました"));
    }

    private record Takeover(long scheduleId, Harness workerA, Harness workerB, ReportGenerationResult resultA,
                            ReportGenerationResult resultB, ReportGenerationCommand commandB, List<String> messages) {
    }

    @Test
    void 同一run_keyへ並行して生成と配布を投入しても配布は1回に収束する() throws Exception {
        long id = insertSchedule(SEPT);
        ReportGenerationCommand command = ReportGenerationCommand.scheduled(templateVersionId, TARGET, "速報", id,
                adminId, savedScope(), PREVIEW_HASH);
        int workers = 8;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Long> deliveredRunIds = new ArrayList<>();
        List<Throwable> rejected = new ArrayList<>();
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                String idempotencyKey = "NF10-CONCURRENT-" + i;
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return generateAndDeliver(command, id, idempotencyKey);
                }));
            }
            for (Future<Long> future : futures) {
                try {
                    deliveredRunIds.add(future.get(120, TimeUnit.SECONDS));
                } catch (ExecutionException ex) {
                    rejected.add(ex.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(deliveredRunIds).as("少なくとも1件は生成から配布まで完了する: rejected=%s", describe(rejected))
                .isNotEmpty();
        assertThat(new HashSet<>(deliveredRunIds)).as("完了した投入は全て同じrunを指す").hasSize(1);
        // 失敗した投入は一意制約（uk_report_run_key / uk_report_delivery_recipient）またはlock競合で拒否されたものだけ。
        // いずれもtransactionごとrollbackされ、通知・outboxを残さない。
        assertThat(rejected).as("想定外の失敗: %s", describe(rejected))
                .allSatisfy(failure -> assertThat(isIdempotencyRejection(failure)).isTrue());
        long runId = deliveredRunIds.get(0);
        String runKey = jdbcTemplate.queryForObject("SELECT run_key FROM t_report_run WHERE id = ?", String.class, runId);
        assertThat(countRunsForSchedule(id)).isEqualTo(1);
        assertThat(countRunsByKey(runKey)).isEqualTo(1);
        assertSingleDelivery(runId);

        // 並行投入の後で同じ論理runを再投入しても、同じrun・deliveryを再利用して通知を増やさない。
        assertThat(generateAndDeliver(command, id, "NF10-REPEAT")).isEqualTo(runId);
        assertThat(countRunsForSchedule(id)).isEqualTo(1);
        assertSingleDelivery(runId);
    }

    // =========================================================================
    // retry上限による自動停止と手動再開
    // =========================================================================

    @Test
    void 連続12回目の失敗で計画を停止し停止理由を一覧で返し管理者の有効化で同一論理runから再開する() {
        long id = insertSchedule(SEPT);
        jdbcTemplate.update("UPDATE m_report_schedule SET failure_count = 10 WHERE id = ?", id);
        ReportSnapshotService failing = mock(ReportSnapshotService.class);
        when(failing.generate(any())).thenThrow(new IllegalStateException("source unavailable"));
        Harness harness = new Harness(SEPT.plusSeconds(30), failing, mock(ReportDeliveryService.class));
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        LocalDateTime eleventhRetryAt;
        try {
            harness.scheduler.dispatchDue();
            assertThat(intColumn(id, "enabled")).as("11回目の失敗では停止しない").isEqualTo(1);
            assertThat(intColumn(id, "failure_count")).isEqualTo(11);
            eleventhRetryAt = dateTime(id, "retry_scheduled_at");
            // 失敗検知 00:00:30 + 退避55分（failureCount=10 → 5×11）。
            assertThat(eleventhRetryAt).isEqualTo(SEPT.plusSeconds(30).plusMinutes(55));

            harness.clock.set(eleventhRetryAt.plusMinutes(1));
            harness.scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }

        // MySQLはSET句を左から評価するが、停止判定とerror codeは加算前のfailure_countに基づく。
        assertThat(intColumn(id, "enabled")).as("12回目の失敗で停止").isZero();
        assertThat(intColumn(id, "failure_count")).isEqualTo(ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES);
        assertThat(stringColumn(id, "last_error_code")).isEqualTo(ReportScheduleMapper.RETRY_EXHAUSTED_ERROR_CODE);
        assertThat(stringColumn(id, "last_error_message")).isNotBlank();
        LocalDateTime twelfthRetryAt = dateTime(id, "retry_scheduled_at");
        assertThat(twelfthRetryAt).as("retry予定は保持（退避60分）").isEqualTo(eleventhRetryAt.plusMinutes(61));
        assertThat(dateTime(id, "processing_logical_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(id, "next_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(id, "processing_claimed_at")).isNull();
        assertThat(token(id)).isNull();
        assertThat(harness.events(ReportScheduleMetrics.Event.RUN_FAILED)).isEqualTo(2.0);
        assertThat(harness.events(ReportScheduleMetrics.Event.RETRY_EXHAUSTED_DISABLED)).isEqualTo(1.0);
        assertThat(messages(logs, Level.ERROR)).anyMatch(message -> message.contains("連続失敗が上限（12回）")
                && message.contains("scheduleId=" + id));

        // 停止中はretry時刻を過ぎてもclaimしない。
        harness.clock.set(twelfthRetryAt.plusMinutes(1));
        harness.scheduler.dispatchDue();
        verify(failing, times(2)).generate(any());

        // 停止理由はschedule一覧API（ReportScheduleService#list）の戻り値で確認できる。
        authenticateAdmin();
        ReportSchedule listed = scheduleService.list().stream()
                .filter(schedule -> schedule.getId() == id).findFirst().orElseThrow();
        assertThat(listed.getEnabled()).isZero();
        assertThat(listed.getLastErrorCode()).isEqualTo(ReportScheduleMapper.RETRY_EXHAUSTED_ERROR_CODE);
        assertThat(listed.getFailureCount()).isEqualTo(ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES);

        // 管理者の有効化は連続失敗回数だけを0へ戻し、retry予定と論理runを保持する。
        ReportSchedule resumed = scheduleService.setEnabled(id, true);
        SecurityContextHolder.clearContext();
        assertThat(resumed.getEnabled()).isEqualTo(1);
        assertThat(resumed.getFailureCount()).isZero();
        assertThat(resumed.getRetryScheduledAt()).isEqualTo(twelfthRetryAt);
        assertThat(resumed.getProcessingLogicalRunAt()).isEqualTo(SEPT);

        ReportSnapshotService recovering = mock(ReportSnapshotService.class);
        when(recovering.generate(any())).thenReturn(succeeded(9001L));
        ReportDeliveryService delivery = mock(ReportDeliveryService.class);
        Harness recovered = new Harness(twelfthRetryAt.plusMinutes(2), recovering, delivery);
        recovered.scheduler.dispatchDue();

        ArgumentCaptor<ReportGenerationCommand> resumedCommand = ArgumentCaptor.forClass(ReportGenerationCommand.class);
        verify(recovering).generate(resumedCommand.capture());
        assertThat(resumedCommand.getValue().period()).as("同一論理run（2026-09-01 → 対象2026-08）を再開").isEqualTo(TARGET);
        verify(delivery).deliverScheduled(eq(9001L), any());
        assertCompletedSuccessfully(id, OCT, SEPT);
        assertThat(intColumn(id, "enabled")).isEqualTo(1);
    }

    // =========================================================================
    // 起動guard（legacy lease検出）
    // =========================================================================

    @Test
    void legacy_leaseがある間は全claimを止めdrain確認後だけ起動前のleaseを期限後に引き継ぐ() {
        long due = insertSchedule(SEPT);
        long legacy = insertSchedule(SEPT);
        String lease = "UPDATE m_report_schedule SET processing_logical_run_at = ?, last_run_at = ?, "
                + "processing_claimed_at = ? WHERE id = ?";
        jdbcTemplate.update(lease, SEPT, SEPT, SEPT.plusMinutes(5), legacy);
        ReportSnapshotService snapshot = mock(ReportSnapshotService.class);
        when(snapshot.generate(any())).thenAnswer(invocation ->
                succeeded(7000L + ((ReportGenerationCommand) invocation.getArgument(0)).scheduleId()));
        ReportDeliveryService delivery = mock(ReportDeliveryService.class);
        LocalDateTime afterExpiry = SEPT.plusMinutes(40);
        Map<String, Object> dueBefore = scheduleRow(due);
        Map<String, Object> legacyBefore = scheduleRow(legacy);

        // 1) drain未確認: legacy leaseが期限切れでも、通常dueを含む全claimを止める。
        Harness unconfirmed = new Harness(afterExpiry, snapshot, delivery);
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            unconfirmed.scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }
        verifyNoInteractions(snapshot, delivery);
        assertThat(scheduleRow(due)).as("通常dueもclaimしない").isEqualTo(dueBefore);
        assertThat(scheduleRow(legacy)).as("legacy leaseを触らない").isEqualTo(legacyBefore);
        assertThat(unconfirmed.events(ReportScheduleMetrics.Event.LEGACY_LEASE_BLOCKED)).isEqualTo(1.0);
        assertThat(unconfirmed.legacyLeaseGauge()).isEqualTo(1.0);
        assertThat(messages(logs, Level.ERROR)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_DETECTED") && message.contains("legacyLeaseCount=1"));

        // 2) drain確認時刻（=起動時刻 00:40）を設定しても、それ以降に取得されたlegacy lease（旧版schedulerが稼働中）が
        //    あれば止める。
        Harness confirmed = new Harness(afterExpiry, snapshot, delivery);
        ReflectionTestUtils.setField(confirmed.scheduler, "legacyLeaseDrainConfirmedBefore",
                afterExpiry.atZone(TOKYO).toOffsetDateTime().toString());
        long active = insertSchedule(SEPT);
        jdbcTemplate.update(lease, SEPT, SEPT, afterExpiry.plusMinutes(1), active);
        confirmed.clock.set(afterExpiry.plusMinutes(2));
        logs = attachSchedulerLog();
        try {
            confirmed.scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }
        verifyNoInteractions(snapshot, delivery);
        assertThat(scheduleRow(due)).isEqualTo(dueBefore);
        assertThat(confirmed.events(ReportScheduleMetrics.Event.LEGACY_LEASE_ACTIVE_BLOCKED)).isEqualTo(1.0);
        assertThat(messages(logs, Level.ERROR)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_ACTIVE") && message.contains("sinceStartupCount=1"));

        // 3) 旧版nodeが停止して起動後のleaseが無くなれば（ここでは旧版のsuccess completion相当で解放）、
        //    起動前から残るlegacy leaseはstale後にtoken=NULL一致のCASで引き継がれ、通常dueも処理される。
        jdbcTemplate.update("UPDATE m_report_schedule SET next_run_at = ?, processing_claimed_at = NULL, "
                + "processing_logical_run_at = NULL WHERE id = ?", OCT, active);
        logs = attachSchedulerLog();
        try {
            confirmed.scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }
        verify(snapshot, times(2)).generate(any());
        assertCompletedSuccessfully(due, OCT, SEPT);
        assertCompletedSuccessfully(legacy, OCT, SEPT);
        assertThat(confirmed.events(ReportScheduleMetrics.Event.LEGACY_LEASE_DRAIN_ACCEPTED)).isEqualTo(1.0);
        assertThat(confirmed.events(ReportScheduleMetrics.Event.STALE_LEASE_TAKEOVER)).isEqualTo(1.0);
        assertThat(confirmed.events(ReportScheduleMetrics.Event.RUN_SUCCEEDED)).isEqualTo(2.0);
        assertThat(messages(logs, Level.WARN)).anyMatch(message ->
                message.contains("errorCode=SCHEDULE_LEGACY_LEASE_DRAIN_ACCEPTED"));
        assertThat(messages(logs, Level.ERROR)).isEmpty();

        // 4) 引き継ぎ後はlegacy leaseが0件になり、gaugeも0へ戻る。
        confirmed.clock.set(afterExpiry.plusMinutes(3));
        confirmed.scheduler.dispatchDue();
        assertThat(scheduleMapper.countLegacyLeases(TENANT, null)).isZero();
        assertThat(confirmed.legacyLeaseGauge()).isZero();
        assertThat(confirmed.events(ReportScheduleMetrics.Event.LEGACY_LEASE_DRAIN_ACCEPTED)).isEqualTo(1.0);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    /**
     * dispatchDue()用のscheduler。mapper/claim service/月次締め/tenant runner/timezoneは実bean、metricはtest専用registry。
     * 月次締めは固定値にせず、{@code t_monthly_closing} の状態（{@link #closeTargetMonth()}）で決まる。
     */
    private final class Harness {
        final MutableClock clock;
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final ManagementReportScheduler scheduler;

        Harness(LocalDateTime startAt, ReportSnapshotService snapshot, ReportDeliveryService delivery) {
            clock = new MutableClock(startAt.atZone(TOKYO).toInstant());
            scheduler = new ManagementReportScheduler(scheduleMapper, claimService, clock, snapshot, delivery,
                    monthlyClosingService, timezoneResolver, tenantAwareBatchRunner, recipientPreviewService,
                    objectMapper);
            ReflectionTestUtils.setField(scheduler, "scheduleMetrics", new ReportScheduleMetrics(registry));
        }

        double events(ReportScheduleMetrics.Event event) {
            Counter counter = registry.find(ReportScheduleMetrics.EVENTS_METRIC)
                    .tag(ReportScheduleMetrics.EVENT_TAG, event.name()).counter();
            return counter == null ? 0.0 : counter.count();
        }

        double legacyLeaseGauge() {
            Gauge gauge = registry.find(ReportScheduleMetrics.LEGACY_LEASES_METRIC).gauge();
            assertThat(gauge).as("legacy lease gauge").isNotNull();
            return gauge.value();
        }
    }

    /** 実snapshot serviceへ委譲し、生成要求（要求cutoff）と生成結果（新規/再利用・実cutoff）を記録する。 */
    private ReportSnapshotService recording(AtomicReference<ReportGenerationResult> holder,
                                            AtomicReference<ReportGenerationCommand> commandHolder) {
        ReportSnapshotService recording = mock(ReportSnapshotService.class);
        when(recording.generate(any())).thenAnswer(invocation -> {
            ReportGenerationCommand command = invocation.getArgument(0);
            commandHolder.set(command);
            ReportGenerationResult result = snapshotService.generate(command);
            holder.set(result);
            return result;
        });
        return recording;
    }

    /** 実delivery serviceへ委譲し、呼出回数を検証できるようにする。 */
    private ReportDeliveryService delegating(ReportDeliveryService real) {
        ReportDeliveryService delegating = mock(ReportDeliveryService.class);
        when(delegating.deliverScheduled(anyLong(), any())).thenAnswer(invocation ->
                real.deliverScheduled(invocation.getArgument(0), invocation.getArgument(1)));
        return delegating;
    }

    /** 対象月（2026-08）の月次締めを確定状態にする（confirm処理の前提検証は本testの対象外のため行を直接置く）。 */
    private void closeTargetMonth() {
        reopenTargetMonth();
        jdbcTemplate.update("INSERT INTO t_monthly_closing (tenant_id, work_month, confirmed_by, confirmed_at, version) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)", TENANT, TARGET.toString(), adminId);
    }

    private void reopenTargetMonth() {
        jdbcTemplate.update("DELETE FROM t_monthly_closing WHERE tenant_id = ? AND work_month = ?",
                TENANT, TARGET.toString());
    }

    /** schedulerと同じsystem主体・tenant境界で、実serviceの生成と配布を続けて呼ぶ。 */
    private Long generateAndDeliver(ReportGenerationCommand command, long scheduleId, String idempotencyKey) {
        return ExecutionActorContext.runAsSystem("nf10-dispatch-policy-test", idempotencyKey, () -> {
            return AccountingTenantContextHolder.runWithTenant(TENANT, TOKYO, () -> {
                ReportGenerationResult result = snapshotService.generate(command);
                ReportRun run = result.getRun();
                if (run == null || !"SUCCEEDED".equals(run.getStatus())) {
                    throw new IllegalStateException("生成が成功しませんでした: status="
                            + (run == null ? null : run.getStatus()));
                }
                deliveryService.deliverScheduled(run.getId(), ReportScheduledDeliveryContext.of(scheduleId, adminId));
                return run.getId();
            });
        });
    }

    private ReportScopeSnapshot savedScope() {
        return new ReportScopeSnapshot("COMPANY", null, true, List.of(), List.of(), "scope-policy-approved-1",
                SCOPE_JSON, sha256(SCOPE_JSON), List.of(), List.of(), List.of());
    }

    /** 一意制約・lock競合による拒否か（いずれもtransactionごとrollbackされ副作用を残さない）。 */
    private static boolean isIdempotencyRejection(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataIntegrityViolationException
                    || cause instanceof PessimisticLockingFailureException
                    || cause instanceof SQLIntegrityConstraintViolationException
                    || cause instanceof SQLTransactionRollbackException) {
                return true;
            }
        }
        return false;
    }

    private static List<String> describe(List<Throwable> failures) {
        return failures.stream().map(failure -> failure.getClass().getName() + ": " + failure.getMessage()).toList();
    }

    private void assertSingleDelivery(long runId) {
        assertThat(countDeliveries(runId)).as("delivery行").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM t_report_delivery WHERE run_id = ?",
                Integer.class, runId)).as("delivery attempt").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT delivery_status FROM t_report_delivery WHERE run_id = ?",
                String.class, runId)).isEqualTo("ENQUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_notification WHERE dedupe_key LIKE "
                + "CONCAT('REPORT:', ?, ':%')", Integer.class, runId)).as("アプリ内通知").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_notification_outbox WHERE dedupe_key LIKE "
                + "CONCAT('REPORT:', ?, ':%')", Integer.class, runId)).as("通知outbox").isEqualTo(1);
    }

    private int countDeliveries(long runId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_report_delivery WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private int countRunsForSchedule(long scheduleId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_report_run WHERE tenant_id = ? "
                + "AND schedule_id = ?", Integer.class, TENANT, scheduleId);
        return count == null ? 0 : count;
    }

    private int countRunsByKey(String runKey) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_report_run WHERE tenant_id = ? "
                + "AND run_key = ?", Integer.class, TENANT, runKey);
        return count == null ? 0 : count;
    }

    private long insertPublishedTemplateVersion() {
        String templateKey = FIXTURE_PREFIX + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO m_report_template (tenant_id, template_key, template_name, status, created_by) "
                + "VALUES (?, ?, 'NF10 dispatch policy', 'ACTIVE', ?)", TENANT, templateKey, adminId);
        Long templateId = jdbcTemplate.queryForObject("SELECT id FROM m_report_template WHERE tenant_id = ? "
                + "AND template_key = ?", Long.class, TENANT, templateKey);
        assertThat(templateId).isNotNull();
        templateIds.add(templateId);
        // sectionは正本値をmockできるAR agingの1件だけにする（他sectionの正本serviceはdedupe境界と無関係）。
        jdbcTemplate.update("INSERT INTO m_report_template_version (tenant_id, template_id, version_no, status, "
                        + "section_config_json, format_config_json, recipient_config_json, scope_config_json, timezone_id, "
                        + "retention_years, created_by, published_at) VALUES (?, ?, 1, 'PUBLISHED', ?, '{}', '{}', '{}', "
                        + "'Asia/Tokyo', 7, ?, CURRENT_TIMESTAMP)",
                TENANT, templateId, "[\"ar-aging\"]", adminId);
        Long versionId = jdbcTemplate.queryForObject("SELECT id FROM m_report_template_version WHERE template_id = ? "
                + "AND version_no = 1", Long.class, templateId);
        assertThat(versionId).isNotNull();
        return versionId;
    }

    private long insertSchedule(LocalDateTime nextRunAt) {
        String lockKey = FIXTURE_PREFIX + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO m_report_schedule (tenant_id, template_version_id, cron_expression, "
                        + "timezone_id, enabled, lock_key, next_run_at, scope_owner_type, organization_scope_json, "
                        + "scope_policy_version, scope_hash, failure_count, created_by, updated_by) "
                        + "VALUES (?, ?, ?, 'Asia/Tokyo', 1, ?, ?, 'COMPANY', ?, 'scope-policy-approved-1', ?, 0, ?, ?)",
                TENANT, templateVersionId, CRON, lockKey, nextRunAt, SCOPE_JSON, sha256(SCOPE_JSON), adminId, adminId);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM m_report_schedule WHERE tenant_id = ? AND lock_key = ?",
                Long.class, TENANT, lockKey);
        assertThat(id).isNotNull();
        scheduleIds.add(id);
        return id;
    }

    private Map<String, Object> scheduleRow(long id) {
        return jdbcTemplate.queryForMap("SELECT enabled, next_run_at, last_run_at, retry_scheduled_at, "
                + "processing_logical_run_at, processing_claimed_at, processing_claim_token, failure_count, "
                + "last_error_code, last_error_message, version FROM m_report_schedule WHERE id = ?", id);
    }

    private LocalDateTime dateTime(long id, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM m_report_schedule WHERE id = ?",
                LocalDateTime.class, id);
    }

    private String token(long id) {
        return stringColumn(id, "processing_claim_token");
    }

    private String stringColumn(long id, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM m_report_schedule WHERE id = ?",
                String.class, id);
    }

    private int intColumn(long id, String column) {
        Integer value = jdbcTemplate.queryForObject("SELECT " + column + " FROM m_report_schedule WHERE id = ?",
                Integer.class, id);
        assertThat(value).as(column).isNotNull();
        return value;
    }

    private void assertCompletedSuccessfully(long id, LocalDateTime nextRunAt, LocalDateTime logicalRunAt) {
        assertThat(dateTime(id, "next_run_at")).as("next_run_at").isEqualTo(nextRunAt);
        assertThat(dateTime(id, "last_run_at")).as("last_run_at").isEqualTo(logicalRunAt);
        assertThat(dateTime(id, "processing_claimed_at")).as("processing_claimed_at").isNull();
        assertThat(dateTime(id, "processing_logical_run_at")).as("processing_logical_run_at").isNull();
        assertThat(dateTime(id, "retry_scheduled_at")).as("retry_scheduled_at").isNull();
        assertThat(token(id)).as("processing_claim_token").isNull();
        assertThat(intColumn(id, "failure_count")).as("failure_count").isZero();
        assertThat(stringColumn(id, "last_error_code")).as("last_error_code").isNull();
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(adminId), "N/A", List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    private static ReportGenerationResult succeeded(long runId) {
        ReportRun run = new ReportRun();
        run.setId(runId);
        run.setStatus("SUCCEEDED");
        return new ReportGenerationResult(run, List.of(), false);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch待機がtimeoutしました");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch待機中に割り込まれました", ex);
        }
    }

    private ListAppender<ILoggingEvent> attachSchedulerLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ManagementReportScheduler.class)).addAppender(appender);
        return appender;
    }

    private void detachSchedulerLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ManagementReportScheduler.class)).detachAppender(appender);
        appender.stop();
    }

    private List<String> messages(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return new ArrayList<>(appender.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    private List<String> messages(ListAppender<ILoggingEvent> appender, Level level) {
        synchronized (appender) {
            return new ArrayList<>(appender.list).stream()
                    .filter(event -> level.equals(event.getLevel()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** scheduler処理中に進められる可変時計。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(LocalDateTime localDateTime) {
            instant = localDateTime.atZone(TOKYO).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return TOKYO;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
