package com.ses.mapper;

import com.ses.dto.accounting.AccountingWaitCostSnapshotRow;
import com.ses.dto.accounting.AccountingWaitCostRow;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountingHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 待機原価の帰属・金額が「対象月時点」で解決されることを検証する。
 *
 * <p>月次締めが遅れている間に要員の原価部門・想定単価を変更すると、
 * 現在値を読む実装では確定済み扱いの過去月へ新しい値が焼き付く（第十三次Review P1-5）。
 * snapshotは訂正しない運用のため、誤りがそのまま永続化してしまう。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class EngineerAccountingHistoryMapperTest {

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerAccountingHistoryMapper historyMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 待機原価は対象月時点の原価部門と想定単価で解決される() {
        Engineer engineer = Engineer.builder()
                .fullName("待機要員").employmentType("正社員").status("Bench")
                .costCenterId(200L)
                .expectedUnitPrice(new BigDecimal("900000"))
                .build();
        engineerMapper.insert(engineer);

        // 6月時点: 原価部門100 / 単価600,000
        historyMapper.insert(EngineerAccountingHistory.builder()
                .engineerId(engineer.getId()).organizationId(100L).costCenterId(100L)
                .expectedUnitPrice(new BigDecimal("600000"))
                .validFrom(LocalDate.of(2026, 1, 1)).validTo(LocalDate.of(2026, 6, 30))
                .build());
        // 7月以降: 原価部門200 / 単価900,000（＝要員マスタの現在値）
        historyMapper.insert(EngineerAccountingHistory.builder()
                .engineerId(engineer.getId()).organizationId(200L).costCenterId(200L)
                .expectedUnitPrice(new BigDecimal("900000"))
                .validFrom(LocalDate.of(2026, 7, 1)).validTo(null)
                .build());

        List<AccountingWaitCostSnapshotRow> june = engineerMapper.selectAccountingWaitCostByEngineer(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        AccountingWaitCostSnapshotRow juneRow = june.stream()
                .filter(row -> engineer.getId().equals(row.getEngineerId())).findFirst().orElse(null);
        assertNotNull(juneRow, "Bench要員は待機原価の対象になる");
        assertEquals(100L, juneRow.getCostCenterId(),
                "7月に異動しても6月の原価部門は変わらない");
        assertEquals(0, new BigDecimal("600000").compareTo(juneRow.getWaitCost()),
                "7月に単価改定しても6月の待機原価は変わらない");
        AccountingWaitCostRow juneAggregate = engineerMapper.selectAccountingWaitCost(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), true,
                        List.of(), null, null, null).stream()
                .filter(row -> Long.valueOf(100L).equals(row.getOrganizationId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("600000").compareTo(juneAggregate.getWaitCost()),
                "集計SQLも対象月の組織・単価を使う");

        List<AccountingWaitCostSnapshotRow> july = engineerMapper.selectAccountingWaitCostByEngineer(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        AccountingWaitCostSnapshotRow julyRow = july.stream()
                .filter(row -> engineer.getId().equals(row.getEngineerId())).findFirst().orElse(null);
        assertNotNull(julyRow);
        assertEquals(200L, julyRow.getCostCenterId(), "7月は改定後の原価部門");
        assertEquals(0, new BigDecimal("900000").compareTo(julyRow.getWaitCost()), "7月は改定後の単価");
    }

    /** 履歴が無い要員は要員マスタの現在値へフォールバックし、集計から落とさない。 */
    @Test
    void 履歴が無い要員は現在値にフォールバックする() {
        Engineer engineer = Engineer.builder()
                .fullName("履歴なし要員").employmentType("正社員").status("Bench")
                .costCenterId(300L)
                .expectedUnitPrice(new BigDecimal("500000"))
                .build();
        engineerMapper.insert(engineer);

        List<AccountingWaitCostSnapshotRow> rows = engineerMapper.selectAccountingWaitCostByEngineer(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        AccountingWaitCostSnapshotRow row = rows.stream()
                .filter(item -> engineer.getId().equals(item.getEngineerId())).findFirst().orElse(null);
        assertNotNull(row, "履歴が無くても集計対象から落とさない");
        assertEquals(300L, row.getCostCenterId());
        assertEquals(0, new BigDecimal("500000").compareTo(row.getWaitCost()));
    }

    /** 要員自身の履歴を優先し、復元不能なclosed行は連携アカウントへ推測フォールバックしない。 */
    @Test
    void 組織帰属は要員履歴優先で復元不能closed行は未配賦になる() {
        Engineer engineer = Engineer.builder()
                .fullName("組織帰属要員").employmentType("正社員").status("Bench")
                .organizationId(101L).costCenterId(301L)
                .expectedUnitPrice(new BigDecimal("400000"))
                .build();
        engineerMapper.insert(engineer);
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer_account_link (engineer_id, sys_user_id) VALUES (?, ?)",
                engineer.getId(), adminId);
        jdbcTemplate.update("INSERT INTO t_user_organization "
                        + "(user_id, organization_id, primary_flag, valid_from) VALUES (?, ?, 1, ?)",
                adminId, 202L, LocalDate.of(2026, 1, 1));

        AccountingWaitCostSnapshotRow direct = engineerMapper.selectAccountingWaitCostByEngineer(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).stream()
                .filter(row -> engineer.getId().equals(row.getEngineerId())).findFirst().orElseThrow();
        assertEquals(101L, direct.getOrganizationId(), "履歴が無い場合も要員直所属をaccount-linkより優先する");

        historyMapper.insert(EngineerAccountingHistory.builder()
                .engineerId(engineer.getId()).organizationId(303L).costCenterId(301L)
                .expectedUnitPrice(new BigDecimal("400000"))
                .validFrom(LocalDate.of(2026, 1, 1)).validTo(LocalDate.of(2026, 6, 30))
                .build());
        AccountingWaitCostSnapshotRow known = engineerMapper.selectAccountingWaitCostByEngineer(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).stream()
                .filter(row -> engineer.getId().equals(row.getEngineerId())).findFirst().orElseThrow();
        assertEquals(303L, known.getOrganizationId(), "要員自身の対象月履歴を優先する");

        historyMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerAccountingHistory>()
                .eq(EngineerAccountingHistory::getEngineerId, engineer.getId()));
        jdbcTemplate.update("INSERT INTO t_engineer_accounting_history "
                        + "(engineer_id, organization_id, organization_history_status, cost_center_id, "
                        + "expected_unit_price, valid_from, valid_to) VALUES (?, NULL, 'UNKNOWN', ?, ?, ?, ?)",
                engineer.getId(), 301L, new BigDecimal("400000"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        AccountingWaitCostSnapshotRow unknown = engineerMapper.selectAccountingWaitCostByEngineer(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).stream()
                .filter(row -> engineer.getId().equals(row.getEngineerId())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(unknown.getOrganizationId(),
                "復元不能なclosed行はaccount-link fallbackせず未配賦にする");
        AccountingWaitCostRow unknownAggregate = engineerMapper.selectAccountingWaitCost(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), true,
                        List.of(), null, null, null).stream()
                .filter(row -> row.getOrganizationId() == null).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("400000").compareTo(unknownAggregate.getWaitCost()),
                "未配賦のclosed行も集計から消さない");
    }
}
