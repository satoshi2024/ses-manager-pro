package com.ses.controller.api;

import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.EngineerService;
import com.ses.service.export.ExcelExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ExportApiController の Web層テスト。
 * Excelダウンロードエンドポイントの Content-Type / Content-Disposition を検証する。
 */
@WebMvcTest(ExportApiController.class)
class ExportApiControllerTest {

    private static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineerService engineerService;

    @MockBean
    private ContractMapper contractMapper;

    @MockBean
    private ProjectMapper projectMapper;

    @MockBean
    private CustomerMapper customerMapper;

    @MockBean
    private WorkRecordMapper workRecordMapper;

    @MockBean
    private ExcelExportService excelExportService;

    @MockBean
    private com.ses.service.billing.MonthlyRevenueCalcService monthlyRevenueCalcService;

    @Test
    @WithMockUser
    void exportEngineers_returnsXlsxWithAttachmentHeader() throws Exception {
        when(engineerService.list(any(Wrapper.class))).thenReturn(List.of(new Engineer()));
        when(excelExportService.exportEngineers(any())).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/engineers/export"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentType() != null
                                && result.getResponse().getContentType().startsWith(XLSX_MEDIA_TYPE)))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader("Content-Disposition");
                    org.junit.jupiter.api.Assertions.assertTrue(disposition.contains("attachment"));
                    // ファイル名は filename*=UTF-8''... 形式でURLエンコードされるため、デコードして検証する
                    String decoded = java.net.URLDecoder.decode(disposition, java.nio.charset.StandardCharsets.UTF_8);
                    org.junit.jupiter.api.Assertions.assertTrue(decoded.contains("要員一覧"));
                });
    }

    @Test
    @WithMockUser
    void exportContracts_returnsXlsxWithAttachmentHeader() throws Exception {
        when(contractMapper.selectList(any())).thenReturn(List.of(new Contract()));
        when(excelExportService.exportContracts(any())).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/contracts/export"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentType() != null
                                && result.getResponse().getContentType().startsWith(XLSX_MEDIA_TYPE)))
                .andExpect(header().exists("Content-Disposition"));
    }

    @Test
    @WithMockUser
    void exportContracts_appliesListSearchParameters() throws Exception {
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(excelExportService.exportContracts(any())).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/contracts/export")
                        .param("status", "稼動中")
                        .param("customerId", "10")
                        .param("salesUserId", "20")
                        .param("contractNo", "C-001")
                        .param("endDateFrom", "2026-07-01")
                        .param("endDateTo", "2026-07-31"))
                .andExpect(status().isOk());

        ArgumentCaptor<Wrapper<Contract>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(contractMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("status"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("customer_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("sales_user_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("contract_no"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("end_date >="));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("end_date <="));
    }

    @Test
    @WithMockUser
    void exportMonthlyRevenue_returnsXlsxWithAttachmentHeader() throws Exception {
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(workRecordMapper.selectList(any())).thenReturn(List.of());
        when(monthlyRevenueCalcService.calc(any(), any(), any()))
                .thenReturn(new com.ses.service.billing.MonthlyRevenueCalcService.MonthlyAmount(0L, 0L, false));
        when(excelExportService.exportMonthlyRevenue(any(Integer.class), any())).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/dashboard/revenue-export").param("fiscalYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentType() != null
                                && result.getResponse().getContentType().startsWith(XLSX_MEDIA_TYPE)))
                .andExpect(header().exists("Content-Disposition"));
    }
}
