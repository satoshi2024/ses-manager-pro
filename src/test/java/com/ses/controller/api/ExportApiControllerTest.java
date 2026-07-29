package com.ses.controller.api;

import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.dto.engineerfollowup.RetentionRiskDto;
import com.ses.dto.export.ContractExportDto;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.EngineerService;
import com.ses.service.export.ExportConcurrencyLimiter;
import com.ses.service.export.ExcelExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.OutputStream;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;

    @MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @MockBean
    private com.ses.service.security.AuthorizationService authorizationService;

    @MockBean
    private com.ses.service.security.MfaService mfaService;

    @MockBean
    private com.ses.service.security.PersistentSessionService persistentSessionService;

    @BeforeEach
    void allowFullScopeForExistingControllerCases() {
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(authorizationService.isAllowed(any(), any())).thenReturn(true);
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
    }

    @MockBean
    private com.ses.service.RetentionRiskService retentionRiskService;

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
    void exportContracts_組織scopeをcountと取得の共通条件へ渡す() throws Exception {
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedContractIds(any(java.time.LocalDate.class)))
                .thenReturn(java.util.Set.of(22L));
        when(organizationScopeService.intersectWithDataScope(any(), any()))
                .thenReturn(java.util.Set.of(22L));
        ArgumentCaptor<Wrapper<Contract>> captor = ArgumentCaptor.forClass(Wrapper.class);
        when(contractMapper.selectCount(any())).thenReturn(0L);

        mockMvc.perform(get("/api/contracts/export"))
                .andExpect(status().isOk());

        verify(contractMapper).selectCount(captor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().getSqlSegment().contains("id"));
    }

    @Test
    @WithMockUser
    void exportContracts_appliesListSearchParameters() throws Exception {
        when(contractMapper.selectCount(any())).thenReturn(1201L);
        when(engineerService.listByIds(any())).thenReturn(List.of());
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of());
        when(customerMapper.selectBatchIds(any())).thenReturn(List.of());

        AtomicInteger fetchCount = new AtomicInteger();
        List<Wrapper<Contract>> batchWrappers = new ArrayList<>();
        when(contractMapper.selectList(any())).thenAnswer(invocation -> {
            batchWrappers.add(invocation.getArgument(0));
            int batch = fetchCount.incrementAndGet();
            if (batch > 3) return List.of();
            int start = batch == 1 ? 1201 : batch == 2 ? 701 : 201;
            int size = batch == 3 ? 201 : 500;
            List<Contract> rows = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Contract contract = new Contract();
                contract.setId((long) (start - i));
                contract.setContractNo("C-" + contract.getId());
                rows.add(contract);
            }
            return rows;
        });
        List<ContractExportDto> exported = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<List<ContractExportDto>> batches = invocation.getArgument(0);
            for (List<ContractExportDto> batch : batches) exported.addAll(batch);
            return null;
        }).when(excelExportService).streamContracts(any(Iterable.class), any(OutputStream.class));

        mockMvc.perform(get("/api/contracts/export")
                        .param("status", "稼動中")
                        .param("customerId", "10")
                        .param("salesUserId", "20")
                        .param("contractNo", "C-001")
                        .param("endDateFrom", "2026-07-01")
                        .param("endDateTo", "2026-07-31"))
                .andExpect(status().isOk());

        ArgumentCaptor<Wrapper<Contract>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(contractMapper, times(3)).selectList(captor.capture());
        assertEquals(1201, exported.size());
        assertEquals("C-1201", exported.get(0).getContractNo());
        assertEquals("C-1", exported.get(1200).getContractNo());
        assertEquals(3, fetchCount.get());
        String sqlSegment = captor.getAllValues().get(0).getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("status"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("customer_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("sales_user_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("contract_no"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("end_date >="));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSegment.contains("end_date <="));
        org.junit.jupiter.api.Assertions.assertTrue(captor.getAllValues().get(1).getSqlSegment().contains("id <"));
        org.junit.jupiter.api.Assertions.assertTrue(captor.getAllValues().get(2).getSqlSegment().contains("id <"));
        for (Wrapper<Contract> batchWrapper : captor.getAllValues()) {
            String batchSql = batchWrapper.getSqlSegment();
            org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("status"));
            org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("customer_id"));
            org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("sales_user_id"));
            org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("contract_no"));
            org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("end_date >="));
            org.junit.jupiter.api.Assertions.assertTrue(batchSql.contains("end_date <="));
        }
        ArgumentCaptor<Wrapper<Contract>> countCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(contractMapper).selectCount(countCaptor.capture());
        String countSql = countCaptor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(countSql.contains("status"));
        org.junit.jupiter.api.Assertions.assertTrue(countSql.contains("customer_id"));
        org.junit.jupiter.api.Assertions.assertTrue(countSql.contains("sales_user_id"));
    }

    @Test
    @WithMockUser
    void exportContracts_原価閲覧権限なしではcostPriceを出力しない() throws Exception {
        Contract contract = new Contract();
        contract.setId(1L);
        contract.setCostPrice(new java.math.BigDecimal("600000"));
        when(contractMapper.selectCount(any())).thenReturn(1L);
        when(contractMapper.selectList(any())).thenReturn(List.of(contract), List.of());
        when(authorizationService.isAllowed(any(), org.mockito.ArgumentMatchers.eq("contract.cost.view")))
                .thenReturn(false);
        List<ContractExportDto> exported = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<List<ContractExportDto>> batches = invocation.getArgument(0);
            for (List<ContractExportDto> batch : batches) exported.addAll(batch);
            return null;
        }).when(excelExportService).streamContracts(any(Iterable.class), any(OutputStream.class));

        mockMvc.perform(get("/api/contracts/export"))
                .andExpect(status().isOk());

        assertEquals(1, exported.size());
        org.junit.jupiter.api.Assertions.assertNull(exported.get(0).getCostPrice());
    }

    @Test
    @WithMockUser
    void exportEngineers_overMaxRowsFailsBeforeResponseHeaders() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(50001L);

        mockMvc.perform(get("/api/engineers/export"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void exportEngineers_rejectsHighRiskCandidateSetBeforeScoring() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(100001L);

        mockMvc.perform(get("/api/engineers/export").param("riskLevel", "high"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(retentionRiskService, never()).scoreBatchFor(any());
    }

    @Test
    @WithMockUser
    void exportEngineers_highRiskScansBatchesForCountThenStreamsBatches() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(1201L);
        when(retentionRiskService.scoreBatchFor(any())).thenAnswer(invocation -> {
            List<Engineer> rows = invocation.getArgument(0);
            Map<Long, RetentionRiskDto> result = new HashMap<>();
            rows.forEach(engineer -> result.put(engineer.getId(),
                    RetentionRiskDto.builder().engineerId(engineer.getId()).highRisk(true).build()));
            return result;
        });

        AtomicInteger fetchCount = new AtomicInteger();
        when(engineerService.list(any(Wrapper.class))).thenAnswer(invocation -> {
            int batch = fetchCount.incrementAndGet();
            int phaseBatch = ((batch - 1) % 3) + 1;
            int start = phaseBatch == 1 ? 1201 : phaseBatch == 2 ? 701 : 201;
            int size = phaseBatch == 3 ? 201 : 500;
            List<Engineer> rows = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Engineer engineer = new Engineer();
                engineer.setId((long) (start - i));
                rows.add(engineer);
            }
            return rows;
        });

        List<Long> exportedIds = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<List<Engineer>> batches = invocation.getArgument(0);
            for (List<Engineer> batch : batches) {
                batch.forEach(engineer -> exportedIds.add(engineer.getId()));
            }
            return null;
        }).when(excelExportService).streamEngineers(any(Iterable.class), any(OutputStream.class));

        mockMvc.perform(get("/api/engineers/export").param("riskLevel", "high"))
                .andExpect(status().isOk());

        assertEquals(6, fetchCount.get());
        assertEquals(1201, exportedIds.size());
        assertEquals(1201L, exportedIds.get(0));
        assertEquals(1L, exportedIds.get(1200));
    }

    @Test
    @WithMockUser
    void exportEngineers_thirdConcurrentRequestReturns429() throws Exception {
        ExportConcurrencyLimiter.configure(2);
        ExportConcurrencyLimiter.acquire();
        ExportConcurrencyLimiter.acquire();
        try {
            mockMvc.perform(get("/api/engineers/export"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value(429));
        } finally {
            ExportConcurrencyLimiter.release();
            ExportConcurrencyLimiter.release();
        }
    }

    @Test
    @WithMockUser
    void exportEngineers_releasesPermitWhenStreamingFails() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(0L);
        doThrow(new RuntimeException("stream failed"))
                .when(excelExportService).streamEngineers(any(Iterable.class), any(OutputStream.class));

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mockMvc.perform(get("/api/engineers/export")));

        doNothing().when(excelExportService)
                .streamEngineers(any(Iterable.class), any(OutputStream.class));
        mockMvc.perform(get("/api/engineers/export"))
                .andExpect(status().isOk());
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
