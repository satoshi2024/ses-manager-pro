package com.ses.controller.page;

import com.ses.entity.ResumeIngestion;
import com.ses.service.ResumeIngestionService;
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

@WebMvcTest(controllers = ResumeIngestionPageController.class)
@DisplayName("スキルシート取込 画面コントローラーテスト")
class ResumeIngestionPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResumeIngestionService resumeIngestionService;

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("有効なjobIdでreview画面が正常描画されjobIdが埋め込まれる")
    void reviewRendersJobIdWithoutForbiddenUtility() throws Exception {
        ResumeIngestion job = new ResumeIngestion();
        job.setId(88L);
        when(resumeIngestionService.getById(88L)).thenReturn(job);

        mockMvc.perform(get("/resume-ingestion/review/88"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("jobId", 88L))
                .andExpect(content().string(containsString("const JOB_ID = 88;")))
                .andExpect(content().string(not(containsString("#request"))));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("存在しないjobIdは404エラーとなる")
    void reviewReturns404WhenNotFound() throws Exception {
        when(resumeIngestionService.getById(888L)).thenReturn(null);

        mockMvc.perform(get("/resume-ingestion/review/888"))
                .andExpect(status().isNotFound());
    }
}
