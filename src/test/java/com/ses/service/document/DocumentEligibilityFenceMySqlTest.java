package com.ses.service.document;

import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.impl.DocumentMetadataCommitService;
import com.ses.service.job.DocumentRetentionJob;
import com.ses.service.security.epoch.AuthorizationEpochReader;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.HeldLock;
import com.ses.test.InnoDbRecordLockWaits.PointLock;
import com.ses.test.InnoDbRecordLockWaits.WaitEdge;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M7（NF01細粒度epoch計画 §6.1・§7.6 W3-17）: 実MySQL 8の空DB（V1〜LATEST）で、文書の証跡利用可否を変える
 * writerが文書行をX lockしてから書き、commitした変化の分だけ {@code evidence_eligibility_epoch} を進めることと、
 * 証跡readerの {@code FOR SHARE} と両順序で直列化することを確かめる。
 *
 * <ul>
 *   <li>reader先行: readerが文書行のS lockを保持している間、writerは文書行のX lockを待ち、解放後に1進める。</li>
 *   <li>writer先行: writerが変更を終えてcommit前にX lockを保持している間、readerは待ち、commit後の値（+1）を読む。</li>
 *   <li>待機の判定は {@code data_locks.LOCK_DATA} に依存せず、保持側が主キー一点で取った単一のrecord lockを基準に、
 *       待機辺のblocking lockがそのlockであることで確かめる（{@link InnoDbRecordLockWaits}）。</li>
 *   <li>対象writer: link追加（ENGINEER・SERVICE_REQUEST）、法的holdの設定・解除、廃棄申請・却下・承認・執行、
 *       保存期限jobの廃棄候補（tenant contextなし）、資産貸与・返却の証跡link。版追加・確定・同じ値の再設定・
 *       既存linkの再追加では進まない。</li>
 *   <li>link挿入は全文書共有の一意indexにgap lockを残さず、別文書のlink挿入を待たせない（W3-17 Review P2-1）。</li>
 * </ul>
 *
 * <p>紛失インシデントのlink（AssetLostIncidentServiceImpl）は同じ {@link DocumentLinkCommandService#link} を通り、
 * fastの {@code AssetServiceTest}・{@code AssetBoundaryAndLifecycleIntegrationTest} が機能を固定する（本classでは紛失報告の
 * 通知・scope fixtureを持たないため扱わない）。confirmSkillSheetがregisterGenerated前にlockを持たないことはfastの
 * {@code EngineerChangeRequestSkillSheetLockOrderTest} が固定する。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class DocumentEligibilityFenceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_document_eligibility")
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

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final long WAIT_SECONDS = 30;
    private static final String TABLE = "t_document";
    private static final String PRIMARY = "PRIMARY";
    private static final Set<String> SHARED_RECORD_MODES = Set.of("S", "S,REC_NOT_GAP");
    private static final LocalDate FUTURE = LocalDate.of(2099, 1, 1);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AuthorizationEpochReader reader;
    @Autowired private DocumentLinkCommandService linkCommand;
    @Autowired private DocumentEligibilityService eligibility;
    @Autowired private DocumentMetadataCommitService metadataCommit;
    @Autowired private DocumentMapper documentMapper;

    @Autowired private com.ses.service.AssetService assetService;
    @Autowired private com.ses.service.AssetAssignmentService assetAssignmentService;

    /** writerの1経路。{@code prepare} はcommit済みのfixtureを作り、{@code write} はtransaction内で変更する。 */
    private record WriterCase(String label, Function<Fixture, Fixture> prepare, Consumer<Fixture> write) { }

    private record Fixture(String tenant, long documentId, long requestId, long assetId, long assignmentId) {
        Fixture withRequest(long id) {
            return new Fixture(tenant, documentId, id, assetId, assignmentId);
        }

        Fixture withAsset(long asset, long assignment) {
            return new Fixture(tenant, documentId, requestId, asset, assignment);
        }
    }

    private List<WriterCase> writers() {
        return List.of(
                new WriterCase("link追加（ENGINEER）", f -> f,
                        f -> linkCommand.link(f.tenant(), f.documentId(), "ENGINEER", 7L)),
                new WriterCase("link追加（SERVICE_REQUEST）", f -> f,
                        f -> linkCommand.link(f.tenant(), f.documentId(), "SERVICE_REQUEST", 99L)),
                new WriterCase("法的holdの設定", f -> f,
                        f -> eligibility.changeLegalHold(snapshot(f), true)),
                new WriterCase("法的holdの解除", f -> {
                    jdbc.update("UPDATE t_document SET legal_hold_flag = 1 WHERE id = ?", f.documentId());
                    return f;
                }, f -> eligibility.changeLegalHold(snapshot(f), false)),
                new WriterCase("廃棄申請", f -> f,
                        f -> eligibility.requestDisposal(f.tenant(), f.documentId(), 2L, "期限")),
                new WriterCase("廃棄申請の却下", f -> f.withRequest(insertRequest(f.documentId(), "PENDING")),
                        f -> eligibility.rejectDisposal(f.tenant(), f.documentId(), f.requestId(), 3L, "却下理由: 継続")),
                new WriterCase("廃棄申請の承認", f -> f.withRequest(insertRequest(f.documentId(), "PENDING")),
                        f -> eligibility.approveDisposal(f.tenant(), f.documentId(), f.requestId(), 3L)),
                new WriterCase("廃棄の執行", f -> f.withRequest(insertRequest(f.documentId(), "APPROVED")),
                        f -> eligibility.executeDisposal(f.tenant(), f.documentId(), f.requestId())),
                new WriterCase("保存期限jobの廃棄候補（tenant contextなし）", f -> {
                    jdbc.update("UPDATE t_document SET retention_until = ? WHERE id = ?",
                            Date.valueOf(LocalDate.now().minusDays(1)), f.documentId());
                    return f;
                }, f -> {
                    AccountingTenantContextHolder.clear();
                    eligibility.registerRetentionDisposalCandidate(f.tenant(), f.documentId(), LocalDate.now());
                }),
                // 資産貸与の受渡証跡（資産X → 貸与insert → 文書X → link。計画 §4.3、F-3）
                new WriterCase("資産貸与の受渡証跡link", f -> f.withAsset(newAsset(), 0L),
                        f -> assetAssignmentService.createAssignment(f.assetId(), "ENGINEER", 7L, LocalDate.now(),
                                LocalDate.now().plusDays(30), f.documentId(), "受渡", 1L)),
                // 資産返却の返却証跡（資産X → 貸与X → 文書X → link）
                new WriterCase("資産返却の返却証跡link", f -> {
                    long asset = newAsset();
                    long assignment = assetAssignmentService.createAssignment(asset, "ENGINEER", 7L, LocalDate.now(),
                            LocalDate.now().plusDays(30), null, "受渡", 1L).getId();
                    return f.withAsset(asset, assignment);
                }, f -> assetAssignmentService.returnAssignment(f.assignmentId(), LocalDate.now(), f.documentId(),
                        "返却", 1L)));
    }

    @Test
    void 別文書へのlink挿入は互いに待たず同じtransactionで2件作っても1213にならない() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstLinked = new CountDownLatch(1);
        CountDownLatch otherFinished = new CountDownLatch(1);
        try {
            // T1: 文書Aへ1件目のlinkを作り、T2が終わるまでcommitせずに保持してから2件目を作る（経費の領収書と同じ形）
            Future<?> first = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                linkCommand.link(a.tenant(), a.documentId(), "ENGINEER", 7L);
                firstLinked.countDown();
                awaitQuietly(otherFinished, WAIT_SECONDS);
                linkCommand.link(a.tenant(), a.documentId(), "EXPENSE_REQUEST", 9L);
            }));
            if (!firstLinked.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                fail("T1が1件目のlinkを作れなかった: " + outcome(first));
            }
            // T2: 別の文書Bへ同じ形の2件を作る。共有の一意indexにgap lockが残っていれば、T1のcommitまで待ってしまう
            Future<?> second = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                linkCommand.link(b.tenant(), b.documentId(), "ENGINEER", 7L);
                linkCommand.link(b.tenant(), b.documentId(), "EXPENSE_REQUEST", 9L);
            }));
            try {
                second.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException waited) {
                fail("別文書のlink挿入がT1の未commitのlinkを待った（一意indexのgap lock）: "
                        + InnoDbRecordLockWaits.waitEdgesOnIndex(jdbc, "t_document_link", "uk_document_link"));
            }
            otherFinished.countDown();
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            otherFinished.countDown();
            pool.shutdownNow();
        }
        assertEquals(1L, epoch(a.documentId()));
        assertEquals(1L, epoch(b.documentId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM t_document_link WHERE document_id = ?", a.documentId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM t_document_link WHERE document_id = ?", b.documentId()));
    }

    @Test
    void 既存linkの再作成は重複を受け止め別tenantと論理削除済みを拒否する() {
        Fixture f = fixture();
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id) "
                + "VALUES ('default', ?, 'ENGINEER', 7)", f.documentId());
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id, deleted_flag) "
                + "VALUES (?, ?, 'ENGINEER', 8, 1)", f.tenant(), f.documentId());

        var foreign = org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> inTransaction(() -> linkCommand.link(f.tenant(), f.documentId(), "ENGINEER", 7L)));
        assertEquals(403, foreign.getCode());
        var deleted = org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> inTransaction(() -> linkCommand.link(f.tenant(), f.documentId(), "ENGINEER", 8L)));
        assertEquals(409, deleted.getCode());
        inTransaction(() -> linkCommand.link(f.tenant(), f.documentId(), "SERVICE_REQUEST", 99L));
        inTransaction(() -> linkCommand.link(f.tenant(), f.documentId(), "SERVICE_REQUEST", 99L));
        assertEquals(1L, epoch(f.documentId()), "挿入した1回だけ進み、重複・拒否では進まない");
    }

    @Test
    void readerが文書行のS_lockを持つ間は各writerが待ち解放後に1進める() throws Exception {
        for (WriterCase writer : writers()) {
            Fixture f = writer.prepare().apply(fixture());
            long before = epoch(f.documentId());
            assertWriterWaitsForReader(writer, f);
            assertEquals(before + 1, epoch(f.documentId()), writer.label() + ": commitした変化の分だけ1進む");
        }
    }

    @Test
    void writerが文書行のX_lockを持つ間はreaderが待ちcommit後の値を読む() throws Exception {
        for (WriterCase writer : writers()) {
            Fixture f = writer.prepare().apply(fixture());
            long before = epoch(f.documentId());
            long read = assertReaderWaitsForWriter(writer, f);
            assertEquals(before + 1, read, writer.label() + ": readerはwriterのcommit後の値を読む");
            assertEquals(before + 1, epoch(f.documentId()), writer.label());
        }
    }

    @Test
    void 変化の無い書込みと版追加と確定ではepochが進まない() {
        Fixture f = fixture();
        inTransaction(() -> linkCommand.link(f.tenant(), f.documentId(), "ENGINEER", 7L));
        long linked = epoch(f.documentId());
        inTransaction(() -> linkCommand.link(f.tenant(), f.documentId(), "ENGINEER", 7L));
        inTransaction(() -> eligibility.changeLegalHold(snapshot(f), false));
        inTransaction(() -> linkCommand.recordSkillSheetConfirmation(f.tenant(), f.documentId(), 7L,
                java.time.LocalDateTime.now(), "fp"));
        DocumentVersion appended = version(f, 2);
        metadataCommit.commitVersion(snapshot(f), appended);
        assertEquals(linked, epoch(f.documentId()), "既存link・同じhold値・付帯情報・版追加では進まない");
        assertEquals("AMENDED", jdbc.queryForObject("SELECT status FROM t_document WHERE id = ?", String.class,
                f.documentId()), "版追加でCONFIRMEDはAMENDEDになる");

        Fixture draft = fixture("DRAFT");
        inTransaction(() -> eligibility.confirm(snapshot(draft), FUTURE));
        assertEquals(0L, epoch(draft.documentId()), "DRAFT→CONFIRMEDでは進まない");
    }

    @Test
    void 同じtransactionで複数の変化があっても1だけ進む() {
        Fixture f = fixture();
        inTransaction(() -> {
            linkCommand.link(f.tenant(), f.documentId(), "ENGINEER", 7L);
            eligibility.changeLegalHold(snapshot(f), true);
            eligibility.changeLegalHold(snapshotForUpdate(f), false);
            eligibility.requestDisposal(f.tenant(), f.documentId(), 2L, "期限");
        });
        assertEquals(1L, epoch(f.documentId()));
    }

    @Test
    void 保存期限jobはtenant_contextなしで各文書自身のtenantのepochを進める() {
        Fixture a = fixture();
        Fixture b = fixture();
        LocalDate past = LocalDate.now().minusDays(1);
        jdbc.update("UPDATE t_document SET retention_until = ? WHERE id IN (?, ?)", Date.valueOf(past),
                a.documentId(), b.documentId());
        AccountingTenantContextHolder.clear();

        // ShedLockのproxyを通さずjob本体を実行する（文書ごとにserviceの短いtransactionでcommitする）
        new DocumentRetentionJob(documentMapper, eligibility, com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.noHoldsGuard()).processExpiredDocuments();

        assertEquals(1L, epoch(a.documentId()));
        assertEquals(1L, epoch(b.documentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING' AND requested_by = -1", a.documentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING' AND requested_by = -1", b.documentId()));
    }

    // ===== helpers: reader × writer =====

    private void assertWriterWaitsForReader(WriterCase writer, Fixture f) throws Exception {
        String label = writer.label();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicLong readerConnection = new AtomicLong();
        AtomicLong writerConnection = new AtomicLong();
        try {
            Future<Long> readerResult = pool.submit(() -> readCommitted().execute(status -> {
                try {
                    readerConnection.set(connectionId());
                    return reader.readDocument(f.tenant(), f.documentId());
                } finally {
                    readerLocked.countDown();
                    awaitQuietly(releaseReader, WAIT_SECONDS * 3);
                    status.setRollbackOnly();
                }
            }));
            if (!readerLocked.await(WAIT_SECONDS, TimeUnit.SECONDS) || readerResult.isDone()) {
                fail(label + ": readerがS lockを保持できなかった: " + outcome(readerResult));
            }
            String sharedLockId = requireSingleSharedLock(readerConnection.get(), label);

            Future<?> writerResult = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                writerConnection.set(connectionId());
                writer.write().accept(f);
            }));
            WaitEdge edge = awaitWriterWaitsOnSharedLock(writerResult, writerConnection, readerConnection.get(),
                    sharedLockId, label);
            assertFalse(writerResult.isDone(), label + ": writerはreaderの解放まで待つこと。edge=" + edge.describe());

            releaseReader.countDown();
            readerResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
            writerResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseReader.countDown();
            pool.shutdownNow();
        }
    }

    private long assertReaderWaitsForWriter(WriterCase writer, Fixture f) throws Exception {
        String label = writer.label();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerChanged = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        AtomicLong readerConnection = new AtomicLong();
        try {
            Future<?> writerResult = pool.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                writerConnection.set(connectionId());
                try {
                    writer.write().accept(f);
                } finally {
                    writerChanged.countDown();
                }
                awaitQuietly(releaseWriter, WAIT_SECONDS * 3);
            }));
            if (!writerChanged.await(WAIT_SECONDS, TimeUnit.SECONDS) || writerResult.isDone()) {
                fail(label + ": writerが変更を終えてX lockを保持できなかった: " + outcome(writerResult));
            }
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, writerConnection.get(), TABLE, PRIMARY,
                    InnoDbRecordLockWaits.primaryKey(f.documentId()), label);

            Future<Long> readerResult = pool.submit(() -> readCommitted().execute(status -> {
                readerConnection.set(connectionId());
                try {
                    return reader.readDocument(f.tenant(), f.documentId());
                } finally {
                    status.setRollbackOnly();
                }
            }));
            WaitEdge edge = InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor,
                    () -> readerConnection.get() == 0 ? null : readerConnection.get(), SHARED_RECORD_MODES,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, TimeUnit.SECONDS.toMillis(WAIT_SECONDS), () -> {
                        if (readerResult.isDone()) {
                            fail(label + ": readerが待たずに終了した: " + outcome(readerResult));
                        }
                    }, label);
            assertFalse(readerResult.isDone(), label + ": readerはwriterのcommitまで待つこと。edge=" + edge.describe());

            releaseWriter.countDown();
            writerResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return readerResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /** readerが文書行の主キーで共有record lockをちょうど1件保持していることを確かめ、そのlock IDを返す。 */
    private String requireSingleSharedLock(long readerConnection, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<HeldLock> held = List.of();
        while (System.nanoTime() < deadline) {
            held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, readerConnection, TABLE, PRIMARY, SHARED_RECORD_MODES);
            if (held.size() == 1) {
                return held.get(0).lockId();
            }
            if (held.size() > 1) {
                break;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + ": readerが文書行の共有record lockをちょうど1件保持していることを確認できなかった: "
                + "count=" + held.size() + "; " + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, readerConnection));
    }

    /** writerがreaderの共有lock（固定したlock ID）を、文書行の排他record lockで待っていることを確かめる。 */
    private WaitEdge awaitWriterWaitsOnSharedLock(Future<?> writer, AtomicLong writerConnection, long readerConnection,
                                                  String sharedLockId, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (writer.isDone()) {
                fail(label + ": writerが待たずに終了した: " + outcome(writer));
            }
            List<HeldLock> held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, readerConnection, TABLE, PRIMARY,
                    SHARED_RECORD_MODES);
            if (held.size() != 1 || !held.get(0).lockId().equals(sharedLockId)) {
                fail(label + ": readerの共有lockが固定したものと変わった: anchored=" + sharedLockId + ", held=" + held);
            }
            long requester = writerConnection.get();
            if (requester > 0) {
                for (WaitEdge edge : InnoDbRecordLockWaits.waitEdges(jdbc, requester, readerConnection, TABLE, PRIMARY)) {
                    if (sharedLockId.equals(edge.blockerLockId())
                            && "RECORD".equals(edge.requesterType()) && "WAITING".equals(edge.requesterStatus())
                            && InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES.contains(edge.requesterMode())
                            && "RECORD".equals(edge.blockerType()) && "GRANTED".equals(edge.blockerStatus())
                            && SHARED_RECORD_MODES.contains(edge.blockerMode())) {
                        return edge;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + ": writerが文書行でreaderの共有lockを待つことを確認できなかった: writer="
                + writerConnection.get() + "; " + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, readerConnection));
    }

    // ===== helpers: transaction・fixture =====

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    /** CONFIRM attemptと同じ READ_COMMITTED のtransaction。 */
    private TransactionTemplate readCommitted() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private void inTransaction(Runnable action) {
        transactionTemplate().executeWithoutResult(status -> action.run());
    }

    private long connectionId() {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
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

    private Fixture fixture() {
        return fixture("CONFIRMED");
    }

    /** 一意なtenantのSKILL_SHEET文書（hold無し、保存期限は未来日）とCLEAN版1件をcommitする。 */
    private Fixture fixture(String status) {
        String tenant = "nf01-f3-" + SEQUENCE.incrementAndGet();
        long documentId = insert("INSERT INTO t_document (tenant_id, document_type, direction, status, legal_hold_flag, "
                + "version, retention_until, title) VALUES (?, 'SKILL_SHEET', 'INTERNAL', ?, 0, 1, ?, 'F3検証')",
                tenant, status, Date.valueOf(FUTURE));
        insert("INSERT INTO t_document_version (tenant_id, document_id, version_no, storage_key, original_name, sha256, "
                        + "source_type, business_key, version_discriminator, scan_status) "
                        + "VALUES (?, ?, 1, ?, 'f3.pdf', ?, 'GENERATED', ?, 'v1', 'CLEAN')",
                tenant, documentId, "nf01/f3/" + documentId, "d".repeat(64), "NF01-F3:" + documentId);
        return new Fixture(tenant, documentId, 0L, 0L, 0L);
    }

    /** 保管中（IN_STOCK）の資産をcommitして返す。 */
    private long newAsset() {
        com.ses.entity.Asset asset = com.ses.entity.Asset.builder()
                .assetTag("NF01-F3-AST-" + SEQUENCE.incrementAndGet())
                .assetName("F3検証端末").category("PC").status("IN_STOCK").build();
        assetService.createAsset(asset, 1L);
        return asset.getId();
    }

    private long insertRequest(long documentId, String status) {
        return insert("INSERT INTO t_document_disposal_request (document_id, requested_by, status, reason) "
                + "VALUES (?, 2, ?, 'F3検証')", documentId, status);
    }

    private Document snapshot(Fixture f) {
        return documentMapper.selectById(f.documentId());
    }

    /** 同じtransactionで自分が書いた最新の行を読む（RRのsnapshotではなくlocking read）。 */
    private Document snapshotForUpdate(Fixture f) {
        return documentMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Document>()
                .eq(Document::getId, f.documentId()).last("FOR UPDATE"));
    }

    private DocumentVersion version(Fixture f, int versionNo) {
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(f.tenant());
        version.setDocumentId(f.documentId());
        version.setVersionNo(versionNo);
        version.setStorageKey("nf01/f3/" + f.documentId() + "/v" + versionNo);
        version.setOriginalName("f3.pdf");
        version.setSha256("e".repeat(64));
        version.setSourceType("GENERATED");
        version.setBusinessKey("NF01-F3:" + f.documentId());
        version.setVersionDiscriminator("v" + versionNo);
        version.setScanStatus("CLEAN");
        return version;
    }

    private long epoch(long documentId) {
        return count("SELECT evidence_eligibility_epoch FROM t_document WHERE id = ?", documentId);
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
}
