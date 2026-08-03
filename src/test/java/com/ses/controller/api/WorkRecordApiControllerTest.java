package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.workrecord.WorkRecordSaveRequest;
import com.ses.entity.WorkRecord;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private WorkRecordGridDto gridItem(Long workRecordId, String status) {
        WorkRecordGridDto dto = new WorkRecordGridDto();
        dto.setContractId(workRecordId == null ? null : workRecordId + 100);
        dto.setContractNo("C-" + workRecordId);
        dto.setEngineerName("要員" + workRecordId);
        dto.setWorkRecordId(workRecordId);
        dto.setStatus(status);
        return dto;
    }

    private WorkRecord workRecordWithUpdatedAt(Long id, LocalDateTime updatedAt) {
        WorkRecord w = new WorkRecord();
        w.setId(id);
        w.setUpdatedAt(updatedAt);
        return w;
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPendingApprovalSummary_countsSubmittedAndSortsByDaysPendingDesc() throws Exception {
        // 提出済(id=1, 2)・確定(id=3, 集計対象外)・未提出(workRecordId無し, 集計対象外) が混在するグリッド。
        when(workRecordService.monthlyGrid(anyString())).thenReturn(List.of(
                gridItem(1L, "提出済"),
                gridItem(2L, "提出済"),
                gridItem(3L, "確定"),
                gridItem(null, null)
        ));
        when(workRecordService.listByIds(any())).thenReturn(List.of(
                workRecordWithUpdatedAt(1L, LocalDateTime.now().minusDays(2)),
                workRecordWithUpdatedAt(2L, LocalDateTime.now().minusDays(5))
        ));

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
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPendingApprovalSummary_noSubmittedRecords_returnsZeroWithoutCallingListByIds() throws Exception {
        when(workRecordService.monthlyGrid(anyString())).thenReturn(List.of(
                gridItem(3L, "確定"),
                gridItem(null, null)
        ));

        mockMvc.perform(get("/api/work-records/pending-approval-summary").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.submittedCount").value(0))
                .andExpect(jsonPath("$.data.maxPendingDays").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        org.mockito.Mockito.verify(workRecordService, org.mockito.Mockito.never()).listByIds(any());
    }
}
