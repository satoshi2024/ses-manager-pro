package com.ses.controller.page;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.entity.ResumeIngestion;
import com.ses.service.ResumeIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

    private org.springframework.test.web.servlet.request.RequestPostProcessor internalAdmin() {
        SysUser user = new SysUser();
        user.setUsername("admin");
        user.setTenantId("default");
        user.setRole("管理者");
        user.setStatus(1);
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("有効なjobIdでreview画面が正常描画されjobIdが埋め込まれる")
    void reviewRendersJobIdWithoutForbiddenUtility() throws Exception {
        ResumeIngestion job = new ResumeIngestion();
        job.setId(88L);
        when(resumeIngestionService.getForCurrentTenant(88L)).thenReturn(job);

        mockMvc.perform(get("/resume-ingestion/review/88").with(internalAdmin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("jobId", 88L))
                .andExpect(content().string(containsString("const JOB_ID = 88;")))
                .andExpect(content().string(not(containsString("#request"))));
    }

    @Test
    @DisplayName("存在しないjobIdは404エラーとなる")
    void reviewReturns404WhenNotFound() throws Exception {
        when(resumeIngestionService.getForCurrentTenant(888L)).thenReturn(null);

        mockMvc.perform(get("/resume-ingestion/review/888").with(internalAdmin()))
                .andExpect(status().isNotFound());
    }
}
