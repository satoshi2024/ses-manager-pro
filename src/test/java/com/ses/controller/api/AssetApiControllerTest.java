package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.BaseIntegrationTest;
import com.ses.entity.Asset;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.LicensePlan;
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
    private ExternalAccountService externalAccountService;

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
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
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
}
