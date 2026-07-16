package com.ses.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.analytics.ContractDateRangeDto;
import com.ses.dto.analytics.EngineerCreatedAtDto;
import com.ses.dto.contract.ContractListDto;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 契約一覧の担当営業join(sys_user)が実DBで実行できることを検証する。
     * sys_user の氏名カラムは real_name であり、full_name を参照すると
     * 「Unknown column」でランタイムエラーになる。単体テストは本SQLをモックするため
     * 素のSQLで結合先カラム名の誤りを検出できるのはH2実行の本テストのみ。
     */
    @Test
    void selectPageWithNames_担当営業joinが実行でき営業名を取得できる() {
        Contract contract = new Contract();
        contract.setEngineerId(1L);
        contract.setProjectId(1L);
        contract.setCustomerId(1L);
        contract.setSalesUserId(1L); // engineer-schema-h2.sql が seed する admin(id=1)
        contract.setStatus("稼動中");
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setSellingPrice(java.math.BigDecimal.valueOf(80));
        contract.setCostPrice(java.math.BigDecimal.valueOf(60));
        contractMapper.insert(contract);

        Page<ContractListDto> page = contractMapper.selectPageWithNames(
                new Page<>(1, 10), null, null, null, null, null, null, null, null, null);

        assertTrue(page.getRecords().stream().anyMatch(r -> r.getId().equals(contract.getId())));
        ContractListDto row = page.getRecords().stream()
                .filter(r -> r.getId().equals(contract.getId())).findFirst().orElseThrow();
        assertEquals(1L, row.getSalesUserId());
        assertNotNull(row.getSalesUserName(), "担当営業名が取得できること");

        // salesUserId 絞り込みが機能すること
        Page<ContractListDto> filtered = contractMapper.selectPageWithNames(
                new Page<>(1, 10), null, null, null, null, null, null, null, 1L, null);
        assertTrue(filtered.getRecords().stream().allMatch(r -> Long.valueOf(1L).equals(r.getSalesUserId())));
    }

    /**
     * インセンティブ個別設定を NULL に更新すると「既定」に戻せることを検証する。
     * グローバルの update-strategy=not_null では NULL 更新が無視されるため、
     * Contract の該当3フィールドは updateStrategy=ALWAYS で上書きしている。
     * 本テストが無いと「既定に戻せない」不具合を検出できない。
     */
    @Test
    void 契約更新_インセンティブ個別設定をNULLで既定に戻せる() {
        Contract contract = new Contract();
        contract.setEngineerId(1L);
        contract.setProjectId(1L);
        contract.setCustomerId(1L);
        contract.setSalesUserId(1L);
        contract.setCommissionBaseType("売上");
        contract.setCommissionRate(new java.math.BigDecimal("8.00"));
        contract.setStatus("稼動中");
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setSellingPrice(java.math.BigDecimal.valueOf(80));
        contract.setCostPrice(java.math.BigDecimal.valueOf(60));
        contractMapper.insert(contract);

        // 個別設定を「既定に戻す」= NULL 更新
        Contract update = new Contract();
        update.setId(contract.getId());
        update.setSalesUserId(null);
        update.setCommissionBaseType(null);
        update.setCommissionRate(null);
        contractMapper.updateById(update);

        Contract reloaded = contractMapper.selectById(contract.getId());
        org.junit.jupiter.api.Assertions.assertNull(reloaded.getCommissionBaseType(), "基準がNULLに戻ること");
        org.junit.jupiter.api.Assertions.assertNull(reloaded.getCommissionRate(), "率がNULLに戻ること");
        org.junit.jupiter.api.Assertions.assertNull(reloaded.getSalesUserId(), "担当営業がNULLに戻ること");
    }
}
