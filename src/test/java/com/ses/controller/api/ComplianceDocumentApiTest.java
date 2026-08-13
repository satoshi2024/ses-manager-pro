package com.ses.controller.api;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.service.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;

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

    @Autowired
    private DocumentStorage documentStorage;

    @Autowired
    private com.ses.service.compliance.ComplianceMappingCanonicalizer canonicalizer;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(organizationScopeService.hasFullAccess()).thenReturn(true);
        systemConfigService.put("compliance.template.DISPATCH_LEDGER.version", "1", "test");
    }

    @Test
    void 生成するとsnapshotとdocumentとdeliveryが作成され同じ内容の再生成は増えない() throws Exception {
        long contractId = insertContractWithProfile();
        systemConfigService.put("company.name", "SES株式会社", "test");
        systemConfigService.put("company.address", "東京都千代田区", "test");
        systemConfigService.put("company.representative", "代表取締役 山田", "test");

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
        // 当事者（派遣元=自社）がcompany系configからsnapshot化される（R15-P1-02）
        String partyName = jdbcTemplate.queryForObject(
                "SELECT party_name FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId, String.class);
        assertThat(partyName).isEqualTo("SES株式会社");
        String partyAddress = jdbcTemplate.queryForObject(
                "SELECT party_address FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId, String.class);
        assertThat(partyAddress).isEqualTo("東京都千代田区");

        // 2回目（同じ内容の再生成）: 同じdeliveryを返し2件目を作らない（Demo: 版が増えない）
        long secondId = generate(contractId, "EMPLOYMENT_CONDITIONS_STATEMENT", "EMAIL");
        assertEquals(firstId, secondId, "同一snapshotからの再生成は同一delivery");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId),
                "再生成ではsnapshotも増えない");
    }

    @Test
    void downloadはviewerRoleで再maskされマネージャーはFULLPDFを取得できない() throws Exception {
        long contractId = insertContractWithProfile();
        long deliveryId = generate(contractId, "EMPLOYMENT_CONDITIONS_STATEMENT", "EMAIL");

        // 管理者（FULL）のdownload
        byte[] adminPdf = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // マネージャー（MASK）のdownload: FULLと異なるバイト列（再maskされたPDF）
        byte[] managerPdf = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("2").roles("マネージャー")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(adminPdf, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        assertThat(new String(managerPdf, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        assertThat(managerPdf).isNotEqualTo(adminPdf);

        // 営業（LIMITED）のdownloadも可能（masked）
        byte[] salesPdf = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("3").roles("営業")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(salesPdf).isNotEqualTo(adminPdf);
    }

    @Test
    @WithMockUser(username = "1", roles = "営業")
    void 営業は一覧とmaskedDownloadができ生成は403() throws Exception {
        long contractId = insertContractWithProfile();
        long deliveryId = generate(contractId, "DISPATCH_NOTICE", "EMAIL",
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("1").roles("管理者"));

        // 一覧は可能
        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
        // masked downloadは可能
        byte[] pdf = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        // 生成は403
        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json")
                        .content("{\"documentType\":\"DISPATCH_NOTICE\",\"deliveryMethod\":\"EMAIL\"}"))
                .andExpect(status().isForbidden());
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
    void 生成archiveとdownloadは同じ交付時刻のworkerSnapshotを使いtemplate切替後もmaskされる() throws Exception {
        long contractId = insertContractWithProfile();
        // 先に契約snapshotを作成し、後から作成したworker snapshotが交付時点asOfで選ばれる条件にする。
        generate(contractId, "DISPATCH_NOTICE", "EMAIL");
        Long workerId = jdbcTemplate.queryForObject(
                "SELECT engineer_id FROM t_contract WHERE id=?", Long.class, contractId);
        LocalDateTime before = LocalDateTime.now().minusMinutes(5).withNano(0);
        LocalDateTime at = LocalDateTime.now().withNano(0);
        insertWorkerSnapshot(contractId, workerId, 1, before, "BEFORE_VERSION");
        insertWorkerSnapshot(contractId, workerId, 2, at, "AT_VERSION");
        insertWorkerSnapshot(contractId, workerId, 3, at.plusMinutes(5), "AFTER_VERSION");
        insertWorkerSnapshot(contractId, workerId, 4, null, "NULL_VERSION");

        long firstId = generate(contractId, "DISPATCH_LEDGER", "EMAIL");
        String archiveText = archiveText(firstId);
        byte[] fullDownload = mockMvc.perform(get("/api/contracts/" + contractId
                        + "/compliance-documents/" + firstId + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String fullDownloadText = extractPdfText(fullDownload);

        assertThat(archiveText).contains("AT_VERSION")
                .doesNotContain("BEFORE_VERSION", "AFTER_VERSION", "NULL_VERSION");
        assertThat(fullDownloadText).contains("AT_VERSION")
                .doesNotContain("BEFORE_VERSION", "AFTER_VERSION", "NULL_VERSION");
        LocalDateTime deliveredAt = jdbcTemplate.queryForObject(
                "SELECT delivered_at FROM t_document_delivery WHERE id=?", LocalDateTime.class, firstId);
        assertThat(deliveredAt).isNotNull();
        assertThat(deliveredAt).isAfterOrEqualTo(at);

        // template versionを進めても同じsnapshot/交付時点のworker値を使い、同じ版の再生成は冪等にする。
        systemConfigService.put("compliance.template.DISPATCH_LEDGER.version", "2", "test");
        long secondId = generate(contractId, "DISPATCH_LEDGER", "EMAIL");
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT template_version FROM t_document_delivery WHERE id=?", String.class, secondId))
                .isEqualTo("2");
        assertThat(archiveText(secondId)).contains("AT_VERSION");
        assertThat(generate(contractId, "DISPATCH_LEDGER", "EMAIL")).isEqualTo(secondId);
        assertEquals(2, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId
                + " AND document_type='DISPATCH_LEDGER'"));

        byte[] managerPdf = mockMvc.perform(get("/api/contracts/" + contractId
                        + "/compliance-documents/" + secondId + "/download")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("2").roles("マネージャー")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] salesPdf = mockMvc.perform(get("/api/contracts/" + contractId
                        + "/compliance-documents/" + secondId + "/download")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("3").roles("営業")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(extractPdfText(managerPdf)).doesNotContain("AT_VERSION", "BEFORE_VERSION", "AFTER_VERSION");
        assertThat(extractPdfText(salesPdf)).doesNotContain("AT_VERSION", "BEFORE_VERSION", "AFTER_VERSION");
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
    void previewAPIはDBレコードを永続化せず透かし付きPDFとプレビューヘッダーを返却する() throws Exception {
        long contractId = insertContractWithProfile();
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("documentType", "DISPATCH_NOTICE");
        body.put("deliveryMethod", "EMAIL");
        String json = objectMapper.writeValueAsString(body);

        int deliveryBefore = queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId);
        int docBefore = queryInt("SELECT COUNT(*) FROM t_document");

        byte[] pdf = mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/preview")
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Compliance-Preview", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");

        assertEquals(deliveryBefore, queryInt("SELECT COUNT(*) FROM t_document_delivery WHERE contract_id=" + contractId));
        assertEquals(docBefore, queryInt("SELECT COUNT(*) FROM t_document"));
    }

    @Test
    void 承認イベントなしのworkplaceでのgenerateは409を返し交付を行わない() throws Exception {
        long contractId = insertContract();
        jdbcTemplate.update("INSERT INTO m_workplace (customer_id, name, organization_unit) "
                + "VALUES ((SELECT customer_id FROM t_contract WHERE id=?), 'unapproved workplace', '営業部')", contractId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='unapproved workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract_compliance_profile "
                + "(tenant_id, contract_id, workplace_id, work_description, dispatch_period_start, dispatch_period_end) "
                + "VALUES ('default', ?, ?, 'システム開発', '2026-01-01', '2026-12-31')",
                contractId, workplaceId);

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("documentType", "DISPATCH_NOTICE");
        body.put("deliveryMethod", "EMAIL");
        String json = objectMapper.writeValueAsString(body);

        mockMvc.perform(post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isConflict());
    }

    @Test
    void downloadは保存済みDocumentVersionの不変PDFバイト列を返す() throws Exception {
        long contractId = insertContractWithProfile();
        long deliveryId = generate(contractId, "DISPATCH_NOTICE", "EMAIL");

        byte[] pdfBefore = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // profileやエンジニア名を変更
        jdbcTemplate.update("UPDATE t_contract_compliance_profile SET command_person_name='変更後担当者' WHERE contract_id=?", contractId);

        byte[] pdfAfter = mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-documents/" + deliveryId + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // downloadはmaster/profileの動的変更を受けず保存済みimmutable bytesを維持
        assertThat(pdfAfter).isEqualTo(pdfBefore);
    }

    // ===== データ準備 =====

    private long generate(long contractId, String documentType, String deliveryMethod) throws Exception {
        return generate(contractId, documentType, deliveryMethod, null);
    }

    private long generate(long contractId, String documentType, String deliveryMethod,
                          org.springframework.test.web.servlet.request.RequestPostProcessor user) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("documentType", documentType);
        body.put("deliveryMethod", deliveryMethod);
        body.put("recipientContactId", null);
        String json = objectMapper.writeValueAsString(body);
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                post("/api/contracts/" + contractId + "/compliance-documents/generate")
                        .with(csrf()).contentType("application/json").content(json);
        if (user != null) {
            builder.with(user);
        }
        String response = mockMvc.perform(builder)
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
        seedGateData(workplaceId);
        return contractId;
    }

    private void seedGateData(Long workplaceId) {
        Integer mappingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_compliance_mapping_version WHERE tenant_id='default' AND mapping_code='G2-MAPPING' AND status='ACTIVE' AND active_slot=1", Integer.class);
        Long mappingId;
        if (mappingCount == null || mappingCount == 0) {
            com.ses.entity.ComplianceMappingVersion v = new com.ses.entity.ComplianceMappingVersion();
            v.setMappingCode("G2-MAPPING");
            v.setMappingVersion("MAPPING-2026-07");
            v.setEffectiveFrom(java.time.LocalDate.of(2026, 1, 1));
            v.setEffectiveTo(java.time.LocalDate.of(2026, 12, 31));
            String mappingHash = canonicalizer.computeMappingHash(v, java.util.List.of());
            String policyHash = canonicalizer.computeReviewPolicyHash(java.util.List.of(), java.util.List.of());

            jdbcTemplate.update("INSERT INTO m_compliance_mapping_version "
                    + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, effective_to, status, active_slot, created_by, version) "
                    + "VALUES ('default', 'G2-MAPPING', 'MAPPING-2026-07', ?, ?, '2026-01-01', '2026-12-31', 'ACTIVE', 1, 1, 1)",
                    mappingHash, policyHash);
            mappingId = jdbcTemplate.queryForObject("SELECT id FROM m_compliance_mapping_version WHERE mapping_code='G2-MAPPING' AND active_slot=1", Long.class);
        } else {
            mappingId = jdbcTemplate.queryForObject("SELECT id FROM m_compliance_mapping_version WHERE mapping_code='G2-MAPPING' AND active_slot=1", Long.class);
        }

        Integer asgCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE tenant_id='default' AND workplace_id_snapshot=? AND active_slot=1", Integer.class, workplaceId);
        if (asgCount == null || asgCount == 0) {
            jdbcTemplate.update("INSERT INTO t_compliance_responsible_assignment "
                    + "(tenant_id, user_id, user_name_snapshot, role_code, workplace_id_snapshot, active_slot, effective_from, effective_to, created_by, version) "
                    + "VALUES ('default', 1, '管理者', 'COMPLIANCE_RESPONSIBLE', ?, 1, '2026-01-01', '2026-12-31', 1, 1)", workplaceId);
        }

        Integer appCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_mapping_approval_event WHERE mapping_id=? AND workplace_id=? AND action='APPROVE' AND count_subsequent_revokes=0", Integer.class, mappingId, workplaceId);
        if (appCount == null || appCount == 0) {
            jdbcTemplate.update("INSERT INTO t_compliance_mapping_approval_event "
                    + "(tenant_id, mapping_id, workplace_id, assignment_id, actor_user_id, actor_name_snapshot, actor_role_snapshot, action, count_subsequent_revokes, occurred_at, created_by) "
                    + "VALUES ('default', ?, ?, 1, 1, '管理者', 'ROLE_管理者', 'APPROVE', 0, NOW(), 1)", mappingId, workplaceId);
        }
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

    private void insertWorkerSnapshot(long contractId, long workerId, int snapshotVersion,
                                      LocalDateTime snapshotAt, String gender) {
        if (snapshotAt == null) {
            jdbcTemplate.update("INSERT INTO t_contract_compliance_worker_snapshot "
                            + "(tenant_id, contract_id, worker_id, snapshot_version, snapshot_hash, snapshot_at, gender) "
                            + "VALUES ('default', ?, ?, ?, ?, NULL, ?)",
                    contractId, workerId, snapshotVersion, "worker-hash-" + snapshotVersion, gender);
            return;
        }
        jdbcTemplate.update("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(tenant_id, contract_id, worker_id, snapshot_version, snapshot_hash, snapshot_at, gender) "
                        + "VALUES ('default', ?, ?, ?, ?, ?, ?)",
                contractId, workerId, snapshotVersion, "worker-hash-" + snapshotVersion,
                Timestamp.valueOf(snapshotAt), gender);
    }

    private String archiveText(long deliveryId) throws Exception {
        String storageKey = jdbcTemplate.queryForObject(
                "SELECT v.storage_key FROM t_document_delivery d "
                        + "JOIN t_document_version v ON v.document_id=d.document_id "
                        + "WHERE d.id=? ORDER BY v.version_no DESC LIMIT 1", String.class, deliveryId);
        return extractPdfText(documentStorage.readAll(storageKey));
    }

    private String extractPdfText(byte[] pdf) throws Exception {
        StringBuilder text = new StringBuilder();
        try (PdfReader reader = new PdfReader(new ByteArrayInputStream(pdf))) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
        }
        return text.toString();
    }
}
