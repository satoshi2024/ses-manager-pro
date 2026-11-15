package com.ses.controller.page;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 機能スイッチNF06がOFF（本番の既定）のとき、取込センターの画面・API・サイドバー導線が管理者にも出ないこと（W3-12）。
 * OFFの値だけが既定のtest profileと異なるため、専用のcontextになる。読取りだけで、{@code @Transactional} でrollbackする。
 */
@SpringBootTest(properties = "app.features.nf06.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImportCenterPageFeatureOffTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "nf06-off-admin", roles = "管理者")
    void NF06がOFFなら管理者でも画面とAPIが404になりサイドバーにも出ない() throws Exception {
        mockMvc.perform(get("/import-center"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/import-center/sources"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        String other = mockMvc.perform(get("/lifecycle"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(other).contains("sidebar-footer").doesNotContain("href=\"/import-center\"");
    }

    @Test
    void 未認証でもOFFの画面とAPIは同じ404になる() throws Exception {
        mockMvc.perform(get("/import-center"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/import-center/jobs/1"))
                .andExpect(status().isNotFound());
    }
}
