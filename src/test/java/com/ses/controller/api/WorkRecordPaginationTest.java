package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.WorkRecordGridDto;
import com.ses.service.TimesheetPdfService;
import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkRecordApiController.class)
@DisplayName("勤怠Grid pagination APIテスト (R3-012)")
class WorkRecordPaginationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkRecordService workRecordService;

    @MockBean
    private TimesheetPdfService timesheetPdfService;

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("grid/pageはPage<WorkRecordGridDto>を返す")
    void gridPageReturnsPagedResult() throws Exception {
        Page<WorkRecordGridDto> page = new Page<>(1, 50, 147);
        WorkRecordGridDto dto = new WorkRecordGridDto();
        dto.setContractId(101L);
        dto.setEngineerName("テスト 太郎");
        page.setRecords(List.of(dto));

        when(workRecordService.monthlyGridPage(eq("2026-08"), eq(1L), eq(50L), any(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/work-records/grid/page")
                        .param("month", "2026-08")
                        .param("current", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(147))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.records[0].engineerName").value("テスト 太郎"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("confirmMonthはpageパラメータを受けず月全体を確定する")
    void confirmMonthProcessesFullMonth() throws Exception {
        mockMvc.perform(post("/api/work-records/confirm")
                        .param("month", "2026-08")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workRecordService).confirmMonth("2026-08");
    }
}
