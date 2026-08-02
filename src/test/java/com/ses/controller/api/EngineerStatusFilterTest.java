package com.ses.controller.api;

import com.ses.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 要員statusのDB正規値と200名fixture件数のAPI契約を固定する。 */
class EngineerStatusFilterTest extends BaseIntegrationTest {

    private static final String FIXTURE_PREFIX = "R3-B1-";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private com.ses.service.EngineerSalesService engineerSalesService;
    @MockBean
    private com.ses.service.RetentionRiskService retentionRiskService;
    @MockBean
    private com.ses.service.EngineerAccountLinkService engineerAccountLinkService;

    @BeforeEach
    void setUp() {
        when(engineerSalesService.mapPrimaryByEngineerIds(any())).thenReturn(Map.of());
        when(retentionRiskService.scoreBatchFor(any())).thenReturn(Map.of());
        when(engineerAccountLinkService.findLinkedEngineerIds(any())).thenReturn(Set.of());
        List<Object[]> rows = new ArrayList<>();
        addRows(rows, "稼動中", 131);
        addRows(rows, "Bench", 32);
        addRows(rows, "提案中", 22);
        addRows(rows, "退場予定", 15);
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_engineer (full_name, employment_type, status, deleted_flag) VALUES (?, '正社員', ?, 0)",
                rows);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void Benchは32件を返す() throws Exception {
        mockMvc.perform(get("/api/engineers")
                        .param("fullName", FIXTURE_PREFIX)
                        .param("status", "Bench")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(32))
                .andExpect(jsonPath("$.data.pages").value(4));
    }

    @ParameterizedTest
    @CsvSource({"稼動中,131", "提案中,22", "退場予定,15", "未知,0"})
    @WithMockUser(roles = "管理者")
    void status別件数と未知値0件を維持する(String filter, long expected) throws Exception {
        mockMvc.perform(get("/api/engineers")
                        .param("fullName", FIXTURE_PREFIX)
                        .param("status", filter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(expected));
    }

    private void addRows(List<Object[]> rows, String status, int count) {
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[]{FIXTURE_PREFIX + status + "-" + i, status});
        }
    }
}
