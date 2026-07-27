package com.ses.controller.api;

import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.service.ManagementAccountingService;
import com.ses.service.ManagementBudgetService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 管理会計CSVのヘッダーとsummary/detail行の列数を同時に固定する。 */
@WebMvcTest(ManagementAccountingApiController.class)
class ManagementAccountingApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ManagementBudgetService budgetService;
    @MockBean
    private ManagementAccountingService managementAccountingService;
    @MockBean
    private ManagementBudgetMapper budgetMapper;
    @MockBean
    private MonthlyAccountingDimensionMapper dimensionMapper;
    @MockBean
    private OrganizationScopeService organizationScopeService;

    @Autowired
    private ManagementAccountingApiController controller;

    @Test
    void csvValueは制御文字を数式文字列として出力しない() throws Exception {
        java.lang.reflect.Method method = ManagementAccountingApiController.class.getDeclaredMethod("csvValue", Object.class);
        method.setAccessible(true);
        assertEquals("'\t=SUM(A1)", method.invoke(controller, "\t=SUM(A1)"));
        assertEquals("\"'\r@cmd\"", method.invoke(controller, "\r@cmd"));
        assertEquals("\"部署\r名\"", method.invoke(controller, "部署\r名"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void export_summaryAndDetailHaveSameFifteenColumnsAsHeader() throws Exception {
        ManagementAccountingSummaryDto.Row row = ManagementAccountingSummaryDto.Row.builder()
                .organizationId(1L).organizationName("営業部").costCenterId(2L)
                .revenue(new BigDecimal("100")).cost(new BigDecimal("60"))
                .grossProfit(new BigDecimal("40")).budgetRevenue(new BigDecimal("90"))
                .budgetGrossProfit(new BigDecimal("30")).revenueVariance(new BigDecimal("10"))
                .grossProfitVariance(new BigDecimal("10")).waitCost(new BigDecimal("5"))
                .build();
        ManagementAccountingSummaryDto.Detail detail = ManagementAccountingSummaryDto.Detail.builder()
                .organizationId(1L).organizationName("営業部").costCenterId(2L)
                .customerId(3L).projectId(4L).salesUserId(5L)
                .revenue(new BigDecimal("100")).cost(new BigDecimal("60"))
                .grossProfit(new BigDecimal("40")).build();
        when(managementAccountingService.summary("2026-06", null, null, null, null, null, null))
                .thenReturn(ManagementAccountingSummaryDto.builder()
                        .month("2026-06").rows(List.of(row)).details(List.of(detail)).build());

        String csv = new String(mockMvc.perform(get("/api/management-accounting/export")
                        .param("month", "2026-06"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\uFEFF", "");
        String[] lines = csv.strip().split("\\R");
        assertEquals(15, lines[0].split(",", -1).length);
        assertEquals(15, lines[1].split(",", -1).length);
        assertEquals(15, lines[2].split(",", -1).length);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void budgetCsv_全行が同じupsert経路を通る() throws Exception {
        when(budgetService.upsert(any(ManagementBudget.class), nullable(Integer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "budget.csv", "text/csv",
                ("organizationId,costCenterId,budgetMonth,revenue,grossProfit,utilizationCount,hireCount,version\n"
                        + "1,,2026-07-01,100,30,2,1,\n"
                        + "2,,2026-07-01,200,60,3,1,\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/management-accounting/budgets/csv").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));
        verify(budgetService, times(2)).upsert(any(ManagementBudget.class), nullable(Integer.class));
        org.junit.jupiter.api.Assertions.assertTrue(
                ManagementAccountingApiController.class.getMethod("importBudgetCsv", MultipartFile.class)
                        .isAnnotationPresent(Transactional.class));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void budgetCsv_二行目の業務エラーは成功に変換しない() throws Exception {
        when(budgetService.upsert(any(ManagementBudget.class), nullable(Integer.class)))
                .thenReturn(new ManagementBudget())
                .thenThrow(com.ses.common.exception.BusinessException.of("error.organization.budget.conflict"));
        MockMultipartFile file = new MockMultipartFile("file", "budget.csv", "text/csv",
                ("organizationId,costCenterId,budgetMonth,revenue,grossProfit,utilizationCount,hireCount,version\n"
                        + "1,,2026-07-01,100,30,2,1,\n"
                        + "2,,2026-07-01,200,60,3,1,\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/management-accounting/budgets/csv").file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(budgetService, times(2)).upsert(any(ManagementBudget.class), nullable(Integer.class));
    }
}
