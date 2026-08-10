package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T065 B2: findingの対応状態遷移API（ack/in-progress/resolve/exception）。
 *  - 遷移: OPEN→ACKNOWLEDGED→IN_PROGRESS→RESOLVED / EXCEPTION_APPROVED（有効期限付き）
 *  - resolve/exceptionは根拠note必須、exceptionは未来のexpiresAt必須
 *  - 不正遷移400・営業403・契約不一致404
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@WithMockUser(username = "1", roles = "管理者")
class ComplianceFindingActionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(organizationScopeService.hasFullAccess()).thenReturn(true);
    }

    @Test
    void ackとinProgressとresolveで状態が遷移する() throws Exception {
        long contractId = insertContract();
        long findingId = insertFinding(contractId, "T065_ACK");

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/ack").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("ACKNOWLEDGED", statusOf(findingId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT acknowledged_by FROM t_compliance_finding WHERE id=" + findingId, Long.class));

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/in-progress").with(csrf()))
                .andExpect(status().isOk());
        assertEquals("IN_PROGRESS", statusOf(findingId));

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/resolve")
                        .with(csrf()).contentType("application/json")
                        .content("{\"note\":\"対応完了\",\"evidenceDocumentId\":null}"))
                .andExpect(status().isOk());
        assertEquals("RESOLVED", statusOf(findingId));
        assertEquals("対応完了", jdbcTemplate.queryForObject(
                "SELECT resolution_note FROM t_compliance_finding WHERE id=" + findingId, String.class));
    }

    @Test
    void resolveはnote必須で解消済みへのackは400() throws Exception {
        long contractId = insertContract();
        long findingId = insertFinding(contractId, "T065_NOTE");

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/resolve")
                        .with(csrf()).contentType("application/json")
                        .content("{\"note\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/resolve")
                        .with(csrf()).contentType("application/json")
                        .content("{\"note\":\"解消\"}"))
                .andExpect(status().isOk());

        // RESOLVEDからのackは遷移不可
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/ack").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exceptionはexpiresAt必須で未来日時ならEXCEPTION_APPROVEDになる() throws Exception {
        long contractId = insertContract();
        long findingId = insertFinding(contractId, "T065_EXC");

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/exception")
                        .with(csrf()).contentType("application/json")
                        .content("{\"note\":\"特例\",\"expiresAt\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/exception")
                        .with(csrf()).contentType("application/json")
                        .content("{\"note\":\"特例\",\"expiresAt\":\"2026-01-01T00:00:00\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/exception")
                        .with(csrf()).contentType("application/json")
                        .content("{\"note\":\"特例\",\"expiresAt\":\"2999-12-31T23:59:59\"}"))
                .andExpect(status().isOk());
        assertEquals("EXCEPTION_APPROVED", statusOf(findingId));
    }

    @Test
    @WithMockUser(username = "1", roles = "営業")
    void 営業はfinding操作できない() throws Exception {
        long contractId = insertContract();
        long findingId = insertFinding(contractId, "T065_SALES");
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/ack").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 契約に属さないfindingは404() throws Exception {
        long contractId = insertContract();
        long otherContractId = insertContract();
        long findingId = insertFinding(otherContractId, "T065_WRONG");
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-findings/" + findingId + "/ack").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ===== データ準備 =====

    private long insertContract() {
        String suffix = String.valueOf(java.lang.System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('fa customer " + suffix + "')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='fa customer " + suffix + "'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('fa engineer " + suffix + "', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='fa engineer " + suffix + "'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('fa project " + suffix + "', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='fa project " + suffix + "'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price) "
                + "VALUES (?, ?, ?, '派遣', '2026-01-01', '2026-12-31', '稼動中', 100, 50)", engineerId, projectId, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
    }

    private long insertFinding(long contractId, String code) {
        jdbcTemplate.update("INSERT INTO t_compliance_finding "
                + "(tenant_id, contract_id, code, severity, status, condition_fingerprint, due_date) "
                + "VALUES ('default', ?, ?, 'WARNING', 'OPEN', 'fp-" + code + "', '2026-12-31')", contractId, code);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_finding WHERE code=? AND condition_fingerprint='fp-" + code + "'", Long.class, code);
    }

    private String statusOf(long findingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_compliance_finding WHERE id=" + findingId, String.class);
    }
}
