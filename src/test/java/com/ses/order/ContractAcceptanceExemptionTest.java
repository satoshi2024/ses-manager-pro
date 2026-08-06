package com.ses.order;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.service.ContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R09-P1-01定向テスト: 検収不要契約は理由付きで可能（R3.3/R5）。
 * acceptance_required=false に理由なしは拒否、trueへ戻すと理由をクリア。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class ContractAcceptanceExemptionTest {

    @Autowired ContractService contractService;
    @Autowired JdbcTemplate jdbcTemplate;

    private long customerId;
    private long engineerId;
    private long projectId;

    @BeforeEach
    void setUp() {
        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "EX顧客" + suffix);
        customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "EX顧客" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", "EX要員" + suffix);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "EX要員" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", "EX案件" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, "EX案件" + suffix);
    }

    private Contract baseContract() {
        Contract c = new Contract();
        c.setContractNo("EX-C-" + System.nanoTime());
        c.setEngineerId(engineerId);
        c.setProjectId(projectId);
        c.setCustomerId(customerId);
        c.setStartDate(LocalDate.of(2026, 1, 1));
        c.setSellingPrice(new BigDecimal("600000"));
        c.setCostPrice(new BigDecimal("300000"));
        c.setContractType("準委任");
        return c;
    }

    @Test
    @DisplayName("検収不要（false）に理由なしは拒否、理由付きは登録できる")
    void exemptionRequiresReason() {
        Contract noReason = baseContract();
        noReason.setAcceptanceRequired(false);
        assertThrows(BusinessException.class, () -> contractService.saveWithBusinessRules(noReason),
                "acceptance_required=falseなのに理由が無い契約は拒否される（R3.3）");

        Contract withReason = baseContract();
        withReason.setAcceptanceRequired(false);
        withReason.setAcceptanceExemptionReason("短期検証のため検収不要（R09-P1-01）");
        contractService.saveWithBusinessRules(withReason);
        assertNotNull(withReason.getId());
        Contract reloaded = contractService.getById(withReason.getId());
        assertFalse(reloaded.getAcceptanceRequired());
        assertTrue(reloaded.getAcceptanceExemptionReason().contains("短期検証"));
    }

    @Test
    @DisplayName("検収要（true）に戻すと理由はクリアされる")
    void revertToRequiredClearsReason() {
        Contract c = baseContract();
        c.setAcceptanceRequired(false);
        c.setAcceptanceExemptionReason("旧理由");
        contractService.saveWithBusinessRules(c);

        Contract reverted = baseContract();
        reverted.setId(c.getId());
        reverted.setAcceptanceRequired(true);
        contractService.updateWithBusinessRules(reverted);
        Contract reloaded = contractService.getById(c.getId());
        assertNull(reloaded.getAcceptanceExemptionReason(), "検収要に戻すと理由はクリアされる");
    }
}
