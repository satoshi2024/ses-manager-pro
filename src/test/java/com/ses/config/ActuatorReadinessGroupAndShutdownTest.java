package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REV-ECS-P1-001 / P2-003: readiness グループに db を含め、graceful shutdown を有効化する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActuatorReadinessGroupAndShutdownTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    @Autowired
    private Environment environment;

    @Test
    void readinessグループはreadinessStateとdbを含む() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");
        assertNotNull(readiness, "readiness health group must exist");
        assertTrue(readiness.isMember("readinessState"), "readiness must include readinessState");
        assertTrue(readiness.isMember("db"), "readiness must include db");
    }

    @Test
    void livenessグループはlivenessStateのみを意図する() {
        HealthEndpointGroup liveness = healthEndpointGroups.get("liveness");
        assertNotNull(liveness, "liveness health group must exist");
        assertTrue(liveness.isMember("livenessState"), "liveness must include livenessState");
        assertFalse(liveness.isMember("db"), "liveness must NOT include db");
    }

    @Test
    void readinessエンドポイントは匿名でUPを返す() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void gracefulShutdownが有効() {
        assertEquals("graceful", environment.getProperty("server.shutdown"));
        assertNotNull(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
    }
}
