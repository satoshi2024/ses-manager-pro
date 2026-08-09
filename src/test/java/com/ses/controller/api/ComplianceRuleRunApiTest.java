package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F2 Demo: POST /api/compliance/rules/run を2回実行し、findingが重複しない（2回目opened=0）ことを確認する。
 * 欠落profileを補完→再実行で該当findingがRESOLVEDになる（欠落解消）も確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "マネージャー")
class ComplianceRuleRunApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void ruleRunを2回実行してもfindingは重複せず欠落補完で解消する() throws Exception {
        long contractId = insertContractAndProfile();

        // 1回目: 派遣・欠落profile（抵触日/責任者/保険/明示書すべて未設定）→ MISSING_*がOPENされる
        // 共有test DBには他testのactive契約も存在しうるため、グローバル件数は下限のみ、自契約は厳密に確認する。
        mockMvc.perform(post("/api/compliance/rules/run").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.contractsEvaluated").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.opened").value(org.hamcrest.Matchers.greaterThanOrEqualTo(10)));
        int firstCount = queryInt("SELECT COUNT(*) FROM t_compliance_finding WHERE contract_id=" + contractId);
        assertEquals(10, firstCount, "欠落profileの派遣契約には抵触日2+責任者3+保険3+明示書2=10件のMISSING findingが立つはず");

        // 2回目（同一rule再実行）: opened=0で重複しない（Demo: ruleを2回実行して件数が増えない）
        mockMvc.perform(post("/api/compliance/rules/run").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opened").value(0));
        assertEquals(firstCount, queryInt("SELECT COUNT(*) FROM t_compliance_finding WHERE contract_id=" + contractId),
                "rule再実行でfinding件数が増えないはず");

        // 欠落profileを補完（2種抵触日を設定）→ 再実行で該当2件がRESOLVEDへ（欠落解消）
        jdbcTemplate.update("UPDATE t_contract_compliance_profile SET workplace_limitation_date='2029-01-01', "
                + "organization_limitation_date='2027-01-01' WHERE contract_id=" + contractId);
        mockMvc.perform(post("/api/compliance/rules/run").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opened").value(0))
                .andExpect(jsonPath("$.data.resolved").value(2));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM t_compliance_finding WHERE contract_id=" + contractId
                + " AND code IN ('MISSING_WORKPLACE_LIMITATION_DATE','MISSING_ORGANIZATION_LIMITATION_DATE')"
                + " AND status='RESOLVED'"), "補完した抵触日findingはRESOLVEDになるはず");
        assertEquals(8, queryInt("SELECT COUNT(*) FROM t_compliance_finding WHERE contract_id=" + contractId
                + " AND status='OPEN'"), "未補完のfinding（責任者/保険/明示書）はOPENのまま");
    }

    private long insertContractAndProfile() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('F2 run customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='F2 run customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('F2 run engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='F2 run engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('F2 run project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='F2 run project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price) "
                + "VALUES (?, ?, ?, '派遣', '2026-01-01', '2026-12-31', '稼動中', 100, 50)", engineerId, projectId, customerId);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
        jdbcTemplate.update("INSERT INTO m_workplace (customer_id, name, organization_unit) "
                + "VALUES (?, 'F2 run workplace', '開発部')", customerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='F2 run workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract_compliance_profile "
                + "(tenant_id, contract_id, workplace_id) VALUES ('default', ?, ?)", id, workplaceId);
        return id;
    }

    private int queryInt(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
