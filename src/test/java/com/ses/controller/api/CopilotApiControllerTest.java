package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.CopilotSummaryView;
import com.ses.dto.ai.CopilotQueryResult;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.CopilotQueryService;
import com.ses.service.ai.copilot.citation.CitationAuthorizationService;
import com.ses.service.ai.copilot.result.CopilotFreshnessInfo;
import com.ses.service.ai.copilot.result.CopilotLimitInfo;
import com.ses.service.ai.copilot.result.CopilotScopeInfo;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.MetricState;
import com.ses.service.ai.copilot.result.MetricUnit;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private CopilotQueryService copilotQueryService;

    @MockBean
    private CitationAuthorizationService citationAuthorizationService;

    @Test
    void flagが無効なら503() throws Exception {
        when(copilotQueryService.query(anyString()))
                .thenThrow(new BusinessException(503, "経営コパイロットは現在無効化されています。"));

        mockMvc.perform(post("/api/copilot/query")
                        .contentType(APPLICATION_JSON)
                        .content("{\"question\":\"今月の稼働率\"}"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void typedResultとcitationを返す() throws Exception {
        Instant now = Instant.now();
        TypedResultEnvelope envelope = new TypedResultEnvelope(
                "dashboard.utilization-forecast",
                "nf08-provisional-1",
                "nf08-result-1",
                now,
                now,
                "Asia/Tokyo",
                new CopilotScopeInfo("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash"),
                List.of(new MetricValue("forecast.utilization.2026-09", BigDecimal.valueOf(75.0), null,
                        MetricUnit.PERCENT, MetricState.VALUE, "2026-09", MetricBasis.FORECAST, 1)),
                List.of(),
                new CopilotFreshnessInfo(now, false, MetricBasis.FORECAST),
                List.of("dashboard.utilization-forecast"),
                new CopilotLimitInfo(200, false),
                "1");
        when(copilotQueryService.query(anyString())).thenReturn(
                new CopilotQueryResult(
                        "dashboard.utilization-forecast",
                        "nf08-provisional-1",
                        "nf08-result-1",
                        "SUCCEEDED",
                        "ok",
                        "trace-1",
                        10L,
                        List.of("dashboard.utilization-forecast"),
                        List.of(new ResolvedCitationDto("dashboard.utilization-forecast", "稼働率予測", "/dashboard", true)),
                        envelope,
                        new CopilotSummaryView(
                                "登録された指標キーを確認しました。",
                                List.of("forecast.utilization.2026-09"),
                                "SUCCEEDED",
                                "mock",
                                true)));

        mockMvc.perform(post("/api/copilot/query")
                        .contentType(APPLICATION_JSON)
                        .content("{\"question\":\"今月の稼働率\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.queryId").value("dashboard.utilization-forecast"))
                .andExpect(jsonPath("$.data.result.values[0].key").value("forecast.utilization.2026-09"))
                .andExpect(jsonPath("$.data.citations[0].available").value(true))
                .andExpect(jsonPath("$.data.summary.available").value(true))
                .andExpect(jsonPath("$.data.summary.text").exists());
    }

    @Test
    void citation再認可失敗はavailableFalse() throws Exception {
        when(citationAuthorizationService.authorize("management-accounting.summary"))
                .thenReturn(ResolvedCitationDto.unavailable("management-accounting.summary"));

        mockMvc.perform(get("/api/copilot/citations")
                        .param("key", "management-accounting.summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.route").doesNotExist());
    }
}
