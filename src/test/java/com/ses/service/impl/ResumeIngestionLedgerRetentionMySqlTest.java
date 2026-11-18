package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Document;
import com.ses.mapper.DocumentMapper;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.document.DocumentEligibilityService;
import com.ses.service.document.ResumeIngestionLedgerRetentionService;
import com.ses.service.job.DocumentRetentionJob;
import com.ses.service.storage.DocumentStorage;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.PointLock;
import com.ses.test.MySQLContainer;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履歴取込原本の purge に合わせた文書台帳の副本（RESUME_INGESTION）の保存期限の処理を、実 MySQL 8 の空 DB（V1〜LATEST）で
 * 確かめる（NF07-F2 delta review 第3回 P2-D、M-1〜M-4）。
 *
 * <ul>
 *   <li>M-1: 外側 transaction を持たない日次清掃（{@code cleanupExpiredExtractedText} → {@code TenantAwareBatchRunner}）で、
 *       副本1件の文書行を別接続が lock したまま lock 待ちが timeout しても、その1件だけが失敗（metric +1）になり、
 *       他の副本・原本の purge・BP 取込の清掃は commit される。次の実行でその副本を拾い直す。</li>
 *   <li>M-2: legal hold の変更（{@code changeLegalHold}）と両順序で直列化する。hold 先行なら副本の処理は待ち、commit 後の
 *       hold を見て何も変えない。副本の処理が先行なら hold の変更は待ち、その後の D3 は廃棄候補を作らない。</li>
 *   <li>M-3: link・ジョブが別 tenant の副本は変えず、別 tenant の実行は副本に触れない。</li>
 *   <li>M-4: 清掃 → D3（{@code DocumentRetentionJob}）の PENDING（requested_by=-1）→ 管理者の承認 → 執行で副本が DISPOSED
 *       になり実体 file が消える。再実行で申請は重複しない。保存期限は早めるだけで延ばさず、AMENDED は状態を変えず、
 *       {@code evidence_eligibility_epoch} は進まない。</li>
 * </ul>
 *
 * <p>lock 待ちは {@code data_locks.LOCK_DATA} に依存せず、保持側が主キー一点で取った単一の record lock を基準に、待機辺の
 * blocking lock がその lock であることで確かめる（{@link InnoDbRecordLockWaits}）。M-1 は lock 待ちの timeout を短くするため
 * {@code innodb_lock_wait_timeout} の GLOBAL 値を下げて接続 pool を入れ替え、終了時に元へ戻す。M-2 の副本先行だけは、
 * 副本の処理を lock 保持のまま止める箇所が無いため、試験の外側 transaction の中で {@code alignPurgedCopies} を呼ぶ
 * （副本ごとの transaction はそれに参加する。外側 transaction を持たない場合の性質は M-1・M-4 が確かめる）。
 * 日次 job は ShedLock（{@code lockAtLeastFor=PT1M}）を通るため、呼ぶ前に lock を期限切れにする（行は消さない）。
 * 各 case は清掃が実際に動いたこと（原本の purge・副本の変化）を確かめ、ShedLock の skip を成功と取り違えない。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class ResumeIngestionLedgerRetentionMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf07_resume_ledger_retention")
            .withUsername("root")
            .withPassword("ses");

    private static final String TENANT_A = "nf07-rrm-a";
    private static final String TENANT_B = "nf07-rrm-b";
    private static final Path UPLOAD_ROOT = createUploadRoot();
    private static final long WAIT_SECONDS = 30;
    private static final int SHORT_LOCK_WAIT_SECONDS = 3;
    private static final String TABLE = "t_document";
    private static final String PRIMARY = "PRIMARY";
    private static final LocalDateTime OLD = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final List<String> SCHEDULER_LOCKS =
            List.of("resumeRetentionCleanupDaily", "DocumentRetentionJob_processExpiredDocuments");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.accounting.tenants.ids", () -> TENANT_A + "," + TENANT_B);
        registry.add("app.upload.base-path", UPLOAD_ROOT::toString);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ResumeRetentionCleanupServiceImpl cleanup;
    @Autowired private ResumeIngestionLedgerRetentionService ledgerRetention;
    @Autowired private DocumentRetentionJob documentRetentionJob;
    @Autowired private DocumentEligibilityService eligibility;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentStorage documentStorage;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private MeterRegistry meterRegistry;

    /** M-1 で GLOBAL の lock 待ち timeout を変えた場合の元の値（後片付けで戻す）。 */
    private Integer originalLockWaitTimeout;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (originalLockWaitTimeout != null) {
            setGlobalLockWaitTimeout(originalLockWaitTimeout);
            originalLockWaitTimeout = null;
        }
        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            jdbc.update("DELETE r FROM t_document_disposal_request r JOIN t_document d ON d.id = r.document_id "
                    + "WHERE d.tenant_id = ?", tenant);
            jdbc.update("DELETE a FROM t_document_access_log a JOIN t_document d ON d.id = a.document_id "
                    + "WHERE d.tenant_id = ?", tenant);
            jdbc.update("DELETE FROM t_document_version WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM t_document_link WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM t_document WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM t_resume_ingestion WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE b FROM t_bp_availability_ingestion b JOIN sys_user u ON u.id = b.created_by "
                    + "WHERE u.tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", tenant);
        }
        clearSchedulerLocks();
    }

    @AfterAll
    static void removeUploadRoot() throws IOException {
        if (!Files.exists(UPLOAD_ROOT)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(UPLOAD_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ===== M-1 副本ごとの transaction と失敗の隔離 =====

    @Test
    void M1_副本1件のlock待ちがtimeoutしても他の副本と原本のpurgeとBPの清掃はcommitされ次の実行で拾い直す() throws Exception {
        long a = copyOfExpiredJob(TENANT_A, "DRAFT", null);
        long b = copyOfExpiredJob(TENANT_A, "DRAFT", null);
        long c = copyOfExpiredJob(TENANT_A, "DRAFT", null);
        long bp = bpIngestionWithText(TENANT_A);
        double failuresBefore = failures(ResumeIngestionLedgerRetentionService.STAGE_DOCUMENT);

        originalLockWaitTimeout = jdbc.queryForObject("SELECT @@GLOBAL.innodb_lock_wait_timeout", Integer.class);
        setGlobalLockWaitTimeout(SHORT_LOCK_WAIT_SECONDS);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicLong blockerConnection = new AtomicLong();
        LocalDate before = LocalDate.now();
        try {
            Future<?> blocker = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                blockerConnection.set(connectionId());
                jdbc.queryForObject("SELECT id FROM t_document WHERE id = ? FOR UPDATE", Long.class, b);
                blockerLocked.countDown();
                awaitQuietly(releaseBlocker, WAIT_SECONDS);
                status.setRollbackOnly();
            }));
            assertTrue(blockerLocked.await(WAIT_SECONDS, TimeUnit.SECONDS), "M-1: blockerが副本Bをlockできなかった");
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, blockerConnection.get(), TABLE, PRIMARY,
                    InnoDbRecordLockWaits.primaryKey(b), "M-1");

            clearSchedulerLocks();
            Future<?> run = pool.submit(() -> cleanup.cleanupExpiredExtractedText());
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor, null,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    TimeUnit.SECONDS.toMillis(WAIT_SECONDS), () -> {
                        if (run.isDone()) {
                            throw new AssertionError("M-1: 副本Bのlock待ちを観測する前に清掃が終わった: " + outcome(run));
                        }
                    }, "M-1");
            // 副本Bの lock 待ちは timeout し、清掃は例外を外へ出さずに終わる
            run.get(WAIT_SECONDS, TimeUnit.SECONDS);
            releaseBlocker.countDown();
            blocker.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseBlocker.countDown();
            pool.shutdownNow();
            setGlobalLockWaitTimeout(originalLockWaitTimeout);
            originalLockWaitTimeout = null;
        }
        LocalDate after = LocalDate.now();

        for (long aligned : new long[] {a, c}) {
            assertEquals("CONFIRMED", status(aligned), "M-1: 他の副本はcommitされる");
            assertRetentionBetween(aligned, before, after);
        }
        assertEquals("DRAFT", status(b), "M-1: lock待ちがtimeoutした副本は変えない");
        assertNull(retentionUntil(b));
        assertEquals(0L, count("SELECT COUNT(*) FROM t_resume_ingestion WHERE tenant_id = ? AND stored_file_name IS NOT NULL",
                TENANT_A), "M-1: 原本の purge は3件ともcommitされる");
        assertNull(jdbc.queryForObject("SELECT extracted_text FROM t_bp_availability_ingestion WHERE id = ?", String.class, bp),
                "M-1: 副本の失敗の後も同じtenantのBP取込の清掃まで進む");
        assertEquals(failuresBefore + 1, failures(ResumeIngestionLedgerRetentionService.STAGE_DOCUMENT), 0.0001,
                "M-1: 失敗はmetricに1件数える");

        clearSchedulerLocks();
        cleanup.cleanupExpiredExtractedText();
        assertEquals("CONFIRMED", status(b), "M-1: 次の実行で副本Bを拾い直す");
        assertEquals(failuresBefore + 1, failures(ResumeIngestionLedgerRetentionService.STAGE_DOCUMENT), 0.0001);
    }

    // ===== M-2 legal hold の変更との直列化 =====

    @Test
    void M2_holdの変更が先にlockを持てば副本の処理は待ちcommit後のholdを見て何も変えない() throws Exception {
        long d = copyOfExpiredJob(TENANT_A, "DRAFT", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holdChanged = new CountDownLatch(1);
        CountDownLatch releaseHold = new CountDownLatch(1);
        AtomicLong holdConnection = new AtomicLong();
        try {
            Future<?> hold = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                holdConnection.set(connectionId());
                assertTrue(eligibility.changeLegalHold(lockedSnapshot(d), true));
                holdChanged.countDown();
                awaitQuietly(releaseHold, WAIT_SECONDS);
            }));
            assertTrue(holdChanged.await(WAIT_SECONDS, TimeUnit.SECONDS), "M-2: holdを設定できなかった: " + outcome(hold));
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, holdConnection.get(), TABLE, PRIMARY,
                    InnoDbRecordLockWaits.primaryKey(d), "M-2 hold先行");

            clearSchedulerLocks();
            Future<?> run = pool.submit(() -> cleanup.cleanupExpiredExtractedText());
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor, null,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    TimeUnit.SECONDS.toMillis(WAIT_SECONDS), () -> {
                        if (run.isDone()) {
                            throw new AssertionError("M-2: 副本の処理がholdのlockを待たずに終わった: " + outcome(run));
                        }
                    }, "M-2 hold先行");
            assertFalse(run.isDone(), "M-2: 副本の処理はholdのcommitまで待つ");

            releaseHold.countDown();
            hold.get(WAIT_SECONDS, TimeUnit.SECONDS);
            run.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseHold.countDown();
            pool.shutdownNow();
        }

        assertEquals("DRAFT", status(d), "M-2: commit後のholdを見て確定しない");
        assertNull(retentionUntil(d));
        assertEquals(1L, count("SELECT legal_hold_flag FROM t_document WHERE id = ?", d));
        assertEquals(0L, count("SELECT COUNT(*) FROM t_resume_ingestion WHERE tenant_id = ? AND stored_file_name IS NOT NULL",
                TENANT_A), "M-2: 清掃は実際に動き原本をpurgeしている");
        long control = copy(TENANT_A, "CONFIRMED", false, LocalDate.now().minusDays(1));
        clearSchedulerLocks();
        documentRetentionJob.processExpiredDocuments();
        assertEquals(1L, pendingBySystem(control), "M-2: D3は実際に動いている（対照）");
        assertEquals(0L, requests(d), "M-2: hold中の副本は廃棄候補にならない");
    }

    @Test
    void M2_副本の処理が先にlockを持てばholdの変更は待ちその後のD3は廃棄候補を作らない() throws Exception {
        long e = copyOfPurgedJob(TENANT_A, "DRAFT", null);
        // D3 が実際に動いたことを示す対照（link が無いので副本の処理の対象外、lock も取らない）
        long control = copy(TENANT_A, "CONFIRMED", false, LocalDate.now().minusDays(1));
        LocalDate today = LocalDate.now();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch alignedLatch = new CountDownLatch(1);
        CountDownLatch releaseAlign = new CountDownLatch(1);
        AtomicLong alignConnection = new AtomicLong();
        AtomicLong holdConnection = new AtomicLong();
        AtomicReference<ResumeIngestionLedgerRetentionService.Result> alignResult = new AtomicReference<>();
        try {
            // 副本の処理を lock 保持のまま止めるため、本ケースだけ外側 transaction の中で呼ぶ（class の Javadoc）
            Future<?> align = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                alignConnection.set(connectionId());
                alignResult.set(ledgerRetention.alignPurgedCopies(TENANT_A, today));
                alignedLatch.countDown();
                awaitQuietly(releaseAlign, WAIT_SECONDS);
            }));
            assertTrue(alignedLatch.await(WAIT_SECONDS, TimeUnit.SECONDS), "M-2: 副本を処理できなかった: " + outcome(align));
            assertEquals(1, alignResult.get().aligned());
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, alignConnection.get(), TABLE, PRIMARY,
                    InnoDbRecordLockWaits.primaryKey(e), "M-2 副本先行");

            Future<?> hold = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                holdConnection.set(connectionId());
                // 操作者の再試行と同じく、lock 取得後の最新の行（副本の処理の commit 後の版）で hold を設定する
                assertTrue(eligibility.changeLegalHold(lockedSnapshot(e), true));
            }));
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor,
                    () -> holdConnection.get() == 0 ? null : holdConnection.get(),
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    TimeUnit.SECONDS.toMillis(WAIT_SECONDS), () -> {
                        if (hold.isDone()) {
                            throw new AssertionError("M-2: holdの変更が副本の処理のlockを待たずに終わった: " + outcome(hold));
                        }
                    }, "M-2 副本先行");
            assertFalse(hold.isDone(), "M-2: holdの変更は副本の処理のcommitまで待つ");

            releaseAlign.countDown();
            align.get(WAIT_SECONDS, TimeUnit.SECONDS);
            hold.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseAlign.countDown();
            pool.shutdownNow();
        }

        assertEquals("CONFIRMED", status(e));
        assertEquals(today, retentionUntil(e));
        assertEquals(1L, count("SELECT legal_hold_flag FROM t_document WHERE id = ?", e));
        clearSchedulerLocks();
        documentRetentionJob.processExpiredDocuments();
        assertEquals(1L, pendingBySystem(control), "M-2: D3は実際に動いている（対照）");
        assertEquals(0L, requests(e), "M-2: holdの後のD3は廃棄候補を作らない");
    }

    // ===== M-3 tenant 境界 =====

    @Test
    void M3_linkやジョブが別tenantの副本は変えず別tenantの実行は副本に触れない() {
        // tenant B のジョブは purge 済みにしておく（tenant の処理順に関係なく、除外の理由が tenant の条件だけになる）
        long jobInB = purgedJob(TENANT_B);
        long linkInB = copy(TENANT_A, "DRAFT", false, null);
        link(linkInB, TENANT_B, jobInB);
        long jobInB2 = purgedJob(TENANT_B);
        long jobOnlyInB = copy(TENANT_A, "DRAFT", false, null);
        link(jobOnlyInB, TENANT_A, jobInB2);
        long control = copyOfPurgedJob(TENANT_A, "DRAFT", null);

        assertEquals(0, ledgerRetention.alignPurgedCopies(TENANT_B, LocalDate.now()).aligned(),
                "M-3: 別tenantの実行は副本に触れない");
        assertEquals("DRAFT", status(control));

        clearSchedulerLocks();
        cleanup.cleanupExpiredExtractedText();

        assertEquals("CONFIRMED", status(control), "M-3: 対照（全てtenant A）は合わせる");
        for (long unchanged : new long[] {linkInB, jobOnlyInB}) {
            assertEquals("DRAFT", status(unchanged), "M-3: link・ジョブが別tenantの副本は変えない");
            assertNull(retentionUntil(unchanged));
        }
    }

    // ===== M-4 端から端まで =====

    @Test
    void M4_清掃からD3と承認と執行まで通り再実行で申請は重複せず保存期限は延ばさない() throws Exception {
        LocalDate start = LocalDate.now();
        long draft = copyOfExpiredJob(TENANT_A, "DRAFT", null);
        String storageKey = storeVersion(draft);
        long later = copyOfExpiredJob(TENANT_A, "CONFIRMED", start.plusDays(10));
        long earlier = copyOfExpiredJob(TENANT_A, "CONFIRMED", start.minusDays(10));
        long amended = copyOfExpiredJob(TENANT_A, "AMENDED", null);
        long earlierVersion = version(earlier);
        long[] epochsBefore = {epoch(draft), epoch(later), epoch(earlier), epoch(amended)};

        clearSchedulerLocks();
        cleanup.cleanupExpiredExtractedText();
        LocalDate after = LocalDate.now();

        assertEquals("CONFIRMED", status(draft));
        assertRetentionBetween(draft, start, after);
        assertRetentionBetween(later, start, after);
        assertEquals(start.minusDays(10), retentionUntil(earlier), "M-4: 当日より前の保存期限は延ばさない");
        assertEquals(earlierVersion, version(earlier));
        assertEquals("AMENDED", status(amended), "M-4: 版追加済みは状態を変えない");
        assertRetentionBetween(amended, start, after);
        long[] epochsAfter = {epoch(draft), epoch(later), epoch(earlier), epoch(amended)};
        for (int i = 0; i < epochsBefore.length; i++) {
            assertEquals(epochsBefore[i], epochsAfter[i], "M-4: 確定・保存期限の変更は証跡利用可否のepochを進めない");
        }

        clearSchedulerLocks();
        documentRetentionJob.processExpiredDocuments();
        assertEquals(1L, pendingBySystem(draft));
        assertEquals(1L, pendingBySystem(later));
        assertEquals(1L, pendingBySystem(earlier));
        assertEquals(0L, requests(amended), "M-4: AMENDED はD3の自動抽出の対象外（D2の手動申請）");

        long requestId = count("SELECT id FROM t_document_disposal_request WHERE document_id = ? AND status = 'PENDING'",
                draft);
        assertEquals("resume", new String(documentStorage.readAll(storageKey), StandardCharsets.UTF_8));
        asAdmin(TENANT_A, () -> documentService.approveDisposal(requestId));
        asAdmin(TENANT_A, () -> documentService.executeDisposal(requestId));

        assertEquals("DISPOSED", status(draft));
        assertThrows(IOException.class, () -> documentStorage.readAll(storageKey), "M-4: 副本の実体fileは消える");

        clearSchedulerLocks();
        cleanup.cleanupExpiredExtractedText();
        clearSchedulerLocks();
        documentRetentionJob.processExpiredDocuments();
        for (long document : new long[] {draft, later, earlier}) {
            assertEquals(1L, requests(document), "M-4: 再実行で申請は重複しない");
        }
        assertEquals(0L, requests(amended));
        assertEquals("DISPOSED", status(draft));
    }

    // ===== helpers =====

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private long connectionId() {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** 同じ transaction の lock 取得後の最新の行（RR の snapshot ではなく locking read）。 */
    private Document lockedSnapshot(long documentId) {
        return documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getId, documentId).last("FOR UPDATE"));
    }

    /** GLOBAL の lock 待ち timeout を変え、既存の pool 接続を入れ替える（新しい接続が新しい値を持つ）。 */
    private void setGlobalLockWaitTimeout(int seconds) {
        jdbc.execute("SET GLOBAL innodb_lock_wait_timeout = " + seconds);
        try {
            DataSource dataSource = jdbc.getDataSource();
            if (dataSource == null) {
                throw new IllegalStateException("JdbcTemplateにDataSourceがありません");
            }
            dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("接続poolを入れ替えられませんでした", failure);
        }
    }

    /**
     * 日次 job の ShedLock を期限切れにする。行は消さない（ShedLock は一度 insert した lock 名を記憶し、以後は
     * {@code lock_until <= now} の UPDATE だけで取るため、行を消すと以後の呼出しが黙って skip される）。
     */
    private void clearSchedulerLocks() {
        jdbc.update("UPDATE shedlock SET lock_until = ? WHERE name IN (?, ?)",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), SCHEDULER_LOCKS.get(0), SCHEDULER_LOCKS.get(1));
    }

    private void asAdmin(String tenant, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("900001", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
        try {
            AccountingTenantContextHolder.runWithTenant(tenant, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private double failures(String stage) {
        Counter counter = meterRegistry.find(ResumeIngestionLedgerRetentionService.FAILURE_METRIC)
                .tag("stage", stage).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 原本を持つ期限切れの履歴取込ジョブ（確定済）。 */
    private long expiredJob(String tenant) {
        String storedName = "nf07-rrm-" + System.nanoTime() + ".pdf";
        return insert("INSERT INTO t_resume_ingestion (tenant_id, original_file_name, stored_file_name, file_ext, status, "
                + "extracted_text, version, created_at, updated_at, deleted_flag) "
                + "VALUES (?, 'resume.pdf', ?, 'pdf', '確定済', 'text', 0, ?, ?, 0)",
                tenant, storedName, Timestamp.valueOf(OLD), Timestamp.valueOf(OLD));
    }

    /** 原本を purge 済み（stored_file_name なし・確定済）のジョブ。 */
    private long purgedJob(String tenant) {
        return insert("INSERT INTO t_resume_ingestion (tenant_id, status, version, created_at, updated_at, deleted_flag) "
                + "VALUES (?, '確定済', 1, ?, ?, 0)", tenant, Timestamp.valueOf(OLD), Timestamp.valueOf(OLD));
    }

    private long copy(String tenant, String status, boolean held, LocalDate retentionUntil) {
        return insert("INSERT INTO t_document (tenant_id, document_type, direction, status, legal_hold_flag, version, "
                        + "retention_until, title) VALUES (?, 'RESUME_INGESTION', 'INCOMING', ?, ?, 1, ?, 'resume.pdf')",
                tenant, status, held ? 1 : 0, retentionUntil == null ? null : Date.valueOf(retentionUntil));
    }

    private void link(long documentId, String linkTenant, long jobId) {
        insert("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id) "
                + "VALUES (?, ?, 'RESUME_INGESTION', ?)", linkTenant, documentId, jobId);
    }

    private long copyOfExpiredJob(String tenant, String status, LocalDate retentionUntil) {
        long job = expiredJob(tenant);
        long document = copy(tenant, status, false, retentionUntil);
        link(document, tenant, job);
        return document;
    }

    private long copyOfPurgedJob(String tenant, String status, LocalDate retentionUntil) {
        long job = purgedJob(tenant);
        long document = copy(tenant, status, false, retentionUntil);
        link(document, tenant, job);
        return document;
    }

    /** 副本の版1件と実体 file（document storage の公開側）を作り、storage key を返す。 */
    private String storeVersion(long documentId) {
        String key = "nf07/resume-ledger/" + documentId + ".pdf";
        documentStorage.put(key, "resume".getBytes(StandardCharsets.UTF_8), false);
        insert("INSERT INTO t_document_version (tenant_id, document_id, version_no, storage_key, original_name, sha256, "
                        + "source_type, business_key, version_discriminator, scan_status) "
                        + "VALUES (?, ?, 1, ?, 'resume.pdf', ?, 'GENERATED', ?, 'v1', 'CLEAN')",
                TENANT_A, documentId, key, "a".repeat(64), "NF07-RRM:" + documentId);
        return key;
    }

    /** tenant の利用者が作った、抽出テキストを持つ期限切れの BP 空き取込ジョブ。 */
    private long bpIngestionWithText(String tenant) {
        long user = insert("INSERT INTO sys_user (tenant_id, username, password, role) VALUES (?, ?, 'x', '要員')",
                tenant, "nf07-rrm-" + System.nanoTime());
        return insert("INSERT INTO t_bp_availability_ingestion (file_ext, status, extracted_text, created_by, created_at, "
                        + "updated_at, deleted_flag) VALUES ('pdf', '確定済', 'text', ?, ?, ?, 0)",
                user, Timestamp.valueOf(OLD), Timestamp.valueOf(OLD));
    }

    private String status(long documentId) {
        return jdbc.queryForObject("SELECT status FROM t_document WHERE id = ?", String.class, documentId);
    }

    private LocalDate retentionUntil(long documentId) {
        Date value = jdbc.queryForObject("SELECT retention_until FROM t_document WHERE id = ?", Date.class, documentId);
        return value == null ? null : value.toLocalDate();
    }

    private void assertRetentionBetween(long documentId, LocalDate from, LocalDate to) {
        LocalDate value = retentionUntil(documentId);
        assertTrue(value != null && !value.isBefore(from) && !value.isAfter(to),
                "保存期限が実行日に合っていること: documentId=" + documentId + " retentionUntil=" + value);
    }

    private long version(long documentId) {
        return count("SELECT version FROM t_document WHERE id = ?", documentId);
    }

    private long epoch(long documentId) {
        return count("SELECT evidence_eligibility_epoch FROM t_document WHERE id = ?", documentId);
    }

    private long requests(long documentId) {
        return count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ?", documentId);
    }

    private long pendingBySystem(long documentId) {
        return count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? AND status = 'PENDING' "
                + "AND requested_by = -1", documentId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String outcome(Future<?> future) {
        try {
            return String.valueOf(future.get(1, TimeUnit.SECONDS));
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    private static Path createUploadRoot() {
        try {
            return Files.createTempDirectory("nf07-resume-ledger-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
