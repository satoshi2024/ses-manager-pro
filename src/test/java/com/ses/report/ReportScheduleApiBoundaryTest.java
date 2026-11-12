package com.ses.report;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.controller.api.ManagementReportScheduleApiController;
import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.entity.ReportSchedule;
import com.ses.service.report.ReportScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * schedule APIとentityのclaim token境界（scheduler-claim-fence-plan.md §7-7）。
 * tokenはJSON応答・toString・HTTP入力のいずれからも観測/注入できず、lease列はentity更新から除外される。
 */
class ReportScheduleApiBoundaryTest {

    private static final String TOKEN = "3f1c2b9a-7d4e-4f00-8a11-0123456789ab";

    private ReportScheduleService scheduleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scheduleService = mock(ReportScheduleService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ManagementReportScheduleApiController(scheduleService)).build();
    }

    private ReportSchedule claimedSchedule() {
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(11L);
        schedule.setTenantId("default");
        schedule.setTemplateVersionId(3L);
        schedule.setCronExpression("0 0 0 1 * *");
        schedule.setTimezoneId("Asia/Tokyo");
        schedule.setEnabled(1);
        schedule.setNextRunAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        schedule.setProcessingLogicalRunAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        schedule.setProcessingClaimedAt(LocalDateTime.of(2026, 9, 1, 0, 1));
        schedule.setProcessingClaimToken(TOKEN);
        schedule.setFailureCount(0);
        return schedule;
    }

    @Test
    void list_enable_disableの応答はclaim_tokenを含まない() throws Exception {
        when(scheduleService.list()).thenReturn(List.of(claimedSchedule()));
        when(scheduleService.setEnabled(11L, true)).thenReturn(claimedSchedule());
        when(scheduleService.setEnabled(11L, false)).thenReturn(claimedSchedule());

        MvcResult list = mockMvc.perform(get("/api/management-reports/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].processingClaimToken").doesNotExist())
                .andReturn();
        MvcResult enable = mockMvc.perform(post("/api/management-reports/schedules/11/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingClaimToken").doesNotExist())
                .andReturn();
        MvcResult disable = mockMvc.perform(post("/api/management-reports/schedules/11/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingClaimToken").doesNotExist())
                .andReturn();

        for (MvcResult result : List.of(list, enable, disable)) {
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain(TOKEN).doesNotContain("processingClaimToken")
                    .doesNotContain("processing_claim_token");
        }
    }

    @Test
    void create要求のclaim_tokenやlease列はserviceへ渡らず応答にも出ない() throws Exception {
        when(scheduleService.create(any())).thenReturn(claimedSchedule());

        MvcResult result = mockMvc.perform(post("/api/management-reports/schedules")
                        .contentType("application/json")
                        .content("{\"templateVersionId\":3,\"cronExpression\":\"0 0 0 1 * *\","
                                + "\"processingClaimToken\":\"" + TOKEN + "\","
                                + "\"processingClaimedAt\":\"2026-09-01T00:01:00\","
                                + "\"failureCount\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingClaimToken").doesNotExist())
                .andReturn();

        ArgumentCaptor<ReportScheduleCreateRequest> request = ArgumentCaptor.forClass(ReportScheduleCreateRequest.class);
        verify(scheduleService).create(request.capture());
        assertThat(request.getValue().getTemplateVersionId()).isEqualTo(3L);
        assertThat(request.getValue().toString()).doesNotContain(TOKEN);
        // 作成要求DTOはowner/lease/retry状態を表す項目を持たない（HTTPから設定できる経路が無い）。
        assertThat(Arrays.stream(ReportScheduleCreateRequest.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase(Locale.ROOT)))
                .noneMatch(name -> name.contains("token") || name.contains("claim") || name.contains("processing")
                        || name.contains("retry") || name.contains("failure"));
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(TOKEN);
    }

    @Test
    void entityのtoStringとJSON入出力はclaim_tokenを扱わない() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ReportSchedule schedule = claimedSchedule();

        assertThat(schedule.toString()).doesNotContain(TOKEN);
        assertThat(objectMapper.writeValueAsString(schedule)).doesNotContain(TOKEN)
                .doesNotContain("processingClaimToken");
        ReportSchedule parsed = objectMapper.readValue(
                "{\"id\":11,\"processingClaimToken\":\"" + TOKEN + "\"}", ReportSchedule.class);
        assertThat(parsed.getProcessingClaimToken()).isNull();
    }

    @Test
    void lease_retry_token_failure列はentity由来のINSERTとUPDATEから除外される() throws Exception {
        for (String field : List.of("retryScheduledAt", "processingLogicalRunAt", "processingClaimedAt",
                "processingClaimToken", "failureCount")) {
            TableField annotation = tableField(field);
            assertThat(annotation.insertStrategy()).as(field + " insertStrategy").isEqualTo(FieldStrategy.NEVER);
            assertThat(annotation.updateStrategy()).as(field + " updateStrategy").isEqualTo(FieldStrategy.NEVER);
        }
        // next_run_atは作成時に必要なためINSERTは許可し、UPDATEだけを除外する。
        for (String field : List.of("nextRunAt", "lastRunAt")) {
            TableField annotation = tableField(field);
            assertThat(annotation.insertStrategy()).as(field + " insertStrategy").isEqualTo(FieldStrategy.DEFAULT);
            assertThat(annotation.updateStrategy()).as(field + " updateStrategy").isEqualTo(FieldStrategy.NEVER);
        }
        assertThat(tableField("processingClaimToken").value()).isEqualTo("processing_claim_token");
    }

    private TableField tableField(String name) throws NoSuchFieldException {
        Field field = ReportSchedule.class.getDeclaredField(name);
        TableField annotation = field.getAnnotation(TableField.class);
        assertThat(annotation).as(name + "の@TableField").isNotNull();
        return annotation;
    }
}
