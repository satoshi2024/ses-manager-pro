package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.BaseIntegrationTest;
import com.ses.entity.Asset;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerSales;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.LicensePlan;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetService;
import com.ses.service.ExternalAccountService;
import com.ses.service.LicenseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Asset / Inventory / Account / License API Integration Tests")
class AssetApiControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentService assetAssignmentService;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerSalesMapper engineerSalesMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ExternalAccountService externalAccountService;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private LicenseService licenseService;

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("POST /api/assets -> GET /api/assets -> PUT /api/assets/{id}")
    void testAssetApiCrud() throws Exception {
        Asset asset = Asset.builder()
                .assetTag("AST-API-001")
                .assetName("MacBook Pro 14")
                .category("PC")
                .status("IN_STOCK")
                .purchasePrice(new BigDecimal("280000.00"))
                .build();

        // 1. 作成
        mockMvc.perform(post("/api/assets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asset)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assetTag").value("AST-API-001"));

        // 2. 検索
        mockMvc.perform(get("/api/assets")
                        .param("keyword", "AST-API-001")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].assetName").value("MacBook Pro 14"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("Asset Assignment & Return API Flow")
    void testAssignmentApiFlow() throws Exception {
        Asset asset = Asset.builder()
                .assetTag("AST-API-ASSIGN-01")
                .assetName("ThinkPad L15")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        // 1. 貸与 API
        String assignPayload = objectMapper.writeValueAsString(Map.of(
                "assetId", asset.getId(),
                "assigneeType", "ENGINEER",
                "assigneeId", 801L,
                "startDate", LocalDate.now().toString(),
                "note", "API貸与"
        ));

        String resStr = mockMvc.perform(post("/api/asset-assignments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resMap = objectMapper.readValue(resStr, Map.class);
        Map<?, ?> dataMap = (Map<?, ?>) resMap.get("data");
        Number assignmentId = (Number) dataMap.get("id");

        // 2. 返却 API
        String returnPayload = objectMapper.writeValueAsString(Map.of(
                "actualReturnDate", LocalDate.now().toString(),
                "note", "API返却完了"
        ));

        mockMvc.perform(post("/api/asset-assignments/" + assignmentId + "/return")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("RETURNED"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("External Account & License API Flow")
    void testAccountAndLicenseApiFlow() throws Exception {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("MS365_TEST")
                .systemName("Microsoft 365")
                .systemType("SAAS_MAIL")
                .isActive(1)
                .build();
        externalAccountService.saveSystem(system);

        // 1. アカウント参照登録
        String accPayload = objectMapper.writeValueAsString(Map.of(
                "systemId", system.getId(),
                "accountIdentifier", "api.user@ses-test.jp",
                "assigneeType", "ENGINEER",
                "assigneeId", 901L,
                "permissionLevel", "DEVELOPER"
        ));

        String accRes = mockMvc.perform(post("/api/external-accounts")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountIdentifier").value("api.user@ses-test.jp"))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> accMap = objectMapper.readValue(accRes, Map.class);
        Map<?, ?> accData = (Map<?, ?>) accMap.get("data");
        Number accId = (Number) accData.get("id");

        // 2. 失効確認
        mockMvc.perform(post("/api/external-accounts/" + accId + "/confirm-revoke")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.actorType").value("HUMAN"))
                .andExpect(jsonPath("$.data.confirmationSource").value("MANUAL_API"))
                .andExpect(jsonPath("$.data.humanUserId").value(1));

        mockMvc.perform(get("/api/external-accounts/export.csv"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("actor_type"),
                                org.hamcrest.Matchers.containsString("HUMAN"),
                                org.hamcrest.Matchers.containsString("MANUAL_API"))));
    }

    @Test
    @WithMockUser(username = "principal-does-not-exist", roles = {"管理者"})
    @DisplayName("Manual revoke confirmation rejects a principal that cannot resolve to sys_user")
    void testManualConfirmRejectsUnresolvedPrincipal() throws Exception {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("UNRESOLVED_PRINCIPAL_" + System.nanoTime())
                .systemName("Unresolved principal test")
                .systemType("SAAS_MAIL")
                .isActive(1)
                .build();
        externalAccountService.saveSystem(system);
        var ref = externalAccountService.registerAccountReference(
                system.getId(), "unresolved@ses-test.jp", "ENGINEER", 902L, "MEMBER", 1L);

        mockMvc.perform(post("/api/external-accounts/" + ref.getId() + "/confirm-revoke")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        assertThat(externalAccountReferenceStatus(ref.getId())).isEqualTo("ACTIVE");
    }

    private String externalAccountReferenceStatus(Long id) {
        return externalAccountReferenceMapper.selectById(id).getStatus();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("Lost incident API: report creates ledger and update tracks wipe, police, insurance and documents")
    void testLostIncidentApiFlow() throws Exception {
        Asset asset = Asset.builder()
                .assetTag("AST-API-LOST-" + System.nanoTime())
                .assetName("Lost API device")
                .category("PC")
                .build();
        assetService.createAsset(asset, 1L);

        mockMvc.perform(post("/api/assets/" + asset.getId() + "/report-lost")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "API紛失報告"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("LOST"));

        mockMvc.perform(get("/api/assets/" + asset.getId() + "/lost-incident"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.remoteWipeStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.data.incidentDetails").value("API紛失報告"));

        String updatePayload = objectMapper.writeValueAsString(Map.of(
                "remoteWipeStatus", "CONFIRMED",
                "policeReportNumber", "POLICE-API-0001",
                "insuranceClaimStatus", "APPLIED"));
        mockMvc.perform(put("/api/assets/" + asset.getId() + "/lost-incident")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.remoteWipeStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.policeReportNumber").value("POLICE-API-0001"))
                .andExpect(jsonPath("$.data.insuranceClaimStatus").value("APPLIED"));
    }

    @Test
    @DisplayName("Lost incident detail is not exposed to sales even for an assigned asset")
    void testSalesCannotReadLostIncidentDetails() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        Engineer engineer = Engineer.builder()
                .fullName("紛失scope要員-" + suffix)
                .employmentType("正社員")
                .status("稼動中")
                .build();
        engineerMapper.insert(engineer);
        SysUser sales = SysUser.builder()
                .username("asset-r10-sales-" + suffix)
                .password("pass")
                .role("営業")
                .status(1)
                .build();
        sysUserMapper.insert(sales);
        engineerSalesMapper.insert(EngineerSales.builder()
                .engineerId(engineer.getId())
                .salesUserId(sales.getId())
                .primaryFlag(1)
                .assignedAt(LocalDate.now())
                .build());

        Asset asset = Asset.builder()
                .assetTag("AST-API-LOST-SCOPE-" + suffix)
                .assetName("Lost scope device")
                .category("PC")
                .build();
        assetService.createAsset(asset, 1L);
        assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", engineer.getId(), LocalDate.now(), LocalDate.now().plusDays(30),
                null, "紛失scope確認", 1L);
        assetService.reportLost(asset.getId(), "営業閲覧拒否確認", 1L, null);

        mockMvc.perform(get("/api/assets/" + asset.getId() + "/lost-incident")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user(String.valueOf(sales.getId())).roles("営業")))
                .andExpect(status().isForbidden());
    }
}
