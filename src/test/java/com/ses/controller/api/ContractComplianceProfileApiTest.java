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
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T063 A1: 契約compliance profile APIのrole別field mask・full DTO保存・validation（L1〜L3）。
 *  - 管理者/HR: 全field（P0_FULL）
 *  - マネージャー: sensitiveFieldはmask（P1_MASK）。sensitive変更reject・省略=現値維持
 *  - 営業: 限定fieldのみ（P2_LIMITED）。書き込みは403
 *  - findingsはcomplianceMenu権限（canViewCompliance）を持つロールのみ返る
 *  - full DTO（key欠落400）、楽観ロック（version 409）、format validation
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class ContractComplianceProfileApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // マネージャーテスト: 組織scopeフルアクセス（契約detail/保存を組織scopeで遮断しない）
        org.mockito.Mockito.when(organizationScopeService.hasFullAccess()).thenReturn(true);
    }

    /** 保存DTOの全編集可能key（version含む）。省略rejectの検証とfull payload構築に使用する。 */
    private static final List<String> SENSITIVE_KEYS = List.of(
            "dispatchFeeAmount", "dispatchFeeBasis", "dispatchFeeCurrency",
            "benefitsDetail", "benefitsProvidedFlag",
            "treatmentScheme",
            "socialInsuranceProcedureIncompleteReason",
            "healthInsuranceStatus", "healthInsuranceMissingReason", "healthInsuranceExpectedDate",
            "pensionInsuranceStatus", "pensionInsuranceMissingReason", "pensionInsuranceExpectedDate",
            "employmentInsuranceStatus", "employmentInsuranceMissingReason", "employmentInsuranceExpectedDate",
            "sourceComplaintContactDepartment", "sourceComplaintContactTitle",
            "sourceComplaintContactName", "sourceComplaintContactPhone",
            "clientComplaintContactDepartment", "clientComplaintContactTitle",
            "clientComplaintContactName", "clientComplaintContactPhone",
            "employmentStabilityPreference",
            "limitationExemptionType", "limitationExemptionDetail", "limitationExemptionBasis",
            "limitationExemptionFrom", "limitationExemptionTo");

    /** 保存DTOの全編集可能key（version含む）。省略rejectの検証とfull payload構築に使用する。 */
    private static final List<String> EDITABLE_KEYS = List.of(
            "contractTypeDetail", "workplaceId",
            "workDescription", "statutoryJobFlag", "statutoryJobReference",
            "responsibilityLevel", "responsibilityDetail",
            "commandPersonContactId", "commandPersonDepartment", "commandPersonTitle",
            "commandPersonName", "commandPersonPhone",
            "clientResponsibleContactId", "clientResponsibleDepartment", "clientResponsibleTitle",
            "clientResponsibleName", "clientResponsiblePhone",
            "dispatchResponsibleUserId", "dispatchResponsibleDepartment", "dispatchResponsibleTitle",
            "dispatchResponsibleName", "dispatchResponsiblePhone",
            "workStartMinute", "workEndMinute", "workSpanNextDayFlag",
            "breakStartMinute", "breakEndMinute",
            "workDayCode", "holidayCalendarCode",
            "agreementReferenceId", "overtimeDailyLimit", "overtimeMonthlyLimit", "overtimeYearlyLimit",
            "overtimePeriodFrom", "overtimePeriodTo",
            "workplaceLimitationDate", "organizationLimitationDate",
            "safetyResponsibilityDetail", "safetyRuleReference",
            "benefitsDetail", "benefitsProvidedFlag",
            "dispatchHeadcount", "agreementTargetFlag", "treatmentScheme",
            "sourceComplaintContactDepartment", "sourceComplaintContactTitle",
            "sourceComplaintContactName", "sourceComplaintContactPhone",
            "clientComplaintContactDepartment", "clientComplaintContactTitle",
            "clientComplaintContactName", "clientComplaintContactPhone",
            "employmentStabilityPreference",
            "limitationExemptionType", "limitationExemptionDetail", "limitationExemptionBasis",
            "limitationExemptionFrom", "limitationExemptionTo",
            "dispatchFeeAmount", "dispatchFeeBasis", "dispatchFeeCurrency",
            "socialInsuranceProcedureIncompleteReason",
            "healthInsuranceStatus", "healthInsuranceMissingReason", "healthInsuranceExpectedDate",
            "pensionInsuranceStatus", "pensionInsuranceMissingReason", "pensionInsuranceExpectedDate",
            "employmentInsuranceStatus", "employmentInsuranceMissingReason", "employmentInsuranceExpectedDate",
            "instructionRoute", "subcontractAllowed", "acceptanceMethod",
            "dispatchPeriodStart", "dispatchPeriodEnd",
            "retentionDueDate", "legalHoldFlag");

    @Test
    @WithMockUser(roles = "管理者")
    void 管理者はprofile全fieldを取得できる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.maskLevel").value("FULL"))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.profile.workDescription").value("業務委託開発"))
                .andExpect(jsonPath("$.data.profile.dispatchFeeAmount").value(10000))
                .andExpect(jsonPath("$.data.profile.healthInsuranceStatus").value("加入済み"))
                .andExpect(jsonPath("$.data.profile.sourceComplaintContactName").value("苦情窓口A"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void HRも全fieldを取得できる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskLevel").value("FULL"))
                .andExpect(jsonPath("$.data.profile.dispatchFeeAmount").value(10000));
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーはsensitiveFieldがmaskされる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskLevel").value("MASK"))
                .andExpect(jsonPath("$.data.profile.workDescription").value("業務委託開発"))
                .andExpect(jsonPath("$.data.profile.dispatchFeeAmount").doesNotExist())
                .andExpect(jsonPath("$.data.profile.healthInsuranceStatus").doesNotExist())
                .andExpect(jsonPath("$.data.profile.benefitsDetail").doesNotExist())
                .andExpect(jsonPath("$.data.profile.sourceComplaintContactName").doesNotExist())
                .andExpect(jsonPath("$.data.profile.workplaceLimitationDate").value("2029-01-01"));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は契約遂行に必要な限定fieldのみ見える() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskLevel").value("LIMITED"))
                .andExpect(jsonPath("$.data.canEdit").value(false))
                .andExpect(jsonPath("$.data.profile.workDescription").value("業務委託開発"))
                .andExpect(jsonPath("$.data.profile.workplaceLimitationDate").value("2029-01-01"))
                .andExpect(jsonPath("$.data.profile.dispatchFeeAmount").doesNotExist())
                .andExpect(jsonPath("$.data.profile.healthInsuranceStatus").doesNotExist())
                .andExpect(jsonPath("$.data.profile.employmentStabilityPreference").doesNotExist())
                .andExpect(jsonPath("$.data.profile.retentionDueDate").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void findingsはcomplianceMenu権限を持つロールのみ返る() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);
        insertFinding(contractId);

        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings.length()").value(1))
                .andExpect(jsonPath("$.data.findings[0].code").value("MISSING_COMMAND_PERSON"));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業にはfindingsが返らない() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);
        insertFinding(contractId);

        mockMvc.perform(get("/api/contracts/" + contractId + "/compliance-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void PUTでfullDTOを保存するとversionが増え再取得で反映される() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 0);
        payload.put("workDescription", "更新後の業務内容");
        payload.put("dispatchFeeAmount", 12000);

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.profile.version").value(1))
                .andExpect(jsonPath("$.data.profile.workDescription").value("更新後の業務内容"))
                .andExpect(jsonPath("$.data.profile.dispatchFeeAmount").value(12000));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void keyを省略したPUTは400でrejectされる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 0);
        payload.remove("workDescription");

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業はPUTできない() throws Exception {
        long contractId = insertDispatchContract();

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(fullPayload())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーはsensitiveFieldを変更すると403でrejectされる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 0);
        payload.put("workDescription", "業務委託開発");
        payload.put("dispatchFeeAmount", 99999);

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーはsensitiveを省略して保存すると現値維持される() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 0);
        SENSITIVE_KEYS.forEach(payload::remove);
        payload.put("workDescription", "マネージャーによる更新");

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.workDescription").value("マネージャーによる更新"));

        // sensitiveFieldはDBに現値が残っている（マネージャーには見えないがDBでは保持されている）
        java.math.BigDecimal fee = jdbcTemplate.queryForObject(
                "SELECT dispatch_fee_amount FROM t_contract_compliance_profile WHERE contract_id=?",
                java.math.BigDecimal.class, contractId);
        assertEquals(10000, fee.intValue());
        String insurance = jdbcTemplate.queryForObject(
                "SELECT health_insurance_status FROM t_contract_compliance_profile WHERE contract_id=?",
                String.class, contractId);
        assertEquals("加入済み", insurance);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 存在しない契約は404になる() throws Exception {
        mockMvc.perform(get("/api/contracts/999999/compliance-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void versionが一致しないPUTは409でrejectされる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 99);

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 派遣期間が逆転しているPUTは400でrejectされる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 0);
        payload.put("dispatchPeriodStart", "2026-12-31");
        payload.put("dispatchPeriodEnd", "2026-01-01");

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 存在しないworkplaceを指定したPUTは400でrejectされる() throws Exception {
        long contractId = insertDispatchContract();
        insertProfile(contractId);

        Map<String, Object> payload = fullPayload();
        payload.put("version", 0);
        payload.put("workplaceId", 999999);

        mockMvc.perform(put("/api/contracts/" + contractId + "/compliance-profile")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ===== データ準備 =====

    private long insertDispatchContract() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('cpp customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='cpp customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('cpp engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='cpp engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('cpp project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='cpp project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price) "
                + "VALUES (?, ?, ?, '派遣', '2026-01-01', '2026-12-31', '稼動中', 100, 50)", engineerId, projectId, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
    }

    private void insertProfile(long contractId) {
        jdbcTemplate.update("INSERT INTO m_workplace (customer_id, name, organization_unit) "
                + "VALUES ((SELECT customer_id FROM t_contract WHERE id=?), 'cpp workplace', '開発部')", contractId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='cpp workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract_compliance_profile "
                + "(tenant_id, contract_id, workplace_id, work_description, workplace_limitation_date, organization_limitation_date, "
                + "dispatch_fee_amount, health_insurance_status, benefits_detail, source_complaint_contact_name) "
                + "VALUES ('default', ?, ?, '業務委託開発', '2029-01-01', '2027-01-01', 10000, '加入済み', '借上社宅', '苦情窓口A')",
                contractId, workplaceId);
    }

    private void insertFinding(long contractId) {
        jdbcTemplate.update("INSERT INTO t_compliance_finding "
                + "(tenant_id, contract_id, code, severity, status, condition_fingerprint) "
                + "VALUES ('default', ?, 'MISSING_COMMAND_PERSON', 'WARNING', 'OPEN', 'command-person')", contractId);
    }

    /** 全編集可能keyを含むfull payload（値はnull）。テストごとに上書きして使う。 */
    private Map<String, Object> fullPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        EDITABLE_KEYS.forEach(key -> payload.put(key, null));
        payload.put("version", null);
        return payload;
    }
}
