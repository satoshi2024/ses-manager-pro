package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T064 B1: 法定帳票の生成・交付・受領確認・ダウンロードAPI（L2〜L3）。
 *  - 生成: snapshot作成→PDF→document archive登録→t_document_delivery記録
 *  - 冪等: 同じ(contract, document_type, template_version, snapshot_hash)の再生成で2件目を作らない
 *  - template version切替（m_system_config）で版が進む
 *  - profile変更→新snapshot→新hash→新交付記録（版差分）
 *  - 受領確認（confirmedAt）、PDFダウンロード（%PDF）
 *  - 営業は403（生成・一覧・ダウンロード不可）
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@WithMockUser(username = "1", roles = "管理者")
class ComplianceDocumentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.ses.service.SystemConfigService systemConfigService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(organizationScopeService.hasFullAccess()).thenReturn(true);
    }

    @Test
    void 生成するとsnapshotとdocumentとdeliveryが作成され同じ内容の再生成は増えない() throws Exception {
        long contractId = insertContractWithProfile();

        // 1回目: 生成
        long firstId = generate(contractId, "EMPLOYMENT_CONDITIONS_STATEMENT", "EMAIL");

        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId),
                "snapshotが1件作成される");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_document WHERE document_type='EMPLOYMENT_CONDITIONS_STATEMENT'"),
                "document archiveへ登録される");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId),
                "交付記録が1件");
        String snapshotHash = jdbcTemplate.queryForObject(
                "SELECT snapshot_hash FROM t_document_delivery WHERE id=" + firstId, String.class);
        assertThat(snapshotHash).isNotBlank();

        // 2回目（同じ内容の再生成）: 同じdeliveryを返し2件目を作らない（Demo: 版が増えない）
        long secondId = generate(contractId, "EMPLOYMENT_CONDITIONS_STATEMENT", "EMAIL");
        assertEquals(firstId, secondId, "同一snapshotからの再生成は同一delivery");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId),
                "再生成ではsnapshotも増えない");
    }

    @Test
    void profile変更後は新snapshotとなり新しい交付記録が作られる() throws Exception {
        long contractId = insertContractWithProfile();
        long firstId = generate(contractId, "DISPATCH_NOTICE", "PAPER");
        String firstHash = jdbcTemplate.queryForObject(
                "SELECT snapshot_hash FROM t_document_delivery WHERE id=" + firstId, String.class);

        // profileの業務内容を変更して再生成 → 新snapshot（v2）・新hash・新delivery
        jdbcTemplate.update("UPDATE t_contract_compliance_profile SET work_description='更新後業務' WHERE contract_id=" + contractId);
        long secondId = generate(contractId, "DISPATCH_NOTICE", "PAPER");

        assertThat(secondId).isNotEqualTo(firstId);
        assertEquals(2, queryInt("SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId));
        String secondHash = jdbcTemplate.queryForObject(
                "SELECT snapshot_hash FROM t_document_delivery WHERE id=" + secondId, String.class);
        assertThat(secondHash).isNotEqualTo(firstHash);
    }

    @Test
    void templateVersion切替で版が進み同じ内容の再生成はその版で冪等である() throws Exception {
        long contractId = insertContractWithProfile();
        long firstId = generate(contractId, "DISPATCH_LEDGER", "EMAIL");
        Integer v1 = jdbcTemplate.queryForObject(
                "SELECT template_version FROM t_document_delivery WHERE id=" + firstId, Integer.class);
        assertEquals(1, v1);

        // m_system_configでtemplate versionを2へ（cacheも更新されるputを使用）
        systemConfigService.put("compliance.template.DISPATCH_LEDGER.version", "2", "test");
        long secondId = generate(contractId, "DISPATCH_LEDGER", "EMAIL");
        Integer v2 = jdbcTemplate.queryForObject(
                "SELECT template_version FROM t_document_delivery WHERE id=" + secondId, Integer.class);
        assertEquals(2, v2);
        assertThat(secondId).isNotEqualTo(firstId);

        // 同じ版・同じsnapshotの再生成は冪等
        long thirdId = generate(contractId, "DISPATCH_LEDGER", "EMAIL");
        assertEquals(secondId, thirdId);
        assertEquals(2, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId));
    }

    @Test
    void 受領確認でconfirmedAtが記録されダウンロードでPDFが返る() throws Exception {
        long contractId = insertContractWithProfile();
        long deliveryId = generate(contractId, "INDIVIDUAL_CONTRACT", "EMAIL");

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/confirm")
                        .with(csrf()).param("note", "受領しました"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.confirmedAt").exists());

        byte[] pdf = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF");
        assertThat(pdf.length).isGreaterThan(500);
    }

    @Test
    void 不正な帳票種別と交付方法は400() throws Exception {
        long contractId = insertContractWithProfile();
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json")
                        .content("{\"documentType\":\"UNKNOWN\",\"deliveryMethod\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json")
                        .content("{\"documentType\":\"DISPATCH_NOTICE\",\"deliveryMethod\":\"FAX\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profile未作成の契約は400() throws Exception {
        long contractId = insertContract();
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json")
                        .content("{\"documentType\":\"DISPATCH_NOTICE\",\"deliveryMethod\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "1", roles = "営業")
    void 営業は一覧生成ダウンロードすべて403() throws Exception {
        long contractId = insertContractWithProfile();
        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json")
                        .content("{\"documentType\":\"DISPATCH_NOTICE\",\"deliveryMethod\":\"EMAIL\"}"))
                .andExpect(status().isForbidden());
    }

    // ===== データ準備 =====

    private long generate(long contractId, String documentType, String deliveryMethod) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("documentType", documentType);
        body.put("deliveryMethod", deliveryMethod);
        body.put("recipientContactId", null);
        String json = objectMapper.writeValueAsString(body);
        String response = mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private long insertContractWithProfile() {
        long contractId = insertContract();
        jdbcTemplate.update("INSERT INTO m_workplace (customer_id, name, organization_unit) "
                + "VALUES ((SELECT customer_id FROM t_contract WHERE id=?), 'doc workplace', '開発部')", contractId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='doc workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract_compliance_profile "
                + "(tenant_id, contract_id, workplace_id, work_description, dispatch_period_start, dispatch_period_end, "
                + "work_start_minute, work_end_minute, command_person_name, dispatch_fee_amount) "
                + "VALUES ('default', ?, ?, 'システム開発', '2026-01-01', '2026-12-31', 540, 1080, '田中', 120000)",
                contractId, workplaceId);
        return contractId;
    }

    private long insertContract() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('doc customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='doc customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('doc engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='doc engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('doc project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='doc project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price, contract_no) "
                + "VALUES (?, ?, ?, '派遣', '2026-01-01', '2026-12-31', '稼動中', 100, 50, 'C-DOC-1')",
                engineerId, projectId, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
    }

    private int queryInt(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
