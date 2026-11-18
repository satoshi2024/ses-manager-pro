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
                List.of("tenant_id", "id"), 0, 0, 0, List.of(), "uk_document_version_tenant_id", true);
        assertEquals("PASS", satisfied.status());
        assertTrue(satisfied.toJson().contains("\"checkId\":\"UNIQUE_KEY_PRECONDITION\""));
        assertTrue(satisfied.toJson().contains("t_document_version(tenant_id,id)"));
        assertTrue(satisfied.toJson().contains("existingUniqueIndex=uk_document_version_tenant_id"));
        assertTrue(satisfied.toJson().contains("idIsPrimaryKey=true"));

        LifecycleAuthorizationPreflight.PreflightReport duplicate =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(duplicate, "sys_user",
                List.of("tenant_id", "id"), 1, 0, 2, List.of("sample|a"), null, false);
        assertEquals("FAIL", duplicate.status());
        assertTrue(duplicate.toJson().contains("duplicateGroups=1"));
        assertTrue(duplicate.toJson().contains("existingUniqueIndex=none"));
        assertTrue(duplicate.toJson().contains("idIsPrimaryKey=false"));
        assertTrue(duplicate.toMarkdown().contains("sample\\|a"));

        LifecycleAuthorizationPreflight.PreflightReport nullKey =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(nullKey, "t_document",
                List.of("tenant_id", "id"), 0, 1, 1, List.of(), "none", true);
        assertEquals("FAIL", nullKey.status());
        assertTrue(nullKey.toJson().contains("nullKeyRows=1"));
    }

    @Test
    void unfinishedDualTasksAndVersionlessEvidenceBlockOnlyWhenPresentAndNeverMaskFailures() {
        LifecycleAuthorizationPreflight.PreflightReport empty =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUnfinishedDualTasks(empty, 0, 0, List.of());
        LifecycleAuthorizationPreflight.recordVersionlessEvidenceLinks(empty, 0, List.of());
        assertEquals("PASS", empty.status(), "0件は人の判断を要しない");

        LifecycleAuthorizationPreflight.PreflightReport dual =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordUnfinishedDualTasks(dual, 3, 2, List.of("s1"));
        assertEquals("BLOCKED_MAPPING", dual.status(), "未終結DUALが残る場合はHRの移行判断を要する");
        String json = dual.toJson();
        assertTrue(json.contains("\"checkId\":\"LIFECYCLE_DUAL_TASK_UNFINISHED\""));
        assertTrue(json.contains("\"rowCount\":3"));
        assertTrue(json.contains("inOpenCases=2"));
        assertTrue(json.contains("inTerminalOrMissingCases=1"));

        LifecycleAuthorizationPreflight.PreflightReport versionless =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordVersionlessEvidenceLinks(versionless, 2, List.of("v1"));
        assertEquals("BLOCKED_MAPPING", versionless.status(), "版なし証跡が残る場合は文書ownerの判断を要する");
        assertTrue(versionless.toJson().contains("\"checkId\":\"LIFECYCLE_EVIDENCE_VERSIONLESS\""));

        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(dual, "sys_user",
                List.of("tenant_id", "id"), 0, 1, 1, List.of(), "none", true);
        assertEquals("FAIL", dual.status(), "BLOCKED_MAPPINGはFAILを隠さない");
    }

    @Test
    void 未終結退社案件の重複は0件ならPASSで1組以上ならV187適用前の人の判断待ちになる() {
        LifecycleAuthorizationPreflight.PreflightReport none =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordActiveResignationDuplicates(none, 0, 0, List.of());
        assertEquals("PASS", none.status());
        assertTrue(none.toJson().contains("\"checkId\":\"LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE\""));

        LifecycleAuthorizationPreflight.PreflightReport duplicated =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordActiveResignationDuplicates(duplicated, 1, 2, List.of("c1", "c2"));
        assertEquals("BLOCKED_MAPPING", duplicated.status(), "重複が残る間はV187の一意制約を追加できない");
        String json = duplicated.toJson();
        assertTrue(json.contains("\"rowCount\":2"));
        assertTrue(json.contains("duplicateGroups=1"));
        assertTrue(json.contains("uk_lifecycle_case_active_resignation"));

        LifecycleAuthorizationPreflight.recordUniqueKeyPrecondition(duplicated, "sys_user",
                List.of("tenant_id", "id"), 0, 1, 1, List.of(), "none", true);
        assertEquals("FAIL", duplicated.status(), "BLOCKED_MAPPINGはFAILを隠さない");
    }

    @Test
    void 紐付けの無い旧email照合対象のポータルユーザーは紐付け登録か許容の判断待ちになる() {
        LifecycleAuthorizationPreflight.PreflightReport none =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordPortalEngineerLinkCandidates(none, 0, List.of(), true);
        assertEquals("PASS", none.status());
        assertTrue(none.toJson().contains("linkTablePresent=true"));

        LifecycleAuthorizationPreflight.PreflightReport pending =
                new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
        LifecycleAuthorizationPreflight.recordPortalEngineerLinkCandidates(pending, 3, List.of("p1"), false);
        assertEquals("BLOCKED_MAPPING", pending.status());
        String json = pending.toJson();
        assertTrue(json.contains("\"checkId\":\"PORTAL_ENGINEER_LINK_CANDIDATES\""));
        assertTrue(json.contains("\"rowCount\":3"));
        assertTrue(json.contains("linkTablePresent=false"));
    }

    @Test
    void epoch列は未適用か定義どおりの適用だけをPASSにし同名CHECKや定義違いや負値をFAILにする() {
        LifecycleAuthorizationPreflight.EpochColumnSpec spec = new LifecycleAuthorizationPreflight.EpochColumnSpec(
                "t_engineer", "engineer_scope_epoch", "ck_engineer_scope_epoch");

        LifecycleAuthorizationPreflight.PreflightReport notApplied = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochColumn(notApplied, spec,
                new LifecycleAuthorizationPreflight.EpochColumnState(false, null, true, null, false, false, false, 0));
        assertEquals("PASS", notApplied.status());
        assertTrue(notApplied.toJson().contains("state=NOT_APPLIED"));
        assertTrue(notApplied.toJson().contains("t_engineer.engineer_scope_epoch"));

        LifecycleAuthorizationPreflight.PreflightReport applied = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochColumn(applied, spec,
                new LifecycleAuthorizationPreflight.EpochColumnState(true, "bigint", false, "0", true, true, false, 0));
        assertEquals("PASS", applied.status());
        assertTrue(applied.toJson().contains("state=APPLIED"));
        assertTrue(applied.toJson().contains("problems=none"));

        LifecycleAuthorizationPreflight.PreflightReport nameTaken = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochColumn(nameTaken, spec,
                new LifecycleAuthorizationPreflight.EpochColumnState(false, null, true, null, false, false, true, 0));
        assertEquals("FAIL", nameTaken.status());
        assertTrue(nameTaken.toJson().contains("checkNameUsedByAnotherTable=ck_engineer_scope_epoch"));

        LifecycleAuthorizationPreflight.PreflightReport wrongDefinition = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochColumn(wrongDefinition, spec,
                new LifecycleAuthorizationPreflight.EpochColumnState(true, "int", true, null, true, false, false, 2));
        assertEquals("FAIL", wrongDefinition.status());
        String json = wrongDefinition.toJson();
        assertTrue(json.contains("type=int"));
        assertTrue(json.contains("nullable"));
        assertTrue(json.contains("default=<null>"));
        assertTrue(json.contains("checkNotEnforced=ck_engineer_scope_epoch"));
        assertTrue(json.contains("negativeRows=2"));

        LifecycleAuthorizationPreflight.PreflightReport checkMissing = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochColumn(checkMissing, spec,
                new LifecycleAuthorizationPreflight.EpochColumnState(true, "bigint", false, "0", false, false, false, 0));
        assertEquals("FAIL", checkMissing.status());
        assertTrue(checkMissing.toJson().contains("checkMissing=ck_engineer_scope_epoch"));
    }

    @Test
    void 全体epoch表は1行だけでCHECK2件のときと未適用のときだけPASSになる() {
        LifecycleAuthorizationPreflight.PreflightReport notApplied = report();
        LifecycleAuthorizationPreflight.recordAuthorizationGlobalEpoch(notApplied,
                new LifecycleAuthorizationPreflight.GlobalEpochState(false, 0, 0, 0, true, false));
        assertEquals("PASS", notApplied.status());

        LifecycleAuthorizationPreflight.PreflightReport applied = report();
        LifecycleAuthorizationPreflight.recordAuthorizationGlobalEpoch(applied,
                new LifecycleAuthorizationPreflight.GlobalEpochState(true, 1, 1, 2, true, false));
        assertEquals("PASS", applied.status());
        assertTrue(applied.toJson().contains("state=APPLIED"));

        LifecycleAuthorizationPreflight.PreflightReport missingRow = report();
        LifecycleAuthorizationPreflight.recordAuthorizationGlobalEpoch(missingRow,
                new LifecycleAuthorizationPreflight.GlobalEpochState(true, 0, 0, 2, true, false));
        assertEquals("FAIL", missingRow.status());
        assertTrue(missingRow.toJson().contains("rows=0,validSingletonRows=0"));

        LifecycleAuthorizationPreflight.PreflightReport extraRow = report();
        LifecycleAuthorizationPreflight.recordAuthorizationGlobalEpoch(extraRow,
                new LifecycleAuthorizationPreflight.GlobalEpochState(true, 2, 1, 2, true, false));
        assertEquals("FAIL", extraRow.status());

        LifecycleAuthorizationPreflight.PreflightReport checkMissing = report();
        LifecycleAuthorizationPreflight.recordAuthorizationGlobalEpoch(checkMissing,
                new LifecycleAuthorizationPreflight.GlobalEpochState(true, 1, 1, 1, true, false));
        assertEquals("FAIL", checkMissing.status());

        LifecycleAuthorizationPreflight.PreflightReport nameTaken = report();
        LifecycleAuthorizationPreflight.recordAuthorizationGlobalEpoch(nameTaken,
                new LifecycleAuthorizationPreflight.GlobalEpochState(false, 0, 0, 0, true, true));
        assertEquals("FAIL", nameTaken.status());
    }

    @Test
    void epoch_Aの一部だけの適用はFAILで全部か無しならPASSになりORG_UNITの対応付けを前提にしない() {
        LifecycleAuthorizationPreflight.PreflightReport none = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochApplication(none, 0, 6);
        assertEquals("PASS", none.status());
        assertTrue(none.toJson().contains("state=NOT_APPLIED"));
        assertTrue(none.toJson().contains("ORG_UNIT_TENANT_MAPPING (BLOCKED_MAPPING) does not block epoch A"));

        LifecycleAuthorizationPreflight.PreflightReport all = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochApplication(all, 6, 6);
        assertEquals("PASS", all.status());
        assertTrue(all.toJson().contains("state=APPLIED"));

        LifecycleAuthorizationPreflight.PreflightReport partial = report();
        LifecycleAuthorizationPreflight.recordAuthorizationEpochApplication(partial, 3, 6);
        assertEquals("FAIL", partial.status());
        assertTrue(partial.toJson().contains("state=PARTIAL"));
        assertTrue(partial.toJson().contains("presentObjects=3/6"));
    }

    private static LifecycleAuthorizationPreflight.PreflightReport report() {
        return new LifecycleAuthorizationPreflight.PreflightReport("baseline", "schema");
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
