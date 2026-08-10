package com.ses.migration;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.compliance.ComplianceRuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T066 M: 法務fixture（3契約）の台帳・finding golden照合（L4）。
 *  - 派遣契約: 欠落profile（抵触日/責任者/保険/明示書未設定）→ MISSING系10件
 *  - 準委任契約: 指示経路未設定 → MISSING_INSTRUCTION_ROUTE
 *  - 請負契約: 指示経路未設定 → MISSING_INSTRUCTION_ROUTE
 *  - 既存4 ruleの出力はLaborComplianceServiceImplTestのgolden 12/12が維持する
 *    （本fixtureはrule実行の実DB経路で、4 ruleを含む全ruleの出力を契約単位で固定する）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class ComplianceLegalFixtureTest {

    @Autowired
    private ComplianceRuleEngine complianceRuleEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(organizationScopeService.hasFullAccess()).thenReturn(true);
    }

    @Test
    void 派遣契約の法務fixtureでMISSING系10件のfindingがgoldenどおり出る() {
        long contractId = insertContractWithProfile("派遣", "legacy-dispatch", "開発部", null);

        List<ComplianceFinding> findings = complianceRuleEngine.evaluate(contractById(contractId));

        assertThat(findings).extracting(ComplianceFinding::getCode)
                .contains("MISSING_WORKPLACE_LIMITATION_DATE", "MISSING_ORGANIZATION_LIMITATION_DATE",
                        "MISSING_COMMAND_PERSON", "MISSING_CLIENT_RESPONSIBLE", "MISSING_DISPATCH_RESPONSIBLE",
                        "MISSING_INSURANCE_CONFIRMATION", "MISSING_DOCUMENT_DELIVERY");
        assertEquals(10, findings.size(), "抵触日2+責任者3+保険3+明示書2=10件");
        // 既存4 ruleは本fixtureでは発火しない（BP tier 0・direct command無し・二重派遣無し・精算範囲正常）
        assertThat(findings).extracting(ComplianceFinding::getCode)
                .doesNotContain("TIER_EXCEEDED", "DIRECT_COMMAND", "DOUBLE_DISPATCH", "SETTLEMENT_MISMATCH");
    }

    @Test
    void 準委任と請負の法務fixtureで指示経路MISSINGが出る() {
        long junin = insertContractWithProfile("準委任", "legacy-junin", null, null);
        long sekyu = insertContractWithProfile("請負", "legacy-sekyu", null, null);

        List<ComplianceFinding> juninFindings = complianceRuleEngine.evaluate(contractById(junin));
        assertThat(juninFindings).extracting(ComplianceFinding::getCode)
                .contains("MISSING_INSTRUCTION_ROUTE");

        List<ComplianceFinding> sekyuFindings = complianceRuleEngine.evaluate(contractById(sekyu));
        assertThat(sekyuFindings).extracting(ComplianceFinding::getCode)
                .contains("MISSING_INSTRUCTION_ROUTE");
    }

    @Test
    void 既存4ruleは法務fixtureのBP階層超過でTIER_EXCEEDEDを出力する() {
        long contractId = insertContractWithProfile("派遣", "legacy-tier", "開発部", null);
        // 実績（work_record）配下にBP階層4（上限3を超過）→ TIER_EXCEEDED
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours) "
                + "VALUES (?, '2026-08', 160)", contractId);
        Long workRecordId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_work_record WHERE contract_id=?", Long.class, contractId);
        jdbcTemplate.update("INSERT INTO t_bp_payment (work_record_id, layer_order, amount, payee_company_name) "
                + "VALUES (?, 4, 1000, 'BP会社')", workRecordId);

        List<ComplianceFinding> findings = complianceRuleEngine.evaluate(contractById(contractId));
        assertThat(findings).extracting(ComplianceFinding::getCode)
                .contains("TIER_EXCEEDED");
    }

    // ===== データ準備 =====

    private long insertContractWithProfile(String contractType, String prefix, String orgUnit, Long ignored) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('" + prefix + " customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='" + prefix + " customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('"
                + prefix + " engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='" + prefix + " engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('" + prefix + " project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='" + prefix + " project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price) "
                + "VALUES (?, ?, ?, ?, '2026-01-01', '2026-12-31', '稼動中', 100, 50)",
                engineerId, projectId, customerId, contractType);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
        if (orgUnit != null) {
            jdbcTemplate.update("INSERT INTO m_workplace (customer_id, name, organization_unit) "
                    + "VALUES (?, '" + prefix + " workplace', ?)", customerId, orgUnit);
            Long workplaceId = jdbcTemplate.queryForObject(
                    "SELECT id FROM m_workplace WHERE name='" + prefix + " workplace'", Long.class);
            jdbcTemplate.update("INSERT INTO t_contract_compliance_profile "
                    + "(tenant_id, contract_id, workplace_id) VALUES ('default', ?, ?)", contractId, workplaceId);
        } else {
            jdbcTemplate.update("INSERT INTO t_contract_compliance_profile "
                    + "(tenant_id, contract_id) VALUES ('default', ?)", contractId);
        }
        return contractId;
    }

    private com.ses.entity.Contract contractById(long contractId) {
        return jdbcTemplate.queryForObject(
                "SELECT id, engineer_id, customer_id, contract_type, start_date, end_date, status, direct_command_flag "
                        + "FROM t_contract WHERE id=" + contractId,
                (rs, rowNum) -> {
                    com.ses.entity.Contract c = new com.ses.entity.Contract();
                    c.setId(rs.getLong("id"));
                    c.setEngineerId(rs.getLong("engineer_id"));
                    c.setCustomerId(rs.getLong("customer_id"));
                    c.setContractType(rs.getString("contract_type"));
                    c.setStartDate(rs.getDate("start_date").toLocalDate());
                    c.setEndDate(rs.getDate("end_date").toLocalDate());
                    c.setStatus(rs.getString("status"));
                    c.setDirectCommandFlag(rs.getInt("direct_command_flag") != 0);
                    return c;
                });
    }

    @SuppressWarnings("unused")
    private String codes(List<ComplianceFinding> findings) {
        return findings.stream().map(ComplianceFinding::getCode).collect(Collectors.joining(","));
    }
}
