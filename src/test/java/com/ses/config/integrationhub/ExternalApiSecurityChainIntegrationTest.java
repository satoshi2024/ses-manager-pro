package com.ses.config.integrationhub;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** default-offでも専用deny-only chainがinternal/portalへfall-throughしないことを固定する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExternalApiSecurityChainIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    /** N5-P2-1: 無効時は監査tableへ書かず、metricだけを増やす（保存期限の無いtableを外部から増やさない）。 */
    @Test
    void disabledApprovedPathReturnsStableNotFoundJsonWithoutAuditRows() throws Exception {
        int auditBefore = auditCount();
        double suppressedBefore = suppressedDisabledCount();
        mockMvc.perform(get("/external-api/v1/projects"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
        assertEquals(auditBefore, auditCount());
        mockMvc.perform(get("/external-api/v1"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertEquals(auditBefore, auditCount());
        assertEquals(suppressedBefore + 2.0, suppressedDisabledCount());
    }

    @Test
    void disabledCommandAndUnknownPathCannotFallThroughToInternalChain() throws Exception {
        int auditBefore = auditCount();
        double suppressedBefore = suppressedDisabledCount();
        mockMvc.perform(post("/external-api/v1/projects"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/external-api/v1/not-approved"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertEquals(auditBefore, auditCount());
        assertEquals(suppressedBefore + 2.0, suppressedDisabledCount());
    }

    private double suppressedDisabledCount() {
        Counter counter = meterRegistry.find("integration_hub_external_api_audit_suppressed_total")
                .tag("reason", "PUBLIC_API_DISABLED").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void externalFiltersArePresentOnceInDedicatedChain() {
        var filters = filterChainProxy.getFilters("/external-api/v1/projects");
        assertTrue(filters != null);
        assertEquals(1, filters.stream().filter(filter -> filter instanceof ExternalApiAuditBoundary).count());
        assertEquals(1, filters.stream().filter(filter -> filter instanceof ExternalApiCorrelationFilter).count());
        assertEquals(1, filters.stream().filter(filter -> filter instanceof ExternalApiAuthenticationFilter).count());
        assertEquals(1, filters.stream().filter(filter -> filter instanceof ExternalApiAuthorizationFilter).count());
        assertTrue(filters.indexOf(filters.stream().filter(filter -> filter instanceof ExternalApiAuditBoundary).findFirst().orElseThrow())
                < filters.indexOf(filters.stream().filter(filter -> filter instanceof ExternalApiAuthenticationFilter).findFirst().orElseThrow()));
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_external_api_audit", Integer.class);
    }
}
