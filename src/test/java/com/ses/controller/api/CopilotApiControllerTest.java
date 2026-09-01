package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.CopilotCatalogResult;
import com.ses.service.ai.copilot.CopilotCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CopilotApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class CopilotApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CopilotCatalogService copilotCatalogService;

    @Test
    void flagが無効なら503() throws Exception {
        when(copilotCatalogService.resolveAndRecord(anyString()))
                .thenThrow(new BusinessException(503, "経営コパイロットは現在無効化されています。"));

        mockMvc.perform(post("/api/copilot/query")
                        .contentType(APPLICATION_JSON)
                        .content("{\"question\":\"今月の稼働率\"}"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void catalog解決結果を返す() throws Exception {
        when(copilotCatalogService.resolveAndRecord(anyString())).thenReturn(
                new CopilotCatalogResult(
                        "dashboard.utilization-forecast",
                        "nf08-provisional-1",
                        "nf08-result-1",
                        "CATALOG_RESOLVED",
                        "ok",
                        "trace-1",
                        10L,
                        List.of("dashboard.utilization-forecast")));

        mockMvc.perform(post("/api/copilot/query")
                        .contentType(APPLICATION_JSON)
                        .content("{\"question\":\"今月の稼働率\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.queryId").value("dashboard.utilization-forecast"))
                .andExpect(jsonPath("$.data.traceId").value("trace-1"));
    }
}
