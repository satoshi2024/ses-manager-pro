package com.ses.nf01.preflight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleAuthorizationPreflightUnitTest {
    @Test
    void incompleteOrFailingReportNeverPasses() {
        LifecycleAuthorizationPreflight.PreflightReport report =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        report.finding("mapping", "org", "BLOCKED_MAPPING", 1, List.of(), "review needed");
        assertEquals("BLOCKED_MAPPING", report.status());
        report.finding("execution", "query", "FAIL", 1, List.of(), "incomplete");
        assertEquals("FAIL", report.status());
        assertFalse(report.toJson().contains("jdbc:mysql"));
    }

    @Test
    void flywayHistoryMissingEmptyAndFailedNeverPass() {
        LifecycleAuthorizationPreflight.PreflightReport missing = new LifecycleAuthorizationPreflight.PreflightReport("input", "schema");
        missing.migrationHistory(false, List.of());
        assertEquals("FAIL", missing.status());
        assertTrue(missing.toJson().contains("FLYWAY_HISTORY_MISSING"));

        LifecycleAuthorizationPreflight.PreflightReport empty = new LifecycleAuthorizationPreflight.PreflightReport("input", "schema");
        empty.migrationHistory(true, List.of());
        assertEquals("FAIL", empty.status());
        assertTrue(empty.toJson().contains("FLYWAY_HISTORY_EMPTY"));

        LifecycleAuthorizationPreflight.PreflightReport failed = new LifecycleAuthorizationPreflight.PreflightReport("input", "schema");
        failed.migrationHistory(true, List.of("5:5:success=false"));
        assertEquals("FAIL", failed.status());
        assertTrue(failed.toJson().contains("FLYWAY_FAILED_MIGRATION"));
    }

    @Test
    void uniqueKeyPreconditionFailsOnDuplicateOrNullKeysAndRecordsExistingIndex() {
        LifecycleAuthorizationPreflight.PreflightReport satisfied =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(satisfied, "t_document_version",
                List.of("tenant_id", "id"), 0, 0, 0, List.of(), "uk_document_version_tenant_id");
        assertEquals("PASS", satisfied.status());
        assertTrue(satisfied.toJson().contains("\"checkId\":\"UNIQUE_KEY_PRECONDITION\""));
        assertTrue(satisfied.toJson().contains("t_document_version(tenant_id,id)"));
        assertTrue(satisfied.toJson().contains("existingUniqueIndex=uk_document_version_tenant_id"));

        LifecycleAuthorizationPreflight.PreflightReport duplicate =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(duplicate, "sys_user",
                List.of("tenant_id", "id"), 1, 0, 2, List.of("sample|a"), null);
        assertEquals("FAIL", duplicate.status());
        assertTrue(duplicate.toJson().contains("duplicateGroups=1"));
        assertTrue(duplicate.toJson().contains("existingUniqueIndex=none"));
        assertTrue(duplicate.toMarkdown().contains("sample\\|a"));

        LifecycleAuthorizationPreflight.PreflightReport nullKey =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(nullKey, "t_document",
                List.of("tenant_id", "id"), 0, 1, 1, List.of(), "none");
        assertEquals("FAIL", nullKey.status());
        assertTrue(nullKey.toJson().contains("nullKeyRows=1"));
    }

    @Test
    void unfinishedDualTaskCountIsInformationalAndNeverMasksBlockingFindings() {
        LifecycleAuthorizationPreflight.PreflightReport report =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUnfinishedDualTasks(report, 3, 2, List.of("s1"));
        assertEquals("PASS", report.status());
        String json = report.toJson();
        assertTrue(json.contains("\"checkId\":\"LIFECYCLE_DUAL_TASK_UNFINISHED\""));
        assertTrue(json.contains("\"rowCount\":3"));
        assertTrue(json.contains("inOpenCases=2"));
        assertTrue(json.contains("inTerminalOrMissingCases=1"));

        report.finding("mapping", "org", "BLOCKED_MAPPING", 1, List.of(), "review needed");
        assertEquals("BLOCKED_MAPPING", report.status());
        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(report, "sys_user",
                List.of("tenant_id", "id"), 0, 1, 1, List.of(), "none");
        assertEquals("FAIL", report.status());
    }

    @Test
    void jsonEscapesUnsafeCharactersAndMarkdownEscapesPipes() {
        LifecycleAuthorizationPreflight.PreflightReport report =
                new LifecycleAuthorizationPreflight.PreflightReport("head\"x", "schema|x");
        report.finding("check", "column", "PASS", 0, List.of("sample|1"), "line\nfeed");
        String json = report.toJson();
        assertEquals("PASS", report.status());
        assertTrue(json.contains("head\\\"x"));
        assertTrue(report.toMarkdown().contains("schema\\|x"));
    }
}
