package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.BadSqlGrammarException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ses.common.result.ApiResult;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(BatchOperationApiControllerTest.SqlDiagnosticConfiguration.class)
public class BatchOperationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    void testBatchPreview_admin_success() throws Exception {
        mockMvc.perform(post("/api/batch-operations/engineers/preview")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"ids\":[1,2],\"status\":\"稼動中\"}"))
                .andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlDiagnosticConfiguration {
        @Bean
        SqlDiagnosticAdvice sqlDiagnosticAdvice() {
            return new SqlDiagnosticAdvice();
        }
    }

    @Slf4j
    @RestControllerAdvice(basePackages = "com.ses.controller.api")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class SqlDiagnosticAdvice {
        private static final Pattern SAFE_OBJECT = Pattern.compile(
                "(?i)(?:table|column|view|sequence)\\s+\"([A-Za-z0-9_.$]+)\"");

        @ExceptionHandler(BadSqlGrammarException.class)
        ResponseEntity<ApiResult<Void>> diagnose(BadSqlGrammarException exception) {
            SQLException sqlException = findSqlException(exception);
            String safeObject = extractSafeObject(sqlException == null ? null : sqlException.getMessage());
            log.error("TEST_ONLY SQL error diagnostics: sqlState={} vendorCode={} object={}",
                    sqlException == null ? "unknown" : sqlException.getSQLState(),
                    sqlException == null ? 0 : sqlException.getErrorCode(), safeObject);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResult.error(500, "test-only SQL diagnostic captured"));
        }

        private static SQLException findSqlException(Throwable cause) {
            for (Throwable current = cause; current != null; current = current.getCause()) {
                if (current instanceof SQLException sqlException) return sqlException;
            }
            return null;
        }

        private static String extractSafeObject(String message) {
            if (message == null) return "unknown";
            Matcher matcher = SAFE_OBJECT.matcher(message);
            return matcher.find() ? matcher.group(1) : "unknown";
        }
    }
}
