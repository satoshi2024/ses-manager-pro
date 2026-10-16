package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class BatchOperationApiControllerTest {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BatchOperationApiControllerTest.class);
    private static final Pattern SAFE_OBJECT = Pattern.compile(
            "(?i)(?:table|column|view|sequence)\\s+\"([A-Za-z0-9_.$]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    void testBatchPreview_admin_success() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batch-operations/engineers/preview")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"ids\":[1,2],\"status\":\"稼動中\"}"))
                .andReturn();
        logSqlDiagnostic(result.getResolvedException());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private static void logSqlDiagnostic(Throwable resolvedException) {
        if (resolvedException == null) return;
        for (Throwable current = resolvedException; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                Matcher matcher = SAFE_OBJECT.matcher(String.valueOf(sqlException.getMessage()));
                String safeObject = matcher.find() ? matcher.group(1) : "unknown";
                log.error("テスト専用SQL診断: sqlState={} vendorCode={} object={}",
                        sqlException.getSQLState(), sqlException.getErrorCode(), safeObject);
                return;
            }
        }
        log.error("テスト専用SQL診断: SQLExceptionが見つかりません exceptionType={}",
                resolvedException.getClass().getName());
    }
}
