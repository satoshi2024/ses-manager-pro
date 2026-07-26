package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.entity.Engineer;
import com.ses.dto.engineerfollowup.RetentionRiskDto;
import com.ses.service.EngineerService;
import com.ses.service.RetentionRiskService;
import com.ses.service.ProjectService;
import com.ses.service.csv.EngineerCsvService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSV出力のkeysetページングと上限チェックを検証する。
 */
@WebMvcTest(CsvApiController.class)
class CsvApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineerService engineerService;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private EngineerCsvService engineerCsvService;

    @MockBean
    private RetentionRiskService retentionRiskService;

    @MockBean
    private DataScopeService dataScopeService;

    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Engineer.class);
    }

    @Test
    @WithMockUser
    void exportEngineersCsv_consumesThreeKeysetBatchesWithoutDuplicates() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(1201L);
        when(retentionRiskService.scoreBatchFor(any())).thenAnswer(invocation -> {
            List<Engineer> rows = invocation.getArgument(0);
            Map<Long, RetentionRiskDto> result = new HashMap<>();
            rows.forEach(engineer -> result.put(engineer.getId(),
                    RetentionRiskDto.builder().engineerId(engineer.getId()).highRisk(true).build()));
            return result;
        });
        AtomicInteger fetchCount = new AtomicInteger();
        List<Wrapper<Engineer>> batchWrappers = new ArrayList<>();
        when(engineerService.list(any(Wrapper.class))).thenAnswer(invocation -> {
            batchWrappers.add(invocation.getArgument(0));
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
            List<Engineer> rows = invocation.getArgument(0);
            rows.forEach(engineer -> exportedIds.add(engineer.getId()));
            return null;
        }).when(engineerCsvService).writeCsvRows(any(), any(Writer.class));

        mockMvc.perform(get("/api/engineers/export-csv")
                        .param("fullName", "山田")
                        .param("status", "稼動中")
                        .param("employmentType", "正社員")
                        .param("salesUserId", "20")
                        .param("riskLevel", "high"))
                .andExpect(status().isOk());

        // riskLevel=highは候補上限を確認した後、判定走査と出力走査を各3バッチで行う。
        assertEquals(6, fetchCount.get());
        assertEquals(1201, exportedIds.size());
        assertEquals(1201L, exportedIds.get(0));
        assertEquals(1L, exportedIds.get(1200));
        assertEquals(1201, exportedIds.stream().distinct().count());
        for (Wrapper<Engineer> wrapper : batchWrappers) {
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("full_name"), sql);
            assertTrue(sql.contains("status"), sql);
            assertTrue(sql.contains("employment_type"), sql);
            assertTrue(sql.contains("sales_user_id"), sql);
        }
        // 2回目以降のkeyset条件は直前バッチの最終IDをバインドする。
        assertTrue(batchWrappers.get(1).getSqlSegment().contains("id <"));
        assertTrue(batchWrappers.get(2).getSqlSegment().contains("id <"));
        assertTrue(batchWrappers.get(4).getSqlSegment().contains("id <"));
        assertTrue(batchWrappers.get(5).getSqlSegment().contains("id <"));
        verify(engineerCsvService, times(3)).writeCsvRows(any(), any(Writer.class));
    }

    @Test
    @WithMockUser
    void exportEngineersCsv_rejectsHighRiskCandidateSetBeforeScoring() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(100001L);

        mockMvc.perform(get("/api/engineers/export-csv").param("riskLevel", "high"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        org.mockito.Mockito.verifyNoInteractions(retentionRiskService);
    }

    @Test
    @WithMockUser
    void exportEngineersCsv_appliesDataScopeBeforeCounting() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of(7L));
        when(engineerService.count(any(Wrapper.class))).thenReturn(0L);

        mockMvc.perform(get("/api/engineers/export-csv"))
                .andExpect(status().isOk());

        ArgumentCaptor<Wrapper<Engineer>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(engineerService).count(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("id IN"));
    }

    @Test
    @WithMockUser
    void exportEngineersCsv_rejectsRowsAboveConfiguredMaximum() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(50001L);

        mockMvc.perform(get("/api/engineers/export-csv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void exportEngineersCsv_allowsExactlyConfiguredMaximum() throws Exception {
        when(engineerService.count(any(Wrapper.class))).thenReturn(50000L);
        when(engineerService.list(any(Wrapper.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/engineers/export-csv"))
                .andExpect(status().isOk());

        verify(engineerCsvService).writeCsvHeader(any(Writer.class));
    }
}
