package com.ses.web;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ACC-OPS-P0-001: 匿名プローブ到達性。
 * ALB/ECS は /actuator/health/readiness・liveness を用いる。
 * 集約 /actuator/health は mail 等の indicator 次第で 200/503 になり得る（恒常 200 を要求しない）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("ACC-OPS-P0-001 Actuator Health & Probes 門禁テスト")
class ActuatorHealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未認証で liveness / readiness が 200 UP、集約 health は status のみ（200 または 503）")
    void healthEndpoint_unauthenticated_returnsStatusUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(Matchers.anyOf(Matchers.is(200), Matchers.is(503))))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
