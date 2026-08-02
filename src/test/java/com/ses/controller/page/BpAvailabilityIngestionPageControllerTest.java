package com.ses.controller.page;

import com.ses.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@DisplayName("BP要員取込レビュー画面")
class BpAvailabilityIngestionPageControllerTest extends BaseIntegrationTest {

    private static final long JOB_ID = 990005L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    @Sql(statements = "INSERT INTO t_bp_availability_ingestion "
            + "(id, file_ext, status, extracted_text, deleted_flag, created_by) "
            + "VALUES (990005, 'eml', '要確認', 'レビュー対象', 0, 1)")
    @DisplayName("有効なジョブはjobIdを埋め込んで描画する")
    void review_validJob_rendersJobId() throws Exception {
        mockMvc.perform(get("/bp-availability-ingestion/review/{id}", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("bp-availability-ingestion/review"))
                .andExpect(model().attribute("jobId", JOB_ID))
                .andExpect(content().string(containsString("const JOB_ID = 990005;")))
                .andExpect(content().string(not(containsString("#request"))))
                .andExpect(content().string(not(containsString("stacktrace"))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    @DisplayName("存在しないジョブは404を返す")
    void review_missingJob_returns404WithoutStacktrace() throws Exception {
        mockMvc.perform(get("/bp-availability-ingestion/review/{id}", 999999999L))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("stacktrace"))));
    }

    @Test
    @WithMockUser(username = "member", roles = "要員")
    @DisplayName("権限外ユーザーは403を返す")
    void review_outsidePermission_returns403WithoutStacktrace() throws Exception {
        mockMvc.perform(get("/bp-availability-ingestion/review/{id}", JOB_ID))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("stacktrace"))));
    }
}
