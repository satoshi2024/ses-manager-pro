package com.ses.report;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportScheduleService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.scheduler.ManagementReportScheduler;
import com.ses.service.scheduler.ReportScheduleClaimService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NF-10 scheduler claim fence（V185）の実MySQL 8受入試験（scheduler-claim-fence-plan.md §7の8項目）。
 *
 * <ul>
 *   <li>Spring context起動前にFlyway APIでV184まで適用し、V185以前のlegacy lease行を投入する。その後アプリ自身の
 *       Flyway（spring.flyway.enabled=true）がV184→最新へupgradeする（§7-8、本番起動時と同じ経路）。</li>
 *   <li>claimは実Spring beanの {@link ReportScheduleClaimService}（REQUIRES_NEW proxy）を使う。</li>
 *   <li>競合は2接続＋latch＋{@code performance_schema.data_lock_waits} による行lock待ち辺の観測、またはbarrierで
 *       再現し、H2を並行性の根拠にしない。</li>
 *   <li>Docker必須・零skip（{@code disabledWithoutDocker} は使わない）。fixtureはsyntheticなtenant/scheduleだけを使う。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class ReportScheduleClaimFenceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf10_claim_fence")
            .withUsername("root")
            .withPassword("ses");

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final String TENANT = "default";
    private static final String OTHER_TENANT = "nf10-fence-other";
    /** inventory外のtenant。dispatchDue()の対象にならないためlegacy行が他testへ干渉しない。 */
    private static final String LEGACY_TENANT = "nf10-fence-legacy";
    private static final String LEGACY_LOCK_KEY = "nf10-fence-v184-legacy-lease";
    private static final String CRON = "0 0 0 1 * *";
    private static final LocalDateTime SEPT = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime OCT = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime NOV = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime LEGACY_CLAIMED_AT = LocalDateTime.of(2026, 9, 1, 0, 5);
    private static final String ERROR_CODE = "SCHEDULE_GENERATION_FAILED";
    private static final String ERROR_MESSAGE = "error.system.unexpected";
    private static final String SCOPE_JSON = "{\"ownerType\":\"COMPANY\",\"ownerId\":null,\"companyWide\":true,"
            + "\"organizationIds\":[],\"directUserIds\":[],\"engineerIds\":[],\"contractIds\":[],"
            + "\"invoiceIds\":[],\"policyVersion\":\"scope-policy-approved-1\"}";
    private static final String ROW_COLUMNS = "tenant_id, enabled, deleted_flag, next_run_at, last_run_at, "
            + "retry_scheduled_at, processing_logical_run_at, processing_claimed_at, processing_claim_token, "
            + "failure_count, last_error_code, last_error_message, updated_by, version, updated_at";
    private static final String LEASE_COLUMNS = "next_run_at, last_run_at, retry_scheduled_at, "
            + "processing_logical_run_at, processing_claimed_at, processing_claim_token, failure_count";

    /** 73aa7000（V185以前）の旧版claim SQL。新旧scheduler混在・rollback後の状態を再現するためだけに使う。 */
    private static final String LEGACY_CLAIM_SQL = "UPDATE m_report_schedule SET processing_logical_run_at = ?, "
            + "processing_claimed_at = ?, last_run_at = ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = ? AND id = ? AND enabled = 1 AND deleted_flag = 0 "
            + "AND (processing_claimed_at IS NULL OR processing_claimed_at < ?) "
            + "AND ((retry_scheduled_at IS NOT NULL AND retry_scheduled_at <= ?) "
            + "     OR (retry_scheduled_at IS NULL AND next_run_at IS NOT NULL AND next_run_at <= ? "
            + "         AND next_run_at = ?))";
    /** 73aa7000の旧版success completion（tokenを知らずprocessing_claimed_atだけをclearする）。 */
    private static final String LEGACY_MARK_SUCCESS_SQL = "UPDATE m_report_schedule SET next_run_at = ?, "
            + "processing_claimed_at = NULL, processing_logical_run_at = NULL, retry_scheduled_at = NULL, "
            + "failure_count = 0, last_error_code = NULL, last_error_message = NULL, last_run_at = ?, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND processing_claimed_at IS NOT NULL";
    /** 73aa7000の旧版failure completion。 */
    private static final String LEGACY_MARK_FAILURE_SQL = "UPDATE m_report_schedule SET "
            + "failure_count = COALESCE(failure_count, 0) + 1, retry_scheduled_at = ?, processing_claimed_at = NULL, "
            + "last_error_code = ?, last_error_message = ?, last_run_at = ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = ? AND id = ? AND processing_claimed_at IS NOT NULL";

    /**
     * holder接続（bt）が保持するm_report_scheduleのPRIMARY record lockに対し、別接続（rt）で実行中のclaim UPDATEが
     * 待っているlock待ち辺を返す。列は待っている側の接続ID（{@code CONNECTION_ID()} と同じ値）、blocking lockの
     * ENGINE_LOCK_ID、待機lockのmode、両端のLOCK_DATA（診断・可視時の照合用）。
     *
     * <p>{@code performance_schema.data_locks}/{@code data_lock_waits} はInnoDBのlock systemを照会のたびに走査する。
     * {@code information_schema.innodb_trx} はInnoDBの {@code trx_i_s} cacheから返され、cacheは「最後の読取から
     * 0.1秒を超えて経過した」場合にだけ再取得されるため、20ms間隔のpollingでは最初の読取時点のsnapshotが固定され、
     * その後に発生したLOCK WAITを観測できない。93c11457の定向MySQL実行で本testが失敗した原因であり、使わない。
     *
     * <p>{@code LOCK_DATA}はInnoDBが対象pageをlatch待ちなしで参照できた場合だけ鍵値を返し、待機中でもNULLになり得るため
     * （{@link InnoDbRecordLockWaits}）、ここでは結合・絞込みに使わない。対象行の特定は
     * {@link #anchoredClaimRowLockWaits}が、holderの単一排他record lockを指す辺だけを採ることで行う。
     */
    private static final String CLAIM_ROW_LOCK_WAIT_SQL = "SELECT rt.PROCESSLIST_ID, blk.ENGINE_LOCK_ID, "
            + "req.LOCK_MODE, req.LOCK_DATA, blk.LOCK_DATA "
            + "FROM performance_schema.data_lock_waits w "
            + "JOIN performance_schema.data_locks req ON req.ENGINE = w.ENGINE "
            + "  AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
            + "JOIN performance_schema.data_locks blk ON blk.ENGINE = w.ENGINE "
            + "  AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
            + "JOIN performance_schema.threads rt ON rt.THREAD_ID = req.THREAD_ID "
            + "JOIN performance_schema.threads bt ON bt.THREAD_ID = blk.THREAD_ID "
            + "WHERE req.OBJECT_SCHEMA = DATABASE() AND req.OBJECT_NAME = 'm_report_schedule' "
            + "  AND req.INDEX_NAME = 'PRIMARY' AND req.LOCK_TYPE = 'RECORD' "
            + "  AND req.LOCK_STATUS = 'WAITING' AND req.LOCK_MODE LIKE 'X%' "
            + "  AND blk.OBJECT_SCHEMA = DATABASE() AND blk.OBJECT_NAME = 'm_report_schedule' "
            + "  AND blk.INDEX_NAME = 'PRIMARY' AND blk.LOCK_TYPE = 'RECORD' "
            + "  AND blk.LOCK_STATUS = 'GRANTED' AND blk.LOCK_MODE LIKE 'X%' "
            + "  AND bt.PROCESSLIST_ID = ? "
            + "  AND rt.PROCESSLIST_ID <> ? AND rt.PROCESSLIST_ID <> CONNECTION_ID() "
            + "  AND rt.PROCESSLIST_INFO LIKE '%UPDATE m_report_schedule SET processing_logical_run_at%'";

    private static final Object V184_PREPARE_LOCK = new Object();
    private static boolean v184Prepared;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ReportScheduleClaimFenceMySqlTest::prepareV184DatabaseWithLegacyLease);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    /**
     * context起動前（datasource URL解決時）に一度だけ、V184までのschemaとV185以前のlegacy lease行を用意する。
     * その後のアプリ起動でFlywayがV185を適用するため、本classは常にV184→V185のupgrade後のDBを検証する。
     */
    private static String prepareV184DatabaseWithLegacyLease() {
        synchronized (V184_PREPARE_LOCK) {
            if (!v184Prepared) {
                Flyway.configure()
                        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .target("184")
                        .load()
                        .migrate();
                try (Connection connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                     Statement statement = connection.createStatement()) {
                    try (ResultSet columns = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = 'm_report_schedule' "
                            + "AND column_name = 'processing_claim_token'")) {
                        if (!columns.next() || columns.getInt(1) != 0) {
                            throw new IllegalStateException("V184時点でprocessing_claim_token列が存在します");
                        }
                    }
                    statement.executeUpdate("INSERT INTO m_report_schedule (tenant_id, template_version_id, "
                            + "cron_expression, timezone_id, enabled, lock_key, next_run_at, last_run_at, "
                            + "processing_logical_run_at, processing_claimed_at, failure_count) VALUES ('"
                            + LEGACY_TENANT + "', 3, '" + CRON + "', 'Asia/Tokyo', 1, '" + LEGACY_LOCK_KEY + "', "
                            + "'2026-09-01 00:00:00', '2026-09-01 00:00:00', '2026-09-01 00:00:00', "
                            + "'2026-09-01 00:05:00', 0)");
                } catch (SQLException ex) {
                    throw new IllegalStateException("V184のlegacy lease fixtureを投入できません", ex);
                }
                v184Prepared = true;
            }
        }
        return MYSQL.getJdbcUrl();
    }

    @Autowired
    private ReportScheduleMapper scheduleMapper;

    @Autowired
    private ReportScheduleClaimService claimService;

    @Autowired
    private ReportScheduleService scheduleService;

    @Autowired
    private AccountingTimezoneResolver timezoneResolver;

    @Autowired
    private TenantAwareBatchRunner tenantAwareBatchRunner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        deleteFixtures();
    }

    @AfterEach
    void tearDown() {
        deleteFixtures();
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    // =========================================================================
    // §7-8 V184→V185 upgradeとlegacy lease
    // =========================================================================

    @Test
    void V184のlegacy_lease行はV185適用後もtoken無しで保持され期限切れ後だけ引き継げる() {
        Integer rank184 = jdbcTemplate.queryForObject(
                "SELECT installed_rank FROM flyway_schema_history WHERE version = '184' AND success = 1", Integer.class);
        Integer rank185 = jdbcTemplate.queryForObject(
                "SELECT installed_rank FROM flyway_schema_history WHERE version = '185' AND success = 1", Integer.class);
        assertThat(rank185).as("V185はV184適用済みDBへ後から適用される").isGreaterThan(rank184);
        assertThat(jdbcTemplate.queryForObject("SELECT CONCAT(column_type, '|', character_set_name, '|', "
                        + "collation_name, '|', is_nullable) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'm_report_schedule' "
                        + "AND column_name = 'processing_claim_token'", String.class))
                .isEqualTo("char(36)|ascii|ascii_bin|YES");

        long legacyId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_report_schedule WHERE tenant_id = ? AND lock_key = ?",
                Long.class, LEGACY_TENANT, LEGACY_LOCK_KEY);
        assertThat(dateTime(legacyId, "processing_claimed_at")).isEqualTo(LEGACY_CLAIMED_AT);
        assertThat(dateTime(legacyId, "processing_logical_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(legacyId, "next_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(legacyId, "last_run_at")).isEqualTo(SEPT);
        assertThat(token(legacyId)).as("migrationはownerを捏造しない").isNull();

        // 期限前（claimed 00:05 / staleBefore 23:50）はdue候補にもclaim対象にもならない。
        LocalDateTime beforeExpiry = LocalDateTime.of(2026, 9, 1, 0, 20);
        assertThat(selectDue(LEGACY_TENANT, beforeExpiry, 50)).isEmpty();
        assertThat(claim(scheduleMapper.selectById(legacyId), SEPT, beforeExpiry)).isNull();
        Map<String, Object> untouched = row(legacyId);
        // token無しのcompletion経路は無い（legacy ownerを新版が完了扱いしない）。
        assertThat(scheduleMapper.markSuccess(LEGACY_TENANT, legacyId, null, LEGACY_CLAIMED_AT, OCT, SEPT)).isZero();
        assertThat(scheduleMapper.markFailure(LEGACY_TENANT, legacyId, null, LEGACY_CLAIMED_AT, beforeExpiry, SEPT,
                ERROR_CODE, ERROR_MESSAGE)).isZero();
        assertThat(row(legacyId)).isEqualTo(untouched);

        // 期限後はstale候補として選ばれ、claimedAt/logicalRunAt/token=NULLの完全一致CASで新tokenを得る。
        LocalDateTime afterExpiry = LocalDateTime.of(2026, 9, 1, 0, 40);
        ReportSchedule stale = dueSnapshot(LEGACY_TENANT, legacyId, afterExpiry);
        assertThat(stale.getProcessingClaimToken()).isNull();
        ReportScheduleClaimService.Claim takeover = claim(stale, SEPT, afterExpiry);
        assertThat(takeover).isNotNull();
        assertThat(token(legacyId)).isEqualTo(takeover.token()).hasSize(36);
        assertThat(dateTime(legacyId, "processing_claimed_at")).isEqualTo(afterExpiry);
        assertThat(scheduleMapper.markSuccess(LEGACY_TENANT, legacyId, takeover.token(), takeover.claimedAt(),
                OCT, SEPT)).isEqualTo(1);
        assertCompletedSuccessfully(legacyId, OCT, SEPT);
    }

    // =========================================================================
    // §7-1/§7-2 A stale takeover後の旧A completion拒否
    // =========================================================================

    @Test
    void stale_takeover後の旧Aのsuccess_completionは0件でBの状態を一切変えない() {
        Takeover takeover = staleTakeover();
        long id = takeover.id();
        Map<String, Object> ownedByB = row(id);

        assertThat(scheduleMapper.markSuccess(TENANT, id, takeover.ownerA().token(),
                takeover.ownerA().claimedAt(), OCT, SEPT)).isZero();
        // tokenとclaimedAtのどちらか一方だけが一致しても所有者とは見なさない。
        assertThat(scheduleMapper.markSuccess(TENANT, id, takeover.ownerA().token(),
                takeover.ownerB().claimedAt(), OCT, SEPT)).isZero();
        assertThat(scheduleMapper.markSuccess(TENANT, id, takeover.ownerB().token(),
                takeover.ownerA().claimedAt(), OCT, SEPT)).isZero();
        // ascii_binのためtokenの大文字小文字違いも別ownerとして扱う。
        String upperB = takeover.ownerB().token().toUpperCase(Locale.ROOT);
        if (!upperB.equals(takeover.ownerB().token())) {
            assertThat(scheduleMapper.markSuccess(TENANT, id, upperB, takeover.ownerB().claimedAt(), OCT, SEPT))
                    .isZero();
        }
        assertThat(row(id)).isEqualTo(ownedByB);

        assertThat(scheduleMapper.markSuccess(TENANT, id, takeover.ownerB().token(),
                takeover.ownerB().claimedAt(), OCT, SEPT)).isEqualTo(1);
        assertCompletedSuccessfully(id, OCT, SEPT);
    }

    @Test
    void stale_takeover後の旧Aのfailure_completionは0件でBの状態を一切変えない() {
        Takeover takeover = staleTakeover();
        long id = takeover.id();
        Map<String, Object> ownedByB = row(id);

        assertThat(scheduleMapper.markFailure(TENANT, id, takeover.ownerA().token(),
                takeover.ownerA().claimedAt(), SEPT.plusHours(1), SEPT, ERROR_CODE, ERROR_MESSAGE)).isZero();
        assertThat(row(id)).isEqualTo(ownedByB);

        LocalDateTime retryAt = takeover.ownerB().claimedAt().plusMinutes(5);
        assertThat(scheduleMapper.markFailure(TENANT, id, takeover.ownerB().token(),
                takeover.ownerB().claimedAt(), retryAt, SEPT, ERROR_CODE, ERROR_MESSAGE)).isEqualTo(1);
        assertThat(dateTime(id, "retry_scheduled_at")).isEqualTo(retryAt);
        assertThat(dateTime(id, "processing_logical_run_at")).as("failureはlogical runを保持").isEqualTo(SEPT);
        assertThat(dateTime(id, "next_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(id, "processing_claimed_at")).isNull();
        assertThat(token(id)).isNull();
        assertThat(intColumn(id, "failure_count")).isEqualTo(1);
        assertThat(stringColumn(id, "last_error_code")).isEqualTo(ERROR_CODE);
        Map<String, Object> failed = row(id);

        // 失敗記録後は旧A・Bいずれのtokenでもcompletionできない。
        assertThat(scheduleMapper.markSuccess(TENANT, id, takeover.ownerA().token(),
                takeover.ownerA().claimedAt(), OCT, SEPT)).isZero();
        assertThat(scheduleMapper.markSuccess(TENANT, id, takeover.ownerB().token(),
                takeover.ownerB().claimedAt(), OCT, SEPT)).isZero();
        assertThat(row(id)).isEqualTo(failed);
    }

    @Test
    void 長時間実行中のAをBが引き継いでも旧Aのcompletionは0件でBの完了状態を上書きしない() throws Exception {
        long id = insertSchedule(TENANT, SEPT);
        Harness workerA = new Harness(SEPT.plusSeconds(30));
        Harness workerB = new Harness(SEPT.plusMinutes(31));
        CountDownLatch aRunning = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicReference<String> tokenA = new AtomicReference<>();
        AtomicReference<String> tokenB = new AtomicReference<>();
        when(workerA.snapshotService.generate(any())).thenAnswer(invocation -> {
            tokenA.set(token(id));
            aRunning.countDown();
            awaitLatch(releaseA);
            // Aの生成はlease（30分）を超えて戻ってくる。
            workerA.clock.set(SEPT.plusMinutes(45));
            return succeeded(505L);
        });
        when(workerB.snapshotService.generate(any())).thenAnswer(invocation -> {
            tokenB.set(token(id));
            return succeeded(505L);
        });
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Map<String, Object> completedByB;
        try {
            Future<?> runA = pool.submit(workerA.scheduler::dispatchDue);
            assertThat(aRunning.await(10, TimeUnit.SECONDS)).as("Aの生成開始").isTrue();

            workerB.scheduler.dispatchDue();
            assertCompletedSuccessfully(id, OCT, SEPT);
            completedByB = row(id);

            releaseA.countDown();
            runA.get(30, TimeUnit.SECONDS);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
            detachSchedulerLog(logs);
        }

        assertThat(row(id)).as("旧Aのsuccess completionはBの完了状態を上書きしない").isEqualTo(completedByB);
        assertThat(tokenA.get()).isNotNull();
        assertThat(tokenB.get()).isNotNull().isNotEqualTo(tokenA.get());
        // fenceは状態の上書きを防ぐが、旧Aの配布要求そのものは止めない。重複はrun_key/delivery dedupe境界に委ねる
        // （lease延長・副作用前fenceはOwner判断待ち。scheduler-claim-fence-plan.md §11）。
        verify(workerA.deliveryService, times(1)).deliverScheduled(eq(505L), any());
        verify(workerB.deliveryService, times(1)).deliverScheduled(eq(505L), any());
        ArgumentCaptor<ReportGenerationCommand> commandA = ArgumentCaptor.forClass(ReportGenerationCommand.class);
        ArgumentCaptor<ReportGenerationCommand> commandB = ArgumentCaptor.forClass(ReportGenerationCommand.class);
        verify(workerA.snapshotService).generate(commandA.capture());
        verify(workerB.snapshotService).generate(commandB.capture());
        assertThat(commandB.getValue().period()).isEqualTo(commandA.getValue().period());
        assertThat(commandB.getValue().scheduleId()).isEqualTo(commandA.getValue().scheduleId());
        assertThat(commandB.getValue().templateVersionId()).isEqualTo(commandA.getValue().templateVersionId());
        assertThat(commandB.getValue().cutoffKind()).isEqualTo(commandA.getValue().cutoffKind());
        List<String> messages = messages(logs);
        assertThat(messages).anyMatch(message -> message.contains("期限切れprocessing leaseを引き継ぎました"));
        assertThat(messages).anyMatch(message -> message.contains("success completionでclaim所有権を失いました"));
        assertThat(messages).noneMatch(message -> message.contains(tokenA.get()) || message.contains(tokenB.get()));
    }

    // =========================================================================
    // §7-3 claim同時競合とtenant境界
    // =========================================================================

    @Test
    void 同一snapshotの同時claimはInnoDB行lock待ちの後で一件だけが勝つ() throws Exception {
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);
        ReportSchedule snapshot = dueSnapshot(TENANT, id, claimedAt);
        String holderToken = UUID.randomUUID().toString();
        TransactionTemplate holderTransaction = new TransactionTemplate(transactionManager);
        holderTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch holderUpdated = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong(-1L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // holder: 同じsnapshotでclaim UPDATEを実行し、行lockを保持したままcommitを待つ。
            Future<Integer> holder = pool.submit(() -> holderTransaction.execute(status -> {
                // transactionに束縛された接続（MyBatisのUPDATEと同じ接続）のIDを、lock保持者として記録する。
                Long connectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
                holderConnectionId.set(connectionId == null ? -1L : connectionId);
                int changed = scheduleMapper.claimDue(TENANT, id, snapshot.getNextRunAt(),
                        snapshot.getRetryScheduledAt(), snapshot.getProcessingClaimedAt(),
                        snapshot.getProcessingLogicalRunAt(), snapshot.getProcessingClaimToken(),
                        snapshot.getLastRunAt(), snapshot.getFailureCount(), SEPT, holderToken,
                        claimedAt, claimedAt.minusMinutes(30));
                holderUpdated.countDown();
                awaitLatch(releaseHolder);
                return changed;
            }));
            assertThat(holderUpdated.await(10, TimeUnit.SECONDS)).as("holderのclaim UPDATE").isTrue();
            long holderConnection = holderConnectionId.get();
            assertThat(holderConnection).as("holder接続ID").isPositive();
            // holderのclaim UPDATEは主キー一点（WHERE tenant_id=? AND id=?）なので、PRIMARYの排他record lockは対象行の1件だけ。
            InnoDbRecordLockWaits.PointLock holderLock = InnoDbRecordLockWaits.requireSinglePointLock(jdbcTemplate,
                    holderConnection, "m_report_schedule", "PRIMARY", InnoDbRecordLockWaits.primaryKey(id),
                    "holderのclaim UPDATE");
            assertThat(claimRowLockWaiters(holderConnection)).as("contender投入前にlock待ちは無い").isEmpty();

            // contender: 実claim service（REQUIRES_NEW）で同じsnapshotをclaimし、holderの行lockで待つ。
            Future<ReportScheduleClaimService.Claim> contender = pool.submit(() -> claim(snapshot, SEPT, claimedAt));
            long contenderConnection = awaitClaimRowLockWait(id, holderLock, contender);
            assertThat(contenderConnection).as("待っているのはholderとは別の接続").isNotEqualTo(holderConnection);
            assertThat(contender.isDone()).as("holderのcommit前にcontenderは完了しない").isFalse();
            releaseHolder.countDown();

            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(contender.get(10, TimeUnit.SECONDS)).as("lock解放後に再評価されたCASは0件").isNull();
            assertThat(claimRowLockWaiters(holderConnection)).as("holder commit後にlock待ちは残らない").isEmpty();
            assertThat(token(id)).isEqualTo(holderToken);
            assertThat(dateTime(id, "processing_claimed_at")).isEqualTo(claimedAt);
        } finally {
            releaseHolder.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 同一snapshotから8並行でclaimしても一件だけがtokenを得る() throws Exception {
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);
        ReportSchedule snapshot = dueSnapshot(TENANT, id, claimedAt);
        int workers = 8;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<ReportScheduleClaimService.Claim>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return claim(snapshot, SEPT, claimedAt);
                }));
            }
            List<ReportScheduleClaimService.Claim> winners = new ArrayList<>();
            for (Future<ReportScheduleClaimService.Claim> future : futures) {
                ReportScheduleClaimService.Claim result = future.get(30, TimeUnit.SECONDS);
                if (result != null) {
                    winners.add(result);
                }
            }
            assertThat(winners).hasSize(1);
            assertThat(token(id)).isEqualTo(winners.get(0).token());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 他tenantの行はtenant境界を越えてclaimもcompletionも更新もできない() {
        long ownId = insertSchedule(TENANT, SEPT);
        long otherId = insertSchedule(OTHER_TENANT, SEPT);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);

        assertThat(selectDue(TENANT, claimedAt, 50)).extracting(ReportSchedule::getId)
                .contains(ownId).doesNotContain(otherId);
        Map<String, Object> otherBefore = row(otherId);
        ReportSchedule forged = dueSnapshot(OTHER_TENANT, otherId, claimedAt);
        forged.setTenantId(TENANT);
        assertThat(claim(forged, SEPT, claimedAt)).as("他tenant行をdefault tenantとしてclaim").isNull();
        assertThat(row(otherId)).isEqualTo(otherBefore);

        ReportScheduleClaimService.Claim otherOwner = claim(dueSnapshot(OTHER_TENANT, otherId, claimedAt),
                SEPT, claimedAt);
        assertThat(otherOwner).isNotNull();
        Map<String, Object> otherClaimed = row(otherId);
        assertThat(scheduleMapper.markSuccess(TENANT, otherId, otherOwner.token(), otherOwner.claimedAt(),
                OCT, SEPT)).isZero();
        assertThat(scheduleMapper.markFailure(TENANT, otherId, otherOwner.token(), otherOwner.claimedAt(),
                claimedAt.plusMinutes(5), SEPT, ERROR_CODE, ERROR_MESSAGE)).isZero();
        assertThat(scheduleMapper.updateEnabled(TENANT, otherId, 0, 1L)).isZero();
        assertThat(row(otherId)).isEqualTo(otherClaimed);

        assertThat(token(ownId)).as("自tenantの行は他tenantのclaimに影響されない").isNull();
        assertThat(claim(dueSnapshot(TENANT, ownId, claimedAt), SEPT, claimedAt)).isNotNull();
    }

    // =========================================================================
    // §7-4 stale due snapshot
    // =========================================================================

    @Test
    void due選択後に状態が変わった古いsnapshotではclaimできない() {
        String tokenA = "00000000-0000-4000-8000-00000000000a";
        String tokenB = "00000000-0000-4000-8000-00000000000b";
        LocalDateTime unclaimedAt = SEPT.plusMinutes(1);
        LocalDateTime staleAt = SEPT.plusMinutes(40);
        String staleLease = "UPDATE m_report_schedule SET processing_logical_run_at = '2026-09-01 00:00:00', "
                + "last_run_at = '2026-09-01 00:00:00', processing_claimed_at = '2026-09-01 00:00:30', "
                + "processing_claim_token = '" + tokenA + "' WHERE id = ?";
        String dueRetry = "UPDATE m_report_schedule SET retry_scheduled_at = '2026-09-01 00:00:10', "
                + "last_run_at = '2026-09-01 00:00:00', processing_logical_run_at = '2026-09-01 00:00:00', "
                + "failure_count = 1 WHERE id = ?";
        List<SnapshotChange> changes = List.of(
                new SnapshotChange("next_run_at 非NULL→別値", null, unclaimedAt,
                        "UPDATE m_report_schedule SET next_run_at = '2026-09-01 00:00:20' WHERE id = ?"),
                new SnapshotChange("retry_scheduled_at NULL→非NULL", null, unclaimedAt,
                        "UPDATE m_report_schedule SET retry_scheduled_at = '2026-09-01 00:00:10', "
                                + "last_run_at = '2026-09-01 00:00:00' WHERE id = ?"),
                new SnapshotChange("retry_scheduled_at 非NULL→NULL", dueRetry, unclaimedAt,
                        "UPDATE m_report_schedule SET retry_scheduled_at = NULL WHERE id = ?"),
                new SnapshotChange("processing_claimed_at NULL→非NULL（他workerがclaim）", null, unclaimedAt,
                        "UPDATE m_report_schedule SET processing_claimed_at = '2026-09-01 00:00:50', "
                                + "processing_claim_token = '" + tokenB + "', "
                                + "processing_logical_run_at = '2026-09-01 00:00:00' WHERE id = ?"),
                new SnapshotChange("processing_claim_token NULL→非NULL（claimed_at NULLのまま孤児化）", null,
                        unclaimedAt, "UPDATE m_report_schedule SET processing_claim_token = '" + tokenB
                        + "' WHERE id = ?"),
                new SnapshotChange("processing_claim_token 非NULL→別token（stale leaseの再claim）", staleLease, staleAt,
                        "UPDATE m_report_schedule SET processing_claim_token = '" + tokenB + "' WHERE id = ?"),
                new SnapshotChange("processing_claimed_at 非NULL→NULL（旧ownerのsuccess完了）", staleLease, staleAt,
                        "UPDATE m_report_schedule SET next_run_at = '2026-10-01 00:00:00', "
                                + "processing_claimed_at = NULL, processing_claim_token = NULL, "
                                + "processing_logical_run_at = NULL WHERE id = ?"),
                new SnapshotChange("processing_claimed_at 非NULL→NULL（旧ownerのfailure完了）", staleLease, staleAt,
                        "UPDATE m_report_schedule SET retry_scheduled_at = '2026-09-01 00:35:00', "
                                + "processing_claimed_at = NULL, processing_claim_token = NULL, failure_count = 1 "
                                + "WHERE id = ?"),
                new SnapshotChange("processing_logical_run_at 変更", staleLease, staleAt,
                        "UPDATE m_report_schedule SET processing_logical_run_at = '2026-08-01 00:00:00' WHERE id = ?"),
                new SnapshotChange("last_run_at NULL→非NULL", null, unclaimedAt,
                        "UPDATE m_report_schedule SET last_run_at = '2026-08-01 00:00:00' WHERE id = ?"),
                new SnapshotChange("failure_count 変更", null, unclaimedAt,
                        "UPDATE m_report_schedule SET failure_count = 3 WHERE id = ?"),
                new SnapshotChange("enabled 1→0", null, unclaimedAt,
                        "UPDATE m_report_schedule SET enabled = 0 WHERE id = ?"),
                new SnapshotChange("deleted_flag 0→1", null, unclaimedAt,
                        "UPDATE m_report_schedule SET deleted_flag = 1 WHERE id = ?"));

        for (SnapshotChange change : changes) {
            long id = insertSchedule(TENANT, SEPT);
            if (change.setupSql() != null) {
                jdbcTemplate.update(change.setupSql(), id);
            }
            ReportSchedule snapshot = dueSnapshot(TENANT, id, change.observedAt());
            jdbcTemplate.update(change.changeSql(), id);
            Map<String, Object> changed = row(id);

            assertThat(claim(snapshot, SEPT, change.observedAt())).as(change.name()).isNull();
            assertThat(row(id)).as(change.name() + " の後の行はclaimで変更されない").isEqualTo(changed);
        }
    }

    // =========================================================================
    // §7-5 正常・retry・月跨ぎ・idempotency入力
    // =========================================================================

    @Test
    void 初回successは次回cronへ進みlease_token_logical_runをclearする() {
        long id = insertSchedule(TENANT, SEPT);
        Harness harness = new Harness(SEPT.plusSeconds(30).plusNanos(400_000_000L));
        AtomicReference<String> tokenDuringRun = new AtomicReference<>();
        AtomicReference<LocalDateTime> claimedAtDuringRun = new AtomicReference<>();
        when(harness.snapshotService.generate(any())).thenAnswer(invocation -> {
            tokenDuringRun.set(token(id));
            claimedAtDuringRun.set(dateTime(id, "processing_claimed_at"));
            return succeeded(101L);
        });

        harness.scheduler.dispatchDue();

        assertThat(tokenDuringRun.get()).hasSize(36);
        assertThat(claimedAtDuringRun.get()).as("claim時刻は秒精度").isEqualTo(SEPT.plusSeconds(30));
        assertCompletedSuccessfully(id, OCT, SEPT);
        ArgumentCaptor<ReportGenerationCommand> command = ArgumentCaptor.forClass(ReportGenerationCommand.class);
        verify(harness.snapshotService).generate(command.capture());
        assertThat(command.getValue().period()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(command.getValue().scheduleId()).isEqualTo(id);
        verify(harness.deliveryService).deliverScheduled(eq(101L), any());
    }

    @Test
    void 失敗は失敗検知時刻基準でretryし月跨ぎretryは同一logicalRunAtと新tokenで完了する() {
        long id = insertSchedule(TENANT, SEPT);
        Harness harness = new Harness(LocalDateTime.of(2026, 9, 30, 23, 57, 0));
        List<String> tokens = new CopyOnWriteArrayList<>();
        List<LocalDateTime> claimedAts = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger oldTokenCompletions = new AtomicInteger(-1);
        when(harness.snapshotService.generate(any())).thenAnswer(invocation -> {
            tokens.add(token(id));
            claimedAts.add(dateTime(id, "processing_claimed_at"));
            if (attempts.incrementAndGet() == 1) {
                harness.clock.advance(Duration.ofSeconds(70).plusMillis(900));
                throw new IllegalStateException("source unavailable");
            }
            // retry実行中: 失敗した前回claimのtokenではsuccess/failureどちらも完了できない。
            oldTokenCompletions.set(scheduleMapper.markSuccess(TENANT, id, tokens.get(0), claimedAts.get(0), OCT, SEPT)
                    + scheduleMapper.markFailure(TENANT, id, tokens.get(0), claimedAts.get(0), OCT, SEPT,
                    ERROR_CODE, ERROR_MESSAGE));
            return succeeded(202L);
        });

        harness.scheduler.dispatchDue();

        // 失敗検知 23:58:10（23:58:10.900を秒へ切捨て）+ 退避5分 = 翌月 00:03:10。
        LocalDateTime retryAt = LocalDateTime.of(2026, 10, 1, 0, 3, 10);
        assertThat(dateTime(id, "retry_scheduled_at")).isEqualTo(retryAt);
        assertThat(dateTime(id, "processing_logical_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(id, "last_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(id, "next_run_at")).isEqualTo(SEPT);
        assertThat(dateTime(id, "processing_claimed_at")).isNull();
        assertThat(token(id)).isNull();
        assertThat(intColumn(id, "failure_count")).isEqualTo(1);
        assertThat(stringColumn(id, "last_error_code")).isEqualTo(ERROR_CODE);

        // retry時刻前のpollでは再実行しない。
        harness.clock.set(LocalDateTime.of(2026, 10, 1, 0, 2, 0));
        harness.scheduler.dispatchDue();
        assertThat(attempts.get()).isEqualTo(1);

        harness.clock.set(LocalDateTime.of(2026, 10, 1, 0, 4, 0));
        harness.scheduler.dispatchDue();

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(tokens).hasSize(2).doesNotHaveDuplicates();
        assertThat(claimedAts).containsExactly(LocalDateTime.of(2026, 9, 30, 23, 57, 0),
                LocalDateTime.of(2026, 10, 1, 0, 4, 0));
        assertThat(oldTokenCompletions.get()).as("旧tokenのsuccess/failure completion件数").isZero();
        ArgumentCaptor<ReportGenerationCommand> commands = ArgumentCaptor.forClass(ReportGenerationCommand.class);
        verify(harness.snapshotService, times(2)).generate(commands.capture());
        // 月を跨いだretryも同一論理実行（2026-09-01 → 対象2026-08）を継続し、新しい論理taskを作らない。
        assertThat(commands.getAllValues()).extracting(ReportGenerationCommand::period)
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 8));
        assertThat(commands.getAllValues().get(1).scheduleId()).isEqualTo(commands.getAllValues().get(0).scheduleId());
        assertThat(commands.getAllValues().get(1).templateVersionId())
                .isEqualTo(commands.getAllValues().get(0).templateVersionId());
        assertCompletedSuccessfully(id, OCT, SEPT);
        verify(harness.deliveryService, times(1)).deliverScheduled(eq(202L), any());
    }

    // =========================================================================
    // §7-6 長時間batch
    // =========================================================================

    @Test
    void 長時間batchでもclaim時刻はscheduleごとの秒精度で保存され直後pollでstale扱いされない() {
        int count = 50;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(insertSchedule(TENANT, SEPT));
        }
        Harness harness = new Harness(SEPT.plusNanos(400_000_000L));
        Map<Long, LocalDateTime> storedClaimedAt = new ConcurrentHashMap<>();
        Map<Long, LocalDateTime> expectedClaimedAt = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<Long>> dueDuringLastRun = new AtomicReference<>();
        AtomicReference<LocalDateTime> lastFailureAt = new AtomicReference<>();
        when(harness.snapshotService.generate(any())).thenAnswer(invocation -> {
            ReportGenerationCommand command = invocation.getArgument(0);
            long scheduleId = command.scheduleId();
            LocalDateTime now = harness.clock.localNow().truncatedTo(ChronoUnit.SECONDS);
            storedClaimedAt.put(scheduleId, dateTime(scheduleId, "processing_claimed_at"));
            expectedClaimedAt.put(scheduleId, now);
            boolean last = calls.incrementAndGet() == count;
            if (last) {
                dueDuringLastRun.set(selectDue(TENANT, now, 50).stream().map(ReportSchedule::getId).toList());
            }
            // 1件ごとに1分0.7秒かかり、50件目のclaimはpoll開始から約49分後になる。
            harness.clock.advance(Duration.ofMinutes(1).plusMillis(700));
            if (last) {
                lastFailureAt.set(harness.clock.localNow().truncatedTo(ChronoUnit.SECONDS));
                throw new IllegalStateException("last schedule failed");
            }
            return succeeded(300L + calls.get());
        });

        harness.scheduler.dispatchDue();

        assertThat(calls.get()).isEqualTo(count);
        for (Long id : ids) {
            assertThat(storedClaimedAt.get(id)).as("id=" + id + "のclaim時刻").isEqualTo(expectedClaimedAt.get(id));
            assertThat(storedClaimedAt.get(id).getNano()).isZero();
        }
        long lastId = ids.get(count - 1);
        // poll開始時刻（00:00:00）を流用していれば最後のclaimは49分前の値となり、直後pollでstaleになる。
        assertThat(storedClaimedAt.get(lastId)).isAfter(SEPT.plusMinutes(30));
        assertThat(dueDuringLastRun.get()).as("最後のschedule実行中のdue/stale候補").isEmpty();
        assertThat(dateTime(lastId, "retry_scheduled_at")).as("retryは実失敗時刻基準")
                .isEqualTo(lastFailureAt.get().plusMinutes(5));
        for (Long id : ids.subList(0, count - 1)) {
            assertThat(dateTime(id, "next_run_at")).isEqualTo(OCT);
        }
    }

    // =========================================================================
    // P1-1 孤児token（claimed_at NULL / token 非NULL）と新旧混在、P2-5 LIMIT枠
    // =========================================================================

    @Test
    void 旧版success_completionが残した孤児tokenは通常claimで回収され旧ownerは完了できない() {
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime aClaimedAt = SEPT.plusMinutes(1);
        ReportScheduleClaimService.Claim newOwner = claim(dueSnapshot(TENANT, id, aClaimedAt), SEPT, aClaimedAt);
        assertThat(newOwner).isNotNull();

        // V185以前の旧nodeがtokenを知らずにstale leaseを引き継ぎ、processing_claimed_atだけを書き換えて完了する（混在稼働）。
        LocalDateTime legacyClaimedAt = aClaimedAt.plusMinutes(31);
        assertThat(jdbcTemplate.update(LEGACY_CLAIM_SQL, SEPT, legacyClaimedAt, SEPT, TENANT, id,
                legacyClaimedAt.minusMinutes(30), legacyClaimedAt, legacyClaimedAt, SEPT)).isEqualTo(1);
        assertThat(token(id)).as("旧版claimはtokenを更新しない").isEqualTo(newOwner.token());
        // tokenが残っていてもclaimedAtが変わったため、新ownerは旧nodeの引き継ぎ後に完了できない。
        assertThat(scheduleMapper.markSuccess(TENANT, id, newOwner.token(), newOwner.claimedAt(), OCT, SEPT)).isZero();
        assertThat(jdbcTemplate.update(LEGACY_MARK_SUCCESS_SQL, OCT, SEPT, TENANT, id)).isEqualTo(1);
        assertThat(dateTime(id, "processing_claimed_at")).isNull();
        assertThat(token(id)).as("孤児token").isEqualTo(newOwner.token());

        // 次回due（10/1）で孤児tokenの行はdue候補に入り、期待token一致のCASで新tokenへ置換される。
        LocalDateTime nextDue = OCT.plusMinutes(1);
        ReportSchedule orphan = dueSnapshot(TENANT, id, nextDue);
        assertThat(orphan.getProcessingClaimedAt()).isNull();
        assertThat(orphan.getProcessingClaimToken()).isEqualTo(newOwner.token());
        ReportScheduleClaimService.Claim recovered = claim(orphan, OCT, nextDue);
        assertThat(recovered).isNotNull();
        assertThat(recovered.token()).isNotEqualTo(newOwner.token());
        assertThat(token(id)).isEqualTo(recovered.token());
        assertThat(scheduleMapper.markSuccess(TENANT, id, newOwner.token(), newOwner.claimedAt(), NOV, OCT)).isZero();
        assertThat(scheduleMapper.markSuccess(TENANT, id, recovered.token(), recovered.claimedAt(), NOV, OCT))
                .isEqualTo(1);
        assertCompletedSuccessfully(id, NOV, OCT);
    }

    @Test
    void 旧版failure_completionが残した孤児token行もretry時刻にscheduler経由で回収される() {
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime aClaimedAt = SEPT.plusMinutes(1);
        ReportScheduleClaimService.Claim newOwner = claim(dueSnapshot(TENANT, id, aClaimedAt), SEPT, aClaimedAt);
        assertThat(newOwner).isNotNull();
        LocalDateTime legacyClaimedAt = aClaimedAt.plusMinutes(31);
        assertThat(jdbcTemplate.update(LEGACY_CLAIM_SQL, SEPT, legacyClaimedAt, SEPT, TENANT, id,
                legacyClaimedAt.minusMinutes(30), legacyClaimedAt, legacyClaimedAt, SEPT)).isEqualTo(1);
        LocalDateTime legacyRetryAt = legacyClaimedAt.plusMinutes(5);
        assertThat(jdbcTemplate.update(LEGACY_MARK_FAILURE_SQL, legacyRetryAt, ERROR_CODE, ERROR_MESSAGE, SEPT,
                TENANT, id)).isEqualTo(1);
        assertThat(token(id)).isEqualTo(newOwner.token());
        assertThat(dateTime(id, "processing_claimed_at")).isNull();
        assertThat(dateTime(id, "retry_scheduled_at")).isEqualTo(legacyRetryAt);

        Harness harness = new Harness(legacyRetryAt.plusMinutes(1));
        AtomicReference<String> tokenDuringRun = new AtomicReference<>();
        when(harness.snapshotService.generate(any())).thenAnswer(invocation -> {
            tokenDuringRun.set(token(id));
            return succeeded(404L);
        });
        ListAppender<ILoggingEvent> logs = attachSchedulerLog();
        try {
            harness.scheduler.dispatchDue();
        } finally {
            detachSchedulerLog(logs);
        }

        assertThat(tokenDuringRun.get()).isNotNull().isNotEqualTo(newOwner.token());
        assertCompletedSuccessfully(id, OCT, SEPT);
        List<String> messages = messages(logs);
        assertThat(messages).anyMatch(message -> message.contains("孤児claim token"));
        assertThat(messages).noneMatch(message -> message.contains(newOwner.token())
                || message.contains(tokenDuringRun.get()));
    }

    @Test
    void 実行中のretry_leaseはLIMIT枠を占有せずstale後だけ再選択される() {
        long inFlightRetry = insertSchedule(TENANT, SEPT);
        jdbcTemplate.update("UPDATE m_report_schedule SET retry_scheduled_at = '2026-09-01 00:05:00', "
                + "last_run_at = '2026-09-01 00:00:00', processing_logical_run_at = '2026-09-01 00:00:00', "
                + "failure_count = 1 WHERE id = ?", inFlightRetry);
        LocalDateTime retryClaimAt = LocalDateTime.of(2026, 9, 1, 0, 6);
        assertThat(claim(dueSnapshot(TENANT, inFlightRetry, retryClaimAt), SEPT, retryClaimAt)).isNotNull();
        long normalDue = insertSchedule(TENANT, LocalDateTime.of(2026, 9, 1, 0, 8));

        // 実行中retry（retry=00:05）は並び順で先頭だが未占有ではないため除外され、通常dueがLIMIT 1に入る。
        assertThat(selectDue(TENANT, LocalDateTime.of(2026, 9, 1, 0, 10), 1))
                .extracting(ReportSchedule::getId).containsExactly(normalDue);
        // lease期限後はstale候補として再び先頭に入る。
        assertThat(selectDue(TENANT, LocalDateTime.of(2026, 9, 1, 0, 40), 1))
                .extracting(ReportSchedule::getId).containsExactly(inFlightRetry);
    }

    // =========================================================================
    // §7-7 API/entity境界、setEnabled lost-update、REQUIRES_NEW境界
    // =========================================================================

    @Test
    void enable_disableは実行中leaseとtokenを保持しversionと更新者だけを進める() throws Exception {
        authenticateAdmin();
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);
        ReportScheduleClaimService.Claim owner = claim(dueSnapshot(TENANT, id, claimedAt), SEPT, claimedAt);
        assertThat(owner).isNotNull();
        jdbcTemplate.update("UPDATE m_report_schedule SET updated_by = 99 WHERE id = ?", id);
        Map<String, Object> leaseBefore = leaseColumns(id);
        int versionBefore = intColumn(id, "version");

        ReportSchedule disabled = scheduleService.setEnabled(id, false);

        assertThat(disabled.getEnabled()).isZero();
        assertThat(disabled.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(leaseColumns(id)).as("disableはleaseを書き戻さない").isEqualTo(leaseBefore);
        assertThat(intColumn(id, "enabled")).isZero();
        assertThat(intColumn(id, "updated_by")).isEqualTo(1);
        assertThat(objectMapper.writeValueAsString(disabled)).doesNotContain(owner.token())
                .doesNotContain("processingClaimToken");

        ReportSchedule enabled = scheduleService.setEnabled(id, true);
        assertThat(enabled.getEnabled()).isEqualTo(1);
        assertThat(enabled.getVersion()).isEqualTo(versionBefore + 2);
        assertThat(leaseColumns(id)).as("enableはleaseを書き戻さない").isEqualTo(leaseBefore);
        assertThat(objectMapper.writeValueAsString(scheduleService.list())).doesNotContain(owner.token())
                .doesNotContain("processingClaimToken");
        // enable/disableを挟んでも現在ownerはそのまま完了できる。
        assertThat(scheduleMapper.markSuccess(TENANT, id, owner.token(), owner.claimedAt(), OCT, SEPT)).isEqualTo(1);

        long deletedId = insertSchedule(TENANT, SEPT);
        jdbcTemplate.update("UPDATE m_report_schedule SET deleted_flag = 1 WHERE id = ?", deletedId);
        assertThatThrownBy(() -> scheduleService.setEnabled(deletedId, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.managementReport.scheduleNotFound");
        assertThat(intColumn(deletedId, "enabled")).as("論理削除済み行は更新しない").isEqualTo(1);
    }

    @Test
    void entity経由のinsertとupdateByIdはlease_token_retry列を書き込まない() {
        ReportSchedule forged = new ReportSchedule();
        forged.setTenantId(TENANT);
        forged.setTemplateVersionId(3L);
        forged.setCronExpression(CRON);
        forged.setTimezoneId("Asia/Tokyo");
        forged.setEnabled(1);
        forged.setLockKey("nf10-fence-" + UUID.randomUUID());
        forged.setNextRunAt(SEPT);
        forged.setScopeOwnerType("COMPANY");
        forged.setOrganizationScopeJson(SCOPE_JSON);
        forged.setScopePolicyVersion("scope-policy-approved-1");
        forged.setScopeHash(sha256(SCOPE_JSON));
        forged.setRetryScheduledAt(SEPT);
        forged.setProcessingLogicalRunAt(SEPT);
        forged.setProcessingClaimedAt(SEPT.plusMinutes(1));
        forged.setProcessingClaimToken(UUID.randomUUID().toString());
        forged.setFailureCount(9);
        assertThat(scheduleMapper.insert(forged)).isEqualTo(1);
        long id = forged.getId();
        Map<String, Object> inserted = leaseColumns(id);
        assertThat(inserted.get("retry_scheduled_at")).isNull();
        assertThat(inserted.get("processing_logical_run_at")).isNull();
        assertThat(inserted.get("processing_claimed_at")).isNull();
        assertThat(inserted.get("processing_claim_token")).isNull();
        assertThat(intColumn(id, "failure_count")).isZero();
        assertThat(dateTime(id, "next_run_at")).as("作成時のnext_run_atは保存する").isEqualTo(SEPT);

        ReportSchedule staleRead = scheduleMapper.selectById(id);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);
        ReportScheduleClaimService.Claim owner = claim(dueSnapshot(TENANT, id, claimedAt), SEPT, claimedAt);
        assertThat(owner).isNotNull();
        Map<String, Object> claimed = leaseColumns(id);

        // claim前に読んだentityを書き戻しても、lease/next/last/retry/failure列は古い値へ戻らない。
        staleRead.setCronExpression("0 0 1 1 * *");
        staleRead.setNextRunAt(OCT);
        assertThat(scheduleMapper.updateById(staleRead)).isEqualTo(1);
        assertThat(leaseColumns(id)).isEqualTo(claimed);
        assertThat(stringColumn(id, "cron_expression")).isEqualTo("0 0 1 1 * *");
        assertThat(scheduleMapper.markSuccess(TENANT, id, owner.token(), owner.claimedAt(), OCT, SEPT)).isEqualTo(1);
    }

    @Test
    void claim_serviceはREQUIRES_NEWのSpring_proxyで外側transactionのrollbackから独立してcommitする() {
        assertThat(AopUtils.isAopProxy(claimService)).as("REQUIRES_NEWはSpring proxyでのみ成立する").isTrue();
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);
        ReportSchedule snapshot = dueSnapshot(TENANT, id, claimedAt);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        ReportScheduleClaimService.Claim committed = outer.execute(status -> {
            ReportScheduleClaimService.Claim claim = claim(snapshot, SEPT, claimedAt);
            status.setRollbackOnly();
            return claim;
        });

        assertThat(committed).isNotNull();
        assertThat(token(id)).as("外側transactionのrollback後もclaimはcommit済み").isEqualTo(committed.token());
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private record Takeover(long id, ReportScheduleClaimService.Claim ownerA, ReportScheduleClaimService.Claim ownerB) {
    }

    private record SnapshotChange(String name, String setupSql, LocalDateTime observedAt, String changeSql) {
    }

    /** Aがclaim→lease期限超過→Bが同じlogical runをstale takeoverした状態を作る。 */
    private Takeover staleTakeover() {
        long id = insertSchedule(TENANT, SEPT);
        LocalDateTime aClaimedAt = SEPT.plusMinutes(1);
        ReportScheduleClaimService.Claim ownerA = claim(dueSnapshot(TENANT, id, aClaimedAt), SEPT, aClaimedAt);
        assertThat(ownerA).isNotNull();

        // lease期限前はdue候補にならず、読取済みsnapshotでの引き継ぎも0件。
        LocalDateTime beforeExpiry = aClaimedAt.plusMinutes(29);
        assertThat(selectDue(TENANT, beforeExpiry, 50)).extracting(ReportSchedule::getId).doesNotContain(id);
        assertThat(claim(scheduleMapper.selectById(id), SEPT, beforeExpiry)).isNull();

        LocalDateTime bClaimedAt = aClaimedAt.plusMinutes(31);
        ReportSchedule stale = dueSnapshot(TENANT, id, bClaimedAt);
        assertThat(stale.getProcessingClaimToken()).isEqualTo(ownerA.token());
        assertThat(stale.getProcessingClaimedAt()).isEqualTo(ownerA.claimedAt());
        ReportScheduleClaimService.Claim ownerB = claim(stale, SEPT, bClaimedAt);
        assertThat(ownerB).isNotNull();
        assertThat(ownerB.token()).isNotEqualTo(ownerA.token());
        assertThat(token(id)).isEqualTo(ownerB.token());
        assertThat(dateTime(id, "processing_logical_run_at")).isEqualTo(SEPT);
        return new Takeover(id, ownerA, ownerB);
    }

    /** dispatchDue()用のscheduler。mapper/claim service/tenant runnerは実bean、副作用serviceはmock。 */
    private final class Harness {
        final ReportSnapshotService snapshotService = mock(ReportSnapshotService.class);
        final ReportDeliveryService deliveryService = mock(ReportDeliveryService.class);
        final MonthlyClosingService closingService = mock(MonthlyClosingService.class);
        final ReportRecipientPreviewService previewService = mock(ReportRecipientPreviewService.class);
        final MutableClock clock;
        final ManagementReportScheduler scheduler;

        Harness(LocalDateTime startAt) {
            clock = new MutableClock(startAt.atZone(TOKYO).toInstant());
            when(previewService.previewForScope(anyLong(), any(), any())).thenReturn(
                    new ReportRecipientPreviewResult("preview-mysql", "APPROVED_SCOPE_CHECKED", null, List.of()));
            scheduler = new ManagementReportScheduler(scheduleMapper, claimService, clock, snapshotService,
                    deliveryService, closingService, timezoneResolver, tenantAwareBatchRunner, previewService,
                    objectMapper);
        }
    }

    private void deleteFixtures() {
        jdbcTemplate.update("DELETE FROM m_report_schedule WHERE tenant_id IN (?, ?)", TENANT, OTHER_TENANT);
    }

    private long insertSchedule(String tenantId, LocalDateTime nextRunAt) {
        String lockKey = "nf10-fence-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO m_report_schedule (tenant_id, template_version_id, cron_expression, "
                        + "timezone_id, enabled, lock_key, next_run_at, scope_owner_type, organization_scope_json, "
                        + "scope_policy_version, scope_hash, failure_count, created_by, updated_by) "
                        + "VALUES (?, 3, ?, 'Asia/Tokyo', 1, ?, ?, 'COMPANY', ?, 'scope-policy-approved-1', ?, 0, 1, 1)",
                tenantId, CRON, lockKey, nextRunAt, SCOPE_JSON, sha256(SCOPE_JSON));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM m_report_schedule WHERE tenant_id = ? AND lock_key = ?",
                Long.class, tenantId, lockKey);
        assertThat(id).isNotNull();
        return id;
    }

    private List<ReportSchedule> selectDue(String tenantId, LocalDateTime now, int limit) {
        return scheduleMapper.selectDue(tenantId, now, now.minusMinutes(30), limit);
    }

    private ReportSchedule dueSnapshot(String tenantId, long id, LocalDateTime now) {
        return selectDue(tenantId, now, 50).stream()
                .filter(row -> row.getId() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("id=" + id + "がdue候補にありません: now=" + now));
    }

    private ReportScheduleClaimService.Claim claim(ReportSchedule snapshot, LocalDateTime logicalRunAt,
                                                   LocalDateTime claimedAt) {
        return claimService.claim(snapshot, logicalRunAt, claimedAt, claimedAt.minusMinutes(30));
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT " + ROW_COLUMNS + " FROM m_report_schedule WHERE id = ?", id);
    }

    private Map<String, Object> leaseColumns(long id) {
        return jdbcTemplate.queryForMap("SELECT " + LEASE_COLUMNS + " FROM m_report_schedule WHERE id = ?", id);
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
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1", "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    /**
     * contenderのclaim UPDATEが、holder接続の保持する対象行のPRIMARY record lockで待っていることを観測し、
     * 待っている接続IDを返す。観測は {@link #CLAIM_ROW_LOCK_WAIT_SQL}（performance_schema、cache無し）で行い、
     * 対象行は {@link #anchoredClaimRowLockWaits} でLOCK_DATAの値に依存せずに特定する。
     */
    private long awaitClaimRowLockWait(long id, InnoDbRecordLockWaits.PointLock holderLock, Future<?> contender)
            throws Exception {
        long holderConnectionId = holderLock.connectionId();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (contender.isDone()) {
                contender.get();
                throw new AssertionError("contenderがholderの行lock待ちに入る前に完了しました"
                        + lockDiagnostics(id, holderConnectionId, contender));
            }
            List<ClaimRowLockWait> waiters = anchoredClaimRowLockWaits(holderLock);
            if (waiters.size() > 1) {
                throw new AssertionError("holderの行lockを待つclaim UPDATEが複数あります: " + waiters
                        + lockDiagnostics(id, holderConnectionId, contender));
            }
            if (waiters.size() == 1) {
                return waiters.get(0).requesterConnectionId();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("contenderのclaim UPDATEがholderの行lockで待つことを10秒以内に観測できませんでした"
                + lockDiagnostics(id, holderConnectionId, contender));
    }

    /**
     * holderの対象行lockを待つclaim UPDATEの待機辺だけを返す。holderが今もm_report_scheduleのPRIMARY排他record lockを
     * ちょうど1件（主キー一点のclaim UPDATEで得た対象行のlock）だけ保持し、待機辺のblocking lockがそのlockで、待機lockが
     * 排他record lockである辺に限る。LOCK_DATAが見えている側は対象schedule IDと一致しなければならず、NULLは不可視として扱う。
     */
    private List<ClaimRowLockWait> anchoredClaimRowLockWaits(InnoDbRecordLockWaits.PointLock holderLock) {
        List<InnoDbRecordLockWaits.HeldLock> held = InnoDbRecordLockWaits.grantedRecordLocks(jdbcTemplate,
                holderLock.connectionId(), holderLock.table(), holderLock.index(),
                InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES);
        if (held.size() != 1
                || !InnoDbRecordLockWaits.keyVisibleAndMatches(held.get(0).lockData(), holderLock.expectedKey())) {
            return List.of();
        }
        String holderLockId = held.get(0).lockId();
        return claimRowLockWaiters(holderLock.connectionId()).stream()
                .filter(wait -> holderLockId.equals(wait.blockingLockId()))
                .filter(wait -> InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES.contains(wait.requestMode()))
                .filter(wait -> InnoDbRecordLockWaits.keyVisibleAndMatches(wait.requesterKey(), holderLock.expectedKey())
                        && InnoDbRecordLockWaits.keyVisibleAndMatches(wait.blockerKey(), holderLock.expectedKey()))
                .toList();
    }

    /** holderのm_report_schedule PRIMARY lockを待つclaim UPDATEの全待機辺（LOCK_DATAでは絞り込まない）。 */
    private List<ClaimRowLockWait> claimRowLockWaiters(long holderConnectionId) {
        return jdbcTemplate.query(CLAIM_ROW_LOCK_WAIT_SQL, (rs, row) -> new ClaimRowLockWait(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                holderConnectionId, holderConnectionId);
    }

    /** claim UPDATEの待機辺。鍵値はNULL（不可視）を含めて観測値のまま保持する。 */
    private record ClaimRowLockWait(long requesterConnectionId, String blockingLockId, String requestMode,
                                    String requesterKey, String blockerKey) { }

    /** 観測失敗時に、行lock・metadata lock・実行中SQLの状態を残す（SQLは先頭だけを出し、token値を含めない）。 */
    private String lockDiagnostics(long id, long holderConnectionId, Future<?> contender) {
        StringBuilder diagnostics = new StringBuilder("\n=== claim行lock診断 ===\n")
                .append("scheduleId=").append(id)
                .append(" holderConnectionId=").append(holderConnectionId)
                .append(" contenderDone=").append(contender.isDone()).append('\n');
        appendDiagnostic(diagnostics, "data_lock_waits", "SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS requesting_trx, "
                + "w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx, rt.PROCESSLIST_ID AS requesting_connection, "
                + "bt.PROCESSLIST_ID AS blocking_connection, req.OBJECT_NAME, req.INDEX_NAME, req.LOCK_TYPE, "
                + "req.LOCK_MODE, req.LOCK_DATA FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks req ON req.ENGINE = w.ENGINE "
                + "  AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.data_locks blk ON blk.ENGINE = w.ENGINE "
                + "  AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                + "LEFT JOIN performance_schema.threads rt ON rt.THREAD_ID = req.THREAD_ID "
                + "LEFT JOIN performance_schema.threads bt ON bt.THREAD_ID = blk.THREAD_ID");
        appendDiagnostic(diagnostics, "data_locks(m_report_schedule)", "SELECT t.PROCESSLIST_ID AS connection_id, "
                + "l.ENGINE_TRANSACTION_ID, l.INDEX_NAME, l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.LOCK_DATA "
                + "FROM performance_schema.data_locks l "
                + "LEFT JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID "
                + "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'm_report_schedule'");
        appendDiagnostic(diagnostics, "metadata_locks(m_report_schedule)", "SELECT t.PROCESSLIST_ID AS connection_id, "
                + "m.LOCK_TYPE, m.LOCK_DURATION, m.LOCK_STATUS FROM performance_schema.metadata_locks m "
                + "LEFT JOIN performance_schema.threads t ON t.THREAD_ID = m.OWNER_THREAD_ID "
                + "WHERE m.OBJECT_SCHEMA = DATABASE() AND m.OBJECT_NAME = 'm_report_schedule'");
        appendDiagnostic(diagnostics, "threads(m_report_schedule SQL実行中)", "SELECT PROCESSLIST_ID AS connection_id, "
                + "PROCESSLIST_COMMAND, PROCESSLIST_STATE, PROCESSLIST_TIME, LEFT(PROCESSLIST_INFO, 80) AS sql_prefix "
                + "FROM performance_schema.threads WHERE PROCESSLIST_INFO LIKE '%m_report_schedule%' "
                + "AND PROCESSLIST_ID <> CONNECTION_ID()");
        return diagnostics.toString();
    }

    private void appendDiagnostic(StringBuilder diagnostics, String label, String sql) {
        try {
            diagnostics.append(label).append(": ").append(jdbcTemplate.queryForList(sql)).append('\n');
        } catch (RuntimeException ex) {
            diagnostics.append(label).append(": 取得失敗 ").append(ex.getClass().getName()).append('\n');
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
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

    private static ReportGenerationResult succeeded(long runId) {
        ReportRun run = new ReportRun();
        run.setId(runId);
        run.setStatus("SUCCEEDED");
        return new ReportGenerationResult(run, List.of(), false);
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

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        void set(LocalDateTime localDateTime) {
            instant = localDateTime.atZone(TOKYO).toInstant();
        }

        LocalDateTime localNow() {
            return LocalDateTime.ofInstant(instant, TOKYO);
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
