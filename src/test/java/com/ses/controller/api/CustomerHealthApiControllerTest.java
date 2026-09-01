package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.service.servicedesk.CustomerHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CustomerHealthApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerHealthService customerHealthService;

    @Test
    @DisplayName("管理者による月次スナップショット生成は200成功")
    @WithMockUser(username = "admin", roles = {"管理者"})
    void generateMonthlySnapshot_asAdmin_returns200() throws Exception {
        doNothing().when(customerHealthService).generateMonthlySnapshot(eq("2026-08"), anyString());

        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf())
                        .param("targetMonth", "2026-08")
                        .param("reason", "手動実行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(customerHealthService).generateMonthlySnapshot(eq("2026-08"), eq("手動実行"));
    }

    @Test
    @DisplayName("targetMonth未指定時はデフォルト当月で200成功")
    @WithMockUser(username = "admin", roles = {"管理者"})
    void generateMonthlySnapshot_defaultMonth_returns200() throws Exception {
        doNothing().when(customerHealthService).generateMonthlySnapshot(any(), any());

        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("不正なtargetMonth形式は400を返却する")
    @WithMockUser(username = "admin", roles = {"管理者"})
    void generateMonthlySnapshot_invalidMonthFormat_returns400() throws Exception {
        doThrow(BusinessException.of(400, "error.invalidMonthFormat"))
                .when(customerHealthService).generateMonthlySnapshot(eq("2026-13"), any());

        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf())
                        .param("targetMonth", "2026-13"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("営業ロールによるスナップショット生成呼び出しは403 Forbidden")
    @WithMockUser(username = "sales", roles = {"営業"})
    void generateMonthlySnapshot_asSales_returns403() throws Exception {
        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf())
                        .param("targetMonth", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("マネージャーロールによるスナップショット生成呼び出しは403 Forbidden")
    @WithMockUser(username = "manager", roles = {"マネージャー"})
    void generateMonthlySnapshot_asManager_returns403() throws Exception {
        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf())
                        .param("targetMonth", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HRロールによるスナップショット生成呼び出しは403 Forbidden")
    @WithMockUser(username = "hr", roles = {"HR"})
    void generateMonthlySnapshot_asHr_returns403() throws Exception {
        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf())
                        .param("targetMonth", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("要員ロールによるスナップショット生成呼び出しは403 Forbidden")
    @WithMockUser(username = "engineer", roles = {"要員"})
    void generateMonthlySnapshot_asEngineer_returns403() throws Exception {
        mockMvc.perform(post("/api/customer-success/health/snapshots")
                        .with(csrf())
                        .param("targetMonth", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("顧客ヘルススコア一覧取得は管理者・営業・マネージャーが実行可能")
    @WithMockUser(username = "sales", roles = {"営業"})
    void listHealthSummaries_asSales_returns200() throws Exception {
        CustomerHealthScoreDto dto = CustomerHealthScoreDto.builder()
                .customerId(10L)
                .customerName("テスト顧客")
                .healthScore(85)
                .healthStatus("HEALTHY")
                .build();
        when(customerHealthService.listCustomerHealthSummaries(any(), any())).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/customer-success/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].customerName").value("テスト顧客"))
                .andExpect(jsonPath("$.data[0].healthScore").value(85));
    }
}
