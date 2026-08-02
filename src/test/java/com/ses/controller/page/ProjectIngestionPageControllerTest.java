package com.ses.controller.page;

import com.ses.entity.ProjectIngestion;
import com.ses.service.ProjectIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProjectIngestionPageController.class)
@DisplayName("案件メール取込 画面コントローラーテスト")
class ProjectIngestionPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectIngestionService projectIngestionService;

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("有効なjobIdでreview画面が正常描画されjobIdが埋め込まれる")
    void reviewRendersJobIdWithoutForbiddenUtility() throws Exception {
        ProjectIngestion job = new ProjectIngestion();
        job.setId(99L);
        when(projectIngestionService.getById(99L)).thenReturn(job);

        mockMvc.perform(get("/project-ingestion/review/99"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("jobId", 99L))
                .andExpect(content().string(containsString("const JOB_ID = 99;")))
                .andExpect(content().string(not(containsString("#request"))));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("存在しないjobIdは404エラーとなる")
    void reviewReturns404WhenNotFound() throws Exception {
        when(projectIngestionService.getById(999L)).thenReturn(null);

        mockMvc.perform(get("/project-ingestion/review/999"))
                .andExpect(status().isNotFound());
    }
}
