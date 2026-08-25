package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prometheus メトリクスエンドポイントの公開制御（ACC-OPS-P1-002）。
 *
 * <ul>
 *   <li>匿名 GET /actuator/prometheus → 401/403（permitAll にしない）</li>
 *   <li>管理者 → 200 かつ scrape 本文（{@code # HELP} 等）</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorPrometheusEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheus_匿名では401または403() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401),
                        org.hamcrest.Matchers.is(403))));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void prometheus_管理者は200でスクレイプ本文を返す() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Prometheus テキスト形式の scrape 本文であること")
                .containsAnyOf("# HELP", "# TYPE", "jvm_");
    }
}
