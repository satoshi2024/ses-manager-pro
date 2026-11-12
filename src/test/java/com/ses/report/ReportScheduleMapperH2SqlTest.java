package com.ses.report;

import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportScheduleMapperの専用SQLがH2（MySQL mode）でも構文解析・実行できることの高速確認。
 *
 * <p>NULL/非NULL分岐と孤児token・実行中retry leaseの選別をSQLレベルで確認するだけで、CAS競合や
 * InnoDB lockの根拠にはしない（並行性は {@code ReportScheduleClaimFenceMySqlTest} で検証する）。
 * claim serviceはREQUIRES_NEWでtest transactionの外へ出るため、ここではmapperを直接呼ぶ。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportScheduleMapperH2SqlTest {

    private static final String TENANT = "nf10-h2-sql";
    private static final LocalDateTime SEPT = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime OCT = LocalDateTime.of(2026, 10, 1, 0, 0);

    @Autowired
    private ReportScheduleMapper scheduleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertDue(LocalDateTime nextRunAt) {
        String lockKey = "nf10-h2-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO m_report_schedule (tenant_id, template_version_id, cron_expression, "
                        + "timezone_id, enabled, lock_key, next_run_at, failure_count) "
                        + "VALUES (?, 1, '0 0 0 1 * *', 'Asia/Tokyo', 1, ?, ?, 0)",
                TENANT, lockKey, nextRunAt);
        return jdbcTemplate.queryForObject("SELECT id FROM m_report_schedule WHERE tenant_id = ? AND lock_key = ?",
                Long.class, TENANT, lockKey);
    }

    private ReportSchedule only(List<ReportSchedule> due, long id) {
        return due.stream().filter(row -> row.getId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("due候補にid=" + id + "がありません"));
    }

    private int claim(ReportSchedule snapshot, String token, LocalDateTime claimedAt) {
        return scheduleMapper.claimDue(snapshot.getTenantId(), snapshot.getId(), snapshot.getNextRunAt(),
                snapshot.getRetryScheduledAt(), snapshot.getProcessingClaimedAt(), snapshot.getProcessingLogicalRunAt(),
                snapshot.getProcessingClaimToken(), snapshot.getLastRunAt(), snapshot.getFailureCount(),
                SEPT, token, claimedAt, claimedAt.minusMinutes(30));
    }

    @Test
    void claim_completion_enable更新SQLがNULLと非NULLの両分岐で実行できる() {
        long id = insertDue(SEPT);
        LocalDateTime claimedAt = SEPT.plusMinutes(1);
        ReportSchedule snapshot = only(scheduleMapper.selectDue(TENANT, claimedAt, claimedAt.minusMinutes(30), 50), id);
        String token = UUID.randomUUID().toString();

        assertThat(claim(snapshot, token, claimedAt)).isEqualTo(1);
        assertThat(claim(snapshot, UUID.randomUUID().toString(), claimedAt)).as("同じsnapshotの再claim").isZero();
        assertThat(scheduleMapper.selectById(id).getProcessingClaimToken()).isEqualTo(token);

        LocalDateTime retryAt = claimedAt.plusMinutes(5);
        assertThat(scheduleMapper.markFailure(TENANT, id, "00000000-0000-4000-8000-000000000000", claimedAt,
                retryAt, SEPT, "SCHEDULE_GENERATION_FAILED", "error.system.unexpected")).isZero();
        assertThat(scheduleMapper.markFailure(TENANT, id, token, claimedAt, retryAt, SEPT,
                "SCHEDULE_GENERATION_FAILED", "error.system.unexpected")).isEqualTo(1);
        ReportSchedule failed = scheduleMapper.selectById(id);
        assertThat(failed.getRetryScheduledAt()).isEqualTo(retryAt);
        assertThat(failed.getFailureCount()).isEqualTo(1);
        assertThat(failed.getProcessingClaimToken()).isNull();
        assertThat(failed.getProcessingLogicalRunAt()).isEqualTo(SEPT);

        LocalDateTime retryClaimAt = retryAt.plusMinutes(1);
        ReportSchedule retrySnapshot = only(scheduleMapper.selectDue(TENANT, retryClaimAt,
                retryClaimAt.minusMinutes(30), 50), id);
        String retryToken = UUID.randomUUID().toString();
        assertThat(claim(retrySnapshot, retryToken, retryClaimAt)).isEqualTo(1);
        assertThat(scheduleMapper.markSuccess(TENANT, id, token, claimedAt, OCT, SEPT)).as("旧token").isZero();
        assertThat(scheduleMapper.markSuccess(TENANT, id, retryToken, retryClaimAt, OCT, SEPT)).isEqualTo(1);
        ReportSchedule succeeded = scheduleMapper.selectById(id);
        assertThat(succeeded.getNextRunAt()).isEqualTo(OCT);
        assertThat(succeeded.getProcessingClaimedAt()).isNull();
        assertThat(succeeded.getRetryScheduledAt()).isNull();
        assertThat(succeeded.getFailureCount()).isZero();

        Integer versionBefore = succeeded.getVersion();
        assertThat(scheduleMapper.updateEnabled(TENANT, id, 0, null)).isEqualTo(1);
        ReportSchedule disabled = scheduleMapper.selectById(id);
        assertThat(disabled.getEnabled()).isZero();
        assertThat(disabled.getVersion()).isEqualTo(versionBefore + 1);
        jdbcTemplate.update("UPDATE m_report_schedule SET deleted_flag = 1 WHERE id = ?", id);
        assertThat(scheduleMapper.updateEnabled(TENANT, id, 1, 1L)).as("論理削除済み行").isZero();
    }

    @Test
    void 孤児tokenは未占有として選ばれ実行中retry_leaseはLIMIT枠を占有しない() {
        long orphanId = insertDue(SEPT);
        jdbcTemplate.update("UPDATE m_report_schedule SET processing_claim_token = ? WHERE id = ?",
                "00000000-0000-4000-8000-0000000000aa", orphanId);
        long inFlightRetryId = insertDue(SEPT);
        jdbcTemplate.update("UPDATE m_report_schedule SET retry_scheduled_at = ?, last_run_at = ?, "
                        + "processing_logical_run_at = ?, processing_claimed_at = ?, processing_claim_token = ?, "
                        + "failure_count = 1 WHERE id = ?",
                SEPT.minusMinutes(10), SEPT, SEPT, SEPT.plusMinutes(5),
                "00000000-0000-4000-8000-0000000000bb", inFlightRetryId);
        LocalDateTime now = SEPT.plusMinutes(10);

        List<ReportSchedule> due = scheduleMapper.selectDue(TENANT, now, now.minusMinutes(30), 50);

        assertThat(due).extracting(ReportSchedule::getId).contains(orphanId).doesNotContain(inFlightRetryId);
        ReportSchedule orphan = only(due, orphanId);
        assertThat(orphan.getProcessingClaimToken()).isEqualTo("00000000-0000-4000-8000-0000000000aa");
        String token = UUID.randomUUID().toString();
        assertThat(claim(orphan, token, now)).isEqualTo(1);
        assertThat(scheduleMapper.selectById(orphanId).getProcessingClaimToken()).isEqualTo(token);
    }
}
