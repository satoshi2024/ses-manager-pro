package com.ses.mapper;

import com.ses.dto.analytics.ContractDateRangeDto;
import com.ses.dto.analytics.EngineerCreatedAtDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analytics集計用の軽量プロジェクションSQLをH2上で検証する（P8フォローアップ・提案9）。
 * 稼動率推移の集計が全カラムロードではなく専用SQLで正しく絞り込まれることを確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class AnalyticsProjectionMapperIntegrationTest {

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Test
    void selectCreatedAtOnly_論理削除済みは除外される() {
        Engineer active = new Engineer();
        active.setFullName("稼働太郎");
        active.setEmploymentType("正社員");
        active.setStatus("稼動中");
        engineerMapper.insert(active);

        Engineer deleted = new Engineer();
        deleted.setFullName("削除済み");
        deleted.setEmploymentType("正社員");
        deleted.setStatus("Bench");
        engineerMapper.insert(deleted);
        engineerMapper.deleteById(deleted.getId());

        List<EngineerCreatedAtDto> result = engineerMapper.selectCreatedAtOnly();

        assertTrue(result.stream().anyMatch(e -> e.getId().equals(active.getId())));
        assertTrue(result.stream().noneMatch(e -> e.getId().equals(deleted.getId())));
    }

    @Test
    void selectActiveDateRanges_稼動中終了以外のステータスは除外される() {
        Contract activeContract = new Contract();
        activeContract.setEngineerId(1L);
        activeContract.setProjectId(1L);
        activeContract.setCustomerId(1L);
        activeContract.setStatus("稼動中");
        activeContract.setStartDate(LocalDate.of(2026, 1, 1));
        activeContract.setSellingPrice(java.math.BigDecimal.valueOf(80));
        activeContract.setCostPrice(java.math.BigDecimal.valueOf(60));
        contractMapper.insert(activeContract);

        Contract preparingContract = new Contract();
        preparingContract.setEngineerId(2L);
        preparingContract.setProjectId(1L);
        preparingContract.setCustomerId(1L);
        preparingContract.setStatus("準備中");
        preparingContract.setStartDate(LocalDate.of(2026, 1, 1));
        preparingContract.setSellingPrice(java.math.BigDecimal.valueOf(80));
        preparingContract.setCostPrice(java.math.BigDecimal.valueOf(60));
        contractMapper.insert(preparingContract);

        List<ContractDateRangeDto> result = contractMapper.selectActiveDateRanges();

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getEngineerId());
    }
}
