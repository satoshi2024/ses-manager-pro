package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
}
