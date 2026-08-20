package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T110 L0: V108 が G0（tenant延期）と raw prompt 禁止を文書どおり守っていること。
 */
class AiFeedbackSchemaContractTest {

    private static final Path V108 = Path.of(
            "src", "main", "resources", "db", "migration", "V108__ai_feedback_learning.sql");

    @Test
    void v108KeepsActivePartialUniqueOutcomeIdempotencyAndNoTenantRawPrompt() throws Exception {
        String sql = Files.readString(V108, StandardCharsets.UTF_8);
        assertTrue(sql.contains("uk_ai_artifact_active_use_case"));
        assertTrue(sql.contains("uk_ai_outcome_idempotent"));
        assertTrue(sql.contains("original_end_date"));
        assertTrue(sql.contains("redacted_summary_json"));
        assertTrue(sql.contains("status_version"));
        assertFalse(java.util.regex.Pattern.compile("(?i)tenant_id\\s+(BIGINT|VARCHAR|INT)")
                .matcher(sql).find(), "tenant_id 列を作ってはならない");
        assertFalse(java.util.regex.Pattern.compile("(?i)\\braw_prompt\\s+(TEXT|JSON|CLOB|VARCHAR)")
                .matcher(sql).find());
        assertFalse(java.util.regex.Pattern.compile("(?i)\\brequest_params\\s+(TEXT|JSON|CLOB|VARCHAR)")
                .matcher(sql).find());
        assertTrue(sql.contains("prompt_version"));
        assertTrue(sql.contains("G0"));
    }
}
