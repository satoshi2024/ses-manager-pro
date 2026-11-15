package com.ses.service.ai.copilot.dispatch;

import com.ses.entity.AiExternalSendCostBucket;
import com.ses.entity.AiExternalSendCostEntry;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiExternalSendCostBucketMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.PersistentExternalSendCostLedger;
import com.ses.service.ai.PersistentExternalSendCostLedger.BucketKeys;
import com.ses.service.ai.PersistentExternalSendCostLedger.ReserveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF08 B2c-1: V194のH2再生とentity / mapper SQLの対応（テストTX内でrollbackする）。
 * <p>H2は型・SQLの対応・分岐だけを確かめる。競合・lock・一意制約の並行挙動・commit不明はMySQL gate
 * （{@code CopilotDispatchClaimMySqlIntegrationTest}）で証拠化する。claimはtransaction外からしか呼べないため、
 * ここではテストTX内の拒否（R-2）とSQL単位の動作を見る。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CopilotDispatchClaimH2Test {

    private static final String TENANT = "tenant-nf08-h2";

    @Autowired private CopilotDispatchClaimService claimService;
    @Autowired private PersistentExternalSendCostLedger ledger;
    @Autowired private AiRecommendationRunMapper runMapper;
    @Autowired private AiExternalSendCostBucketMapper bucketMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private long artifactId;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        String useCase = "NF08H2" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbcTemplate.update("INSERT INTO m_ai_artifact_version (use_case, provider, model_name, prompt_version, "
                + "rule_version, config_hash, status, status_version) VALUES (?, 'gemini', 'gemini-2.5-flash', "
                + "'nf08-f1', 'catalog-v1', ?, 'SHADOW', 0)", useCase, DispatchClaimFixtures.CONFIG_HASH);
        artifactId = jdbcTemplate.queryForObject("SELECT id FROM m_ai_artifact_version WHERE use_case = ?",
                Long.class, useCase);
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 既存行はNOT_APPLICABLEで汎用insertとupdateByIdはdispatch列を書かない() {
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTenantId(TENANT);
        run.setTraceId(UUID.randomUUID().toString());
        run.setUseCase("MANAGEMENT_COPILOT");
        run.setArtifactVersionId(artifactId);
        run.setInputHash(DispatchClaimFixtures.CONFIG_HASH);
        run.setStatus("PENDING");
        run.setStatusVersion(0);
        run.setDispatchState("CLAIMED");
        run.setDispatchClaimId(new byte[16]);
        runMapper.insert(run);

        AiRecommendationRun loaded = runMapper.selectById(run.getId());
        assertEquals("NOT_APPLICABLE", loaded.getDispatchState(), "insertはdispatch列を書かずDB既定値になる");
        assertNull(loaded.getDispatchClaimId());
        assertNull(loaded.getDispatchIssuedAt());

        loaded.setStatus("FAILED");
        loaded.setDispatchState("READY");
        loaded.setDispatchNonceHash(new byte[32]);
        runMapper.updateById(loaded);
        AiRecommendationRun updated = runMapper.selectById(run.getId());
        assertEquals("FAILED", updated.getStatus());
        assertEquals("NOT_APPLICABLE", updated.getDispatchState(), "updateByIdはclaim状態を書けない");
        assertNull(updated.getDispatchNonceHash());
    }

    @Test
    void READY化はclaim_IDをUUID_TO_BINで保存しBIN_TO_UUIDの36文字表記と全列照合が一致する() {
        RunRef run = insertSummaryRun();
        DispatchTicket ticket = prepare(run);

        DispatchRunRow row = runMapper.lockDispatchRow(ticket.columns());
        assertEquals(List.of(), ticket.mismatches(row));
        assertEquals(ticket.claimId(), row.getDispatchClaimIdText());
        byte[] stored = jdbcTemplate.queryForObject("SELECT dispatch_claim_id FROM t_ai_recommendation_run WHERE id = ?",
                byte[].class, run.id());
        UUID uuid = UUID.fromString(ticket.claimId());
        assertArrayEquals(ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array(), stored, "swap_flag=0のbyte順");
        AiRecommendationRun entity = runMapper.selectById(run.id());
        assertEquals("READY", entity.getDispatchState());
        assertArrayEquals(stored, entity.getDispatchClaimId());
        assertEquals(LocalDateTime.ofInstant(ticket.issuedAt(), ZoneOffset.UTC), entity.getDispatchIssuedAt());

        assertThrows(IllegalStateException.class, () -> claimService.prepareReady(run.attempt(),
                DispatchClaimFixtures.request(artifactId, Instant.now(), System.nanoTime())),
                "READY化は一度だけ");
    }

    @Test
    void claim_CASは一度だけ成功し発行時bindingの一列でも違えば0件() {
        RunRef run = insertSummaryRun();
        DispatchTicket ticket = prepare(run);
        DispatchIssueRequest base = ticket.request();
        DispatchIssueRequest otherTier = new DispatchIssueRequest(base.provider(), base.modelRevision(), base.region(),
                "priority", base.configHash(), base.artifactVersionId(), base.scopeHash(), base.inputHash(),
                base.preflightSnapshotHash(), base.requestSha256(), base.approvalDigest(), base.decisionId(),
                base.cardId(), base.cardVersion(), base.cardDigest(), base.cardEffectiveFrom(), base.cardEffectiveUntil(),
                base.policyId(), base.policyVersion(), base.policyDigest(), base.destinationHost(), base.queryId(),
                base.legalEntityId(), base.promptBytes(), base.costScope(), base.upperBoundYen(), base.dailyLimitYen(),
                base.monthlyLimitYen(), base.attemptTtl(), base.deadlineNanos());
        DispatchTicket drifted = new DispatchTicket(ticket.runId(), ticket.tenantId(), ticket.traceId(),
                ticket.claimId(), otherTier, ticket.nonce(), ticket.idempotencyKey(), ticket.issuedAt(),
                ticket.issuedNanos());
        DispatchTicket otherClaim = new DispatchTicket(ticket.runId(), ticket.tenantId(), ticket.traceId(),
                UUID.randomUUID().toString(), base, ticket.nonce(), ticket.idempotencyKey(), ticket.issuedAt(),
                ticket.issuedNanos());
        LocalDateTime now = LocalDateTime.ofInstant(ticket.issuedAt(), ZoneOffset.UTC);

        assertEquals(0, runMapper.claimDispatch(drifted.columns(), now));
        assertEquals(0, runMapper.claimDispatch(otherClaim.columns(), now));
        assertEquals("READY", stateOf(run.id()));
        assertEquals(1, runMapper.claimDispatch(ticket.columns(), now));
        assertEquals(0, runMapper.claimDispatch(ticket.columns(), now), "CLAIMEDは再claimできない");
        assertEquals("CLAIMED", stateOf(run.id()));
        assertEquals(List.of("dispatch_state", "dispatch_claimed_at"),
                ticket.mismatches(runMapper.readDispatchRow(ticket.columns())));
    }

    @Test
    void claimはテストTXのような実transaction内では何も変えずに拒否する() {
        RunRef run = insertSummaryRun();
        DispatchTicket ticket = prepare(run);
        long audits = auditCount(ticket.claimId());

        ClaimOutcome outcome = claimService.claim(ticket);

        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, outcome.status());
        assertEquals(CopilotDispatchClaimService.REASON_TRANSACTION_ACTIVE, outcome.reasonCode());
        assertNull(outcome.permit());
        assertEquals("READY", stateOf(run.id()));
        assertEquals(audits, auditCount(ticket.claimId()));
    }

    @Test
    void bucketの事前生成は冪等で上限内だけ予約し高水位は増える方向にだけ動く() {
        String scope = "nf08-h2-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.parse("2026-11-15T01:00:00.123456Z");
        BucketKeys keys = BucketKeys.of(now);
        ledger.ensureBuckets(scope, keys);
        ledger.ensureBuckets(scope, keys);
        assertEquals(2, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ai_external_send_cost_bucket WHERE cost_scope = ?", Integer.class, scope));
        assertNull(ledger.clockHighWater(scope));

        byte[] digest = new byte[32];
        Duration tolerance = Duration.ofSeconds(1);
        assertEquals(ReserveResult.RESERVED, ledger.reserve("DAY", scope, keys.dayKey(), 60, 100, digest, now, tolerance));
        assertEquals(ReserveResult.RESERVED,
                ledger.reserve("DAY", scope, keys.dayKey(), 40, 100, digest, now.minusMillis(500), tolerance),
                "高水位から1秒以内の前の時刻は許す");
        assertEquals(ReserveResult.LIMIT_EXCEEDED,
                ledger.reserve("DAY", scope, keys.dayKey(), 1, 100, digest, now, tolerance), "上限ちょうどの後は超過");
        assertEquals(ReserveResult.CLOCK_INVALID,
                ledger.reserve("DAY", scope, keys.dayKey(), 1, 1000, digest, now.minusSeconds(2), tolerance));
        assertEquals(ReserveResult.BUCKET_MISSING, ledger.reserve("DAY", scope, "2026-11-16", 1, 100, digest, now,
                tolerance));
        AiExternalSendCostBucket day = bucketMapper.selectBucket(scope, "DAY", keys.dayKey());
        assertEquals(100L, day.getCommittedYen());
        assertEquals(2L, day.getVersion(), "予約ごとにversionを増やす");
        assertEquals(100L, day.getLastLimitYen());
        assertEquals(LocalDateTime.ofInstant(now, ZoneOffset.UTC), day.getClockHighWaterAt(), "高水位を下げない");
        assertEquals(now, ledger.clockHighWater(scope));
        assertEquals(100L, (long) jdbcTemplate.queryForObject("SELECT committed_yen FROM t_ai_external_send_cost_bucket "
                + "WHERE cost_scope = ? AND bucket_type = 'DAY'", Long.class, scope));
    }

    @Test
    void 予約entryはclaim_IDを予約IDとして36文字表記で読み戻し同じIDの二重作成を拒否する() {
        String reservationId = UUID.randomUUID().toString();
        AiExternalSendCostEntry entry = new AiExternalSendCostEntry();
        entry.setReservationId(reservationId);
        entry.setTenantId(TENANT);
        entry.setRunId(1L);
        entry.setTraceId(UUID.randomUUID().toString());
        entry.setCostScope("gemini");
        entry.setDayKey("2026-11-15");
        entry.setMonthKey("2026-11");
        entry.setDecisionId("NF08-EXT-2026-0001");
        entry.setApprovalDigest(new byte[32]);
        entry.setReservedYen(30);
        entry.setCreatedAt(LocalDateTime.of(2026, 11, 15, 1, 0));
        ledger.recordReservation(entry);

        AiExternalSendCostEntry loaded = ledger.findReservation(reservationId);
        assertNotNull(loaded);
        assertEquals(reservationId, loaded.getReservationId());
        assertEquals(AiExternalSendCostEntry.STATE_RESERVED, loaded.getEntryState());
        assertEquals(0, loaded.getStatusVersion());
        assertNull(loaded.getSettledYen());
        assertThrows(DuplicateKeyException.class, () -> ledger.recordReservation(entry));
    }

    @Test
    void H2のUUID変換aliasはswap_flag0だけを受け付けMySQLと同じbyte順で往復する() {
        String text = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
        byte[] binary = jdbcTemplate.queryForObject("SELECT UUID_TO_BIN(?, 0)", byte[].class, text);
        assertArrayEquals(java.util.HexFormat.of().parseHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0"), binary);
        assertEquals(text, jdbcTemplate.queryForObject("SELECT BIN_TO_UUID(UUID_TO_BIN(?, 0), 0)", String.class, text));
        assertThrows(RuntimeException.class,
                () -> jdbcTemplate.queryForObject("SELECT UUID_TO_BIN(?, 1)", byte[].class, text));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.queryForObject("SELECT UUID_TO_BIN(?, 0)",
                byte[].class, text.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void 照合索引と監査の存在照会索引をMySQLと同じ列順で再現する() throws Exception {
        assertEquals(List.of("TENANT_ID", "DISPATCH_STATE", "STATUS", "DISPATCH_EXPIRES_AT", "ID"),
                indexColumns("T_AI_RECOMMENDATION_RUN", "IDX_AI_RUN_DISPATCH_RECONCILE"));
        assertEquals(List.of("IDEMPOTENCY_KEY", "APPLICATION_CODE", "ID"),
                indexColumns("T_AUDIT_LOG", "IDX_AUDIT_DISPATCH_KEY"));
        assertEquals(List.of("DISPATCH_CLAIM_ID"), indexColumns("T_AI_RECOMMENDATION_RUN", "UK_AI_RUN_DISPATCH_CLAIM"));
    }

    private DispatchTicket prepare(RunRef run) {
        return claimService.prepareReady(run.attempt(),
                DispatchClaimFixtures.request(artifactId, Instant.now(), System.nanoTime()));
    }

    private RunRef insertSummaryRun() {
        String traceId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO t_ai_recommendation_run (tenant_id, trace_id, use_case, artifact_version_id, "
                        + "input_hash, redacted_summary_json, status, status_version) "
                        + "VALUES (?, ?, 'MANAGEMENT_COPILOT', ?, ?, '{}', 'PENDING', 0)",
                TENANT, traceId, artifactId, DispatchClaimFixtures.CONFIG_HASH);
        long id = jdbcTemplate.queryForObject("SELECT id FROM t_ai_recommendation_run WHERE trace_id = ?",
                Long.class, traceId);
        return new RunRef(id, DispatchClaimFixtures.summaryAttempt(id, traceId, TENANT, artifactId));
    }

    private String stateOf(long runId) {
        return jdbcTemplate.queryForObject("SELECT dispatch_state FROM t_ai_recommendation_run WHERE id = ?",
                String.class, runId);
    }

    private long auditCount(String claimId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE idempotency_key = ?",
                Long.class, claimId);
    }

    private List<String> indexColumns(String table, String index) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            List<int[]> order = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (rows.next()) {
                if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                    order.add(new int[]{rows.getShort("ORDINAL_POSITION"), names.size()});
                    names.add(rows.getString("COLUMN_NAME").toUpperCase(java.util.Locale.ROOT));
                }
            }
            order.sort(java.util.Comparator.comparingInt(position -> position[0]));
            for (int[] position : order) {
                columns.add(names.get(position[1]));
            }
        }
        assertTrue(!columns.isEmpty(), table + "." + index + " が無い");
        return columns;
    }

    private record RunRef(long id, com.ses.service.ai.copilot.CopilotRunService.CopilotAttemptRecord attempt) { }
}
