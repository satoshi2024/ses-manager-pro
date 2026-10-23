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
