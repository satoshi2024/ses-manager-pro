package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.workrecord.PendingApprovalItemDto;
import com.ses.dto.workrecord.PendingApprovalSummaryDto;
import com.ses.dto.workrecord.WorkRecordSaveRequest;
import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkRecordApiController.class)
@ActiveProfiles("test")
public class WorkRecordApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkRecordService workRecordService;

    @MockBean
    private com.ses.service.TimesheetPdfService timesheetPdfService;

    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;

    @Test
    @WithMockUser(roles = "管理者")
    void testSaveHours_workMonth不正() throws Exception {
        WorkRecordSaveRequest req = new WorkRecordSaveRequest();
        req.setContractId(1L);
        req.setWorkMonth("2026/07"); // 不正形式
        req.setActualHours(new BigDecimal("150"));

        // バリデーション違反は HTTP 400 と ApiResult(code=400) で返す。
        mockMvc.perform(put("/api/work-records")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("対象月はYYYY-MM形式で指定してください")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testSaveHours_actualHours負数() throws Exception {
        WorkRecordSaveRequest req = new WorkRecordSaveRequest();
        req.setContractId(1L);
        req.setWorkMonth("2026-07");
        req.setActualHours(new BigDecimal("-1"));

        mockMvc.perform(put("/api/work-records")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("実績時間は0以上を指定してください")));
    }

    // ===== 承認滞留の可視化（トラックA3・読み取り専用） =====

    private PendingApprovalItemDto pendingItem(Long workRecordId, int daysPending) {
        PendingApprovalItemDto dto = new PendingApprovalItemDto();
        dto.setWorkRecordId(workRecordId);
        dto.setContractId(workRecordId + 100);
        dto.setContractNo("C-" + workRecordId);
        dto.setEngineerName("要員" + workRecordId);
        dto.setDaysPending(daysPending);
        return dto;
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPendingApprovalSummary_countsSubmittedAndSortsByDaysPendingDesc() throws Exception {
        when(workRecordService.pendingApprovalSummary(eq("2026-08"), isNull(), isNull()))
                .thenReturn(new PendingApprovalSummaryDto(2, 5, List.of(
                        pendingItem(2L, 5),
                        pendingItem(1L, 2)
                )));

        mockMvc.perform(get("/api/work-records/pending-approval-summary").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.submittedCount").value(2))
                .andExpect(jsonPath("$.data.maxPendingDays").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                // 滞留日数の降順（長い順）で並ぶこと。
                .andExpect(jsonPath("$.data.items[0].workRecordId").value(2))
                .andExpect(jsonPath("$.data.items[0].daysPending").value(5))
                .andExpect(jsonPath("$.data.items[1].workRecordId").value(1))
                .andExpect(jsonPath("$.data.items[1].daysPending").value(2));

        verify(workRecordService, never()).monthlyGrid(anyString());
        verify(workRecordService, never()).listByIds(any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPendingApprovalSummary_noSubmittedRecords_returnsZero() throws Exception {
        when(workRecordService.pendingApprovalSummary(eq("2026-08"), isNull(), isNull()))
                .thenReturn(new PendingApprovalSummaryDto(0, null, List.of()));

        mockMvc.perform(get("/api/work-records/pending-approval-summary").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.submittedCount").value(0))
                .andExpect(jsonPath("$.data.maxPendingDays").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        verify(workRecordService, never()).monthlyGrid(anyString());
        verify(workRecordService, never()).listByIds(any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPendingApprovalSummary_forwardsPageParamsWithoutCallingMonthlyGrid() throws Exception {
        when(workRecordService.pendingApprovalSummary(eq("2026-08"), eq(2L), eq(5L)))
                .thenReturn(new PendingApprovalSummaryDto(12, 9, List.of(pendingItem(9L, 9))));

        mockMvc.perform(get("/api/work-records/pending-approval-summary")
                        .param("month", "2026-08")
                        .param("current", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.submittedCount").value(12))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        verify(workRecordService).pendingApprovalSummary("2026-08", 2L, 5L);
        verify(workRecordService, never()).monthlyGrid(anyString());
    }
}
