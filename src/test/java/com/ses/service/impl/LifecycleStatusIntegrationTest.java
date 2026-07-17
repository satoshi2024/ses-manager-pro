package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.service.ContractService;
import com.ses.service.EngineerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 要員ステータス手動編集ガード(S5)と契約削除時の要員解放(S6)を実DB(H2)で検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class LifecycleStatusIntegrationTest {

    @Autowired
    private EngineerService engineerService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long insertEngineer(String status) {
        Engineer e = new Engineer();
        e.setFullName("要員");
        e.setEmploymentType("正社員");
        e.setStatus(status);
        engineerService.save(e);
        return e.getId();
    }

    private Long insertContract(Long engineerId, String status) {
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, engineer_id, contract_type, start_date, "
                + "selling_price, cost_price, status, deleted_flag) VALUES (?, ?, '準委任', CURRENT_DATE, 80, 60, ?, 0)",
                "C-" + System.nanoTime(), engineerId, status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_contract", Long.class);
    }

    private String engineerStatus(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM t_engineer WHERE id = ?", String.class, id);
    }

    // ===== S5 =====

    @Test
    void S5_稼動中契約が無いのに稼動中へ変更は拒否() {
        Long id = insertEngineer("Bench");
        Engineer update = new Engineer();
        update.setId(id);
        update.setStatus("稼動中");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> engineerService.updateWithStatusGuard(update));
        assertEquals("error.engineer.statusActiveNoContract", ex.getMessage());
    }

    @Test
    void S5_稼動中契約があるのにBenchへ変更は拒否() {
        Long id = insertEngineer("稼動中");
        insertContract(id, "稼動中");
        Engineer update = new Engineer();
        update.setId(id);
        update.setStatus("Bench");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> engineerService.updateWithStatusGuard(update));
        assertEquals("error.engineer.statusBenchHasContract", ex.getMessage());
    }

    @Test
    void S5_提案中への変更はガード対象外() {
        Long id = insertEngineer("Bench");
        Engineer update = new Engineer();
        update.setId(id);
        update.setStatus("提案中");
        assertTrue(engineerService.updateWithStatusGuard(update));
        assertEquals("提案中", engineerStatus(id));
    }

    @Test
    void S5_ステータス不変の項目編集は影響を受けない() {
        Long id = insertEngineer("稼動中"); // 契約が無くても、statusを変えなければ通る
        Engineer update = new Engineer();
        update.setId(id);
        update.setStatus("稼動中"); // 不変
        update.setFullName("氏名変更");
        assertTrue(engineerService.updateWithStatusGuard(update));
        assertEquals("氏名変更",
                jdbcTemplate.queryForObject("SELECT full_name FROM t_engineer WHERE id = ?", String.class, id));
    }

    // ===== S6 =====

    @Test
    void S6_準備中契約の削除で要員がBenchに戻る() {
        Long engineerId = insertEngineer("提案中");
        Long contractId = insertContract(engineerId, "準備中");

        assertTrue(contractService.removeById(contractId));

        assertEquals("Bench", engineerStatus(engineerId), "オープン提案も稼動契約も無ければBench");
    }

    @Test
    void S6_他に稼動中契約が残る要員は稼動中を維持() {
        Long engineerId = insertEngineer("稼動中");
        Long draftId = insertContract(engineerId, "準備中");
        insertContract(engineerId, "稼動中");

        assertTrue(contractService.removeById(draftId));

        assertEquals("稼動中", engineerStatus(engineerId), "稼動中契約が残るためBenchにしない");
    }
}
