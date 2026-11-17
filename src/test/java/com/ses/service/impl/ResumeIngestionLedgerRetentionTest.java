package com.ses.service.impl;

import com.ses.entity.Document;
import com.ses.service.document.DocumentEligibilityService;
import com.ses.service.document.ResumeIngestionLedgerRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履歴・スキルシート取込の原本 purge に合わせて、文書台帳の副本（RESUME_INGESTION）を処分対象にする（2026-11-16 依頼者決定）。
 *
 * <p>原本の purge 後、副本は CONFIRMED・保存期限＝当日になり、既存の廃棄候補作成（{@code DocumentRetentionJob} と同じ
 * {@code registerRetentionDisposalCandidate}）が受け付ける。legal hold 中の副本は変えない。link・ジョブが別 tenant の副本に
 * 触れない。保存期限は早めるだけで延ばさない。再実行しても重ねて変更せず、廃棄候補も重複しない。hold の解除後の実行で
 * 拾い直す。照会は keyset で進み、先頭の hold 中の副本が後ろの副本を止めない。
 *
 * <p>本 class は H2 の外側 transaction の中で動くため、副本ごとの transaction・lock・失敗の隔離は
 * {@code ResumeIngestionLedgerRetentionMySqlTest} が実 MySQL で確かめる。清掃 service が内部で読む当日と試験の当日が
 * 0時をまたいでずれないよう、清掃を通す試験は前後の日付の範囲で比べる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResumeIngestionLedgerRetentionTest {

    private static final String TENANT = "nf07-rr-a";
    private static final String OTHER_TENANT = "nf07-rr-b";
    private static final long BASE = 971_000_000L;
    private static final LocalDateTime OLD = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired
    private ResumeRetentionCleanupServiceImpl cleanup;
    @Autowired
    private ResumeIngestionLedgerRetentionService ledgerRetention;
    @Autowired
    private DocumentEligibilityService documentEligibilityService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.ses.mapper.DocumentMapper documentMapper;
    @Autowired
    private com.ses.privacy.production.retention.hold.HoldSubjectResolver holdSubjectResolver;

    @Test
    void 副本の調整の停止口は設定が無ければ有効で従来どおり副本を合わせる() {
        // app.resume.ledger-copy-alignment.enabled（FINAL-FIX (a)-3）。既定 true を実際の設定の解決で確かめる
        // @SchedulerLock の proxy の field ではなく、設定を注入した本体を読む
        Object target = AopTestUtils.getTargetObject(cleanup);
        assertThat(ReflectionTestUtils.getField(target, "ledgerCopyAlignmentEnabled")).isEqualTo(true);
    }

    @Test
    void 原本のpurge後に副本は保存期限が当日になり既存の廃棄候補作成が受け付ける() {
        long copy = resumeWithCopy(1, TENANT, OLD, "DRAFT", false);

        LocalDate before = LocalDate.now();
        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        LocalDate after = LocalDate.now();

        assertThat(jdbc.queryForObject("SELECT stored_file_name FROM t_resume_ingestion WHERE id = ?", String.class,
                BASE + 1)).as("原本は purge 済み").isNull();
        assertThat(document(copy).get("STATUS")).isEqualTo("CONFIRMED");
        assertThat(retentionUntil(copy)).isBetween(before, after);
        assertThat(documentEligibilityService.registerRetentionDisposalCandidate(TENANT, copy, after))
                .as("DocumentRetentionJob と同じ経路で廃棄候補になる").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING' AND requested_by = -1", Long.class, copy)).isEqualTo(1L);
    }

    @Test
    void legal_hold中の副本と他tenantの副本と未期限の原本の副本は変えない() {
        long held = resumeWithCopy(11, TENANT, OLD, "DRAFT", true);
        long other = resumeWithCopy(12, OTHER_TENANT, OLD, "DRAFT", false);
        long notExpired = resumeWithCopy(13, TENANT, LocalDateTime.now(), "DRAFT", false);

        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));

        assertThat(document(held).get("STATUS")).isEqualTo("DRAFT");
        assertThat(retentionUntil(held)).isNull();
        assertThat(document(other).get("STATUS")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT stored_file_name FROM t_resume_ingestion WHERE id = ?", String.class,
                BASE + 12)).as("他 tenant の原本は purge しない").isNotNull();
        assertThat(document(notExpired).get("STATUS")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT stored_file_name FROM t_resume_ingestion WHERE id = ?", String.class,
                BASE + 13)).as("期限前の原本は purge しない").isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id IN (?, ?, ?)",
                Long.class, held, other, notExpired)).isZero();
    }

    @Test
    void linkとジョブのtenantが副本と違えばpurge済みでも変えない() {
        LocalDate today = LocalDate.now();
        // 副本は TENANT、link とジョブは OTHER_TENANT（l.tenant_id = d.tenant_id が無ければ拾われる組合せ）
        long otherJob = purgedJob(41, OTHER_TENANT);
        long linkMismatch = copy(41, TENANT, "DRAFT", false, null);
        link(linkMismatch, OTHER_TENANT, otherJob);
        // 副本と link は TENANT、ジョブは OTHER_TENANT（r.tenant_id = l.tenant_id が無ければ拾われる組合せ）
        long otherJob2 = purgedJob(42, OTHER_TENANT);
        long jobMismatch = copy(42, TENANT, "DRAFT", false, null);
        link(jobMismatch, TENANT, otherJob2);
        // 対照: 副本・link・ジョブが全て TENANT
        long sameTenantJob = purgedJob(43, TENANT);
        long control = copy(43, TENANT, "DRAFT", false, null);
        link(control, TENANT, sameTenantJob);

        assertThat(ledgerRetention.alignPurgedCopies(OTHER_TENANT, today).aligned())
                .as("別 tenant の実行は TENANT の副本に触れない").isZero();
        assertThat(document(control).get("STATUS")).isEqualTo("DRAFT");

        ResumeIngestionLedgerRetentionService.Result result = ledgerRetention.alignPurgedCopies(TENANT, today);

        assertThat(result.aligned()).as("対照の1件だけを合わせる").isEqualTo(1);
        assertThat(document(control).get("STATUS")).isEqualTo("CONFIRMED");
        assertThat(retentionUntil(control)).isEqualTo(today);
        for (long unchanged : new long[] {linkMismatch, jobMismatch}) {
            assertThat(document(unchanged).get("STATUS")).isEqualTo("DRAFT");
            assertThat(retentionUntil(unchanged)).isNull();
        }
    }

    @Test
    void 保存期限は早めるだけで延ばさず版追加済みの副本も状態を変えない() {
        LocalDate today = LocalDate.now();
        long later = copyWithPurgedJob(51, "CONFIRMED", today.plusDays(10));
        long earlier = copyWithPurgedJob(52, "CONFIRMED", today.minusDays(10));
        long sameDay = copyWithPurgedJob(53, "CONFIRMED", today);
        long amended = copyWithPurgedJob(54, "AMENDED", null);
        Object earlierVersion = document(earlier).get("VERSION");
        Object sameDayVersion = document(sameDay).get("VERSION");

        ResumeIngestionLedgerRetentionService.Result result = ledgerRetention.alignPurgedCopies(TENANT, today);

        assertThat(result.aligned()).isEqualTo(2);
        assertThat(retentionUntil(later)).as("当日より後なら当日に早める").isEqualTo(today);
        assertThat(retentionUntil(earlier)).as("当日より前なら延ばさない").isEqualTo(today.minusDays(10));
        assertThat(document(earlier).get("VERSION")).isEqualTo(earlierVersion);
        assertThat(retentionUntil(sameDay)).isEqualTo(today);
        assertThat(document(sameDay).get("VERSION")).isEqualTo(sameDayVersion);
        assertThat(document(amended).get("STATUS")).as("版追加済みは状態を変えない").isEqualTo("AMENDED");
        assertThat(retentionUntil(amended)).isEqualTo(today);
        assertThat(documentEligibilityService.alignRetention(snapshot(earlier), today))
                .as("集約先の CAS も延ばさない").isFalse();
        assertThat(retentionUntil(earlier)).isEqualTo(today.minusDays(10));
    }

    @Test
    void 先頭のhold中の副本が溜まっても後ろの副本はkeysetで処理する() {
        LocalDate today = LocalDate.now();
        long held1 = copyWithPurgedJob(61, "DRAFT", null);
        long held2 = copyWithPurgedJob(62, "DRAFT", null);
        long held3 = copyWithPurgedJob(63, "DRAFT", null);
        long tail = copyWithPurgedJob(64, "DRAFT", null);
        jdbc.update("UPDATE t_document SET legal_hold_flag = 1 WHERE id IN (?, ?, ?)", held1, held2, held3);
        Object original = ReflectionTestUtils.getField(ledgerRetention, "pageSize");
        ReflectionTestUtils.setField(ledgerRetention, "pageSize", 2);
        try {
            ResumeIngestionLedgerRetentionService.Result result = ledgerRetention.alignPurgedCopies(TENANT, today);

            assertThat(result.held()).isEqualTo(3);
            assertThat(result.aligned()).as("page の大きさを超えて後ろの副本まで進む").isEqualTo(1);
            assertThat(document(tail).get("STATUS")).isEqualTo("CONFIRMED");
            assertThat(retentionUntil(tail)).isEqualTo(today);
        } finally {
            ReflectionTestUtils.setField(ledgerRetention, "pageSize", original);
        }
    }

    @Test
    void 再実行しても重ねて変えず廃棄候補も重複せずhold解除後の実行で拾い直す() {
        long copy = resumeWithCopy(21, TENANT, OLD, "DRAFT", false);
        long confirmedWithoutRetention = resumeWithCopy(22, TENANT, OLD, "CONFIRMED", false);
        long held = resumeWithCopy(23, TENANT, OLD, "DRAFT", true);
        long cancelled = resumeWithCopy(24, TENANT, OLD, "CANCELLED", false);

        LocalDate before = LocalDate.now();
        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        LocalDate today = LocalDate.now();
        assertThat(retentionUntil(confirmedWithoutRetention)).as("確定済みで保存期限が無い副本も当日に合わせる")
                .isBetween(before, today);
        assertThat(document(cancelled).get("STATUS")).as("取消済みの副本は状態を変えず保存期限だけを合わせる")
                .isEqualTo("CANCELLED");
        assertThat(retentionUntil(cancelled)).isBetween(before, today);
        assertThat(documentEligibilityService.registerRetentionDisposalCandidate(TENANT, copy, today)).isTrue();
        Object version = document(copy).get("VERSION");
        Object confirmedVersion = document(confirmedWithoutRetention).get("VERSION");

        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        assertThat(ledgerRetention.alignPurgedCopies(TENANT, today).aligned()).as("合わせ済みの副本は照会に出ない")
                .isZero();
        assertThat(document(copy).get("VERSION")).isEqualTo(version);
        assertThat(document(confirmedWithoutRetention).get("VERSION")).isEqualTo(confirmedVersion);
        assertThat(documentEligibilityService.registerRetentionDisposalCandidate(TENANT, copy, today))
                .as("廃棄候補は重複しない").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ?",
                Long.class, copy)).isEqualTo(1L);
        assertThat(document(held).get("STATUS")).isEqualTo("DRAFT");

        jdbc.update("UPDATE t_document SET legal_hold_flag = 0 WHERE id = ?", held);
        LocalDate releasedBefore = LocalDate.now();
        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        assertThat(document(held).get("STATUS")).as("hold 解除後の実行で拾い直す").isEqualTo("CONFIRMED");
        assertThat(retentionUntil(held)).isBetween(releasedBefore, LocalDate.now());
    }

    /**
     * NF07 A1H-2（F2 13af9afe delta review C-c）: 原本の purge に合わせて保存期限を早めた副本でも、SUBJECT hold の本人の副本は
     * D3（{@code DocumentRetentionJob}）で廃棄候補にならない。hold は原本の purge の後に開始された想定（原本が先に hold されて
     * いれば D4 が purge せず副本も合わせないため、D1〜D3 だけが副本を守る場合）。本人の導出は本物の {@link HoldSubjectResolver}
     * （F2 {@code DocumentFileProvider} の RESUME_INGESTION 規則）、gate の判定だけを差し替える。
     */
    @Test
    void NF07_SUBJECT_holdの本人の副本は保存期限を合わせた後もD3で廃棄候補にならず他者の副本はなる() {
        long candidateId = BASE + 9_001;
        jdbc.update("INSERT INTO t_candidate (id, tenant_id, name, contact_email, current_stage, deleted_flag) "
                + "VALUES (?, ?, '候補 保全', 'c@example.invalid', '応募受付', 0)", candidateId, TENANT);
        long heldCopy = resumeWithCopy(51, TENANT, OLD, "DRAFT", false);
        long otherCopy = resumeWithCopy(52, TENANT, OLD, "DRAFT", false);
        jdbc.update("UPDATE t_resume_ingestion SET candidate_id = ? WHERE id = ?", candidateId, BASE + 51);
        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        assertThat(document(heldCopy).get("STATUS")).as("hold 開始前に保存期限を合わせ済み").isEqualTo("CONFIRMED");
        assertThat(document(otherCopy).get("STATUS")).isEqualTo("CONFIRMED");

        com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.Stub hold =
                com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.stub().tenant(TENANT,
                        com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.verified(
                                java.util.Set.of("CANDIDATE:" + candidateId), java.util.Set.of(),
                                Map.of("DOCUMENT:RESUME_INGESTION",
                                        com.ses.privacy.production.retention.catalog.SubjectAttribution.ATTRIBUTABLE)));
        new com.ses.service.job.DocumentRetentionJob(documentMapper, documentEligibilityService,
                new com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard(hold.gate, holdSubjectResolver, null))
                .processExpiredDocuments();

        assertThat(pendingRequests(heldCopy)).as("held な本人の副本は廃棄候補にしない").isZero();
        assertThat(pendingRequests(otherCopy)).isEqualTo(1L);
    }

    private long pendingRequests(long documentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING'", Long.class, documentId);
    }

    /** 原本を持つ履歴取込ジョブと、文書台帳の副本（同じ tenant の link 付き）を作る。 */
    private long resumeWithCopy(int offset, String tenant, LocalDateTime updatedAt, String documentStatus, boolean held) {
        long jobId = BASE + offset;
        jdbc.update("INSERT INTO t_resume_ingestion (id, tenant_id, original_file_name, stored_file_name, file_ext, "
                        + "status, extracted_text, version, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'resume.pdf', ?, 'pdf', '確定済', 'text', 0, ?, ?, 0)",
                jobId, tenant, "nf07-rr-" + jobId + ".pdf", updatedAt, updatedAt);
        long documentId = copy(offset, tenant, documentStatus, held, null);
        link(documentId, tenant, jobId);
        return documentId;
    }

    /** 原本を purge 済みのジョブに同じ tenant の link を持つ副本を作る。 */
    private long copyWithPurgedJob(int offset, String status, LocalDate retentionUntil) {
        long jobId = purgedJob(offset, TENANT);
        long documentId = copy(offset, TENANT, status, false, retentionUntil);
        link(documentId, TENANT, jobId);
        return documentId;
    }

    /** 原本を purge 済み（stored_file_name なし・確定済）のジョブ。 */
    private long purgedJob(int offset, String tenant) {
        long jobId = BASE + offset;
        jdbc.update("INSERT INTO t_resume_ingestion (id, tenant_id, status, version, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, '確定済', 1, ?, ?, 0)", jobId, tenant, OLD, OLD);
        return jobId;
    }

    private long copy(int offset, String tenant, String status, boolean held, LocalDate retentionUntil) {
        long documentId = BASE + 100 + offset;
        jdbc.update("INSERT INTO t_document (id, tenant_id, document_type, title, direction, status, legal_hold_flag, "
                        + "retention_until, version, deleted_flag) "
                        + "VALUES (?, ?, 'RESUME_INGESTION', 'resume.pdf', 'INCOMING', ?, ?, ?, 1, 0)",
                documentId, tenant, status, held ? 1 : 0, retentionUntil);
        return documentId;
    }

    private void link(long documentId, String linkTenant, long jobId) {
        jdbc.update("INSERT INTO t_document_link (id, tenant_id, document_id, target_type, target_id, deleted_flag) "
                + "VALUES (?, ?, ?, 'RESUME_INGESTION', ?, 0)", documentId, linkTenant, documentId, jobId);
    }

    private Map<String, Object> document(long id) {
        return jdbc.queryForMap("SELECT status, version FROM t_document WHERE id = ?", id);
    }

    /** 集約先の writer に渡す snapshot（ID・tenant・版）。 */
    private Document snapshot(long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT tenant_id, version FROM t_document WHERE id = ?", id);
        Document document = new Document();
        document.setId(id);
        document.setTenantId((String) row.get("TENANT_ID"));
        document.setVersion(((Number) row.get("VERSION")).longValue());
        return document;
    }

    private LocalDate retentionUntil(long id) {
        return jdbc.queryForObject("SELECT retention_until FROM t_document WHERE id = ?", LocalDate.class, id);
    }
}
