package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-PII-OWNERSHIP-01（design §6.2）:
 * T061のinternal entity/tableをportal/AI DTOへ直接公開するconsumerをscanし、直接公開0件を確認する。
 * detail/list/countのfield maskはT063、CSV/Excel/PDF/downloadはT064のmatrixで証明する。
 */
class F1PiiOwnershipScanTest {

    private static final List<String> DISPATCH_ENTITIES = List.of(
            "ContractComplianceProfile",
            "ContractComplianceSnapshot",
            "ContractComplianceWorkerSnapshot",
            "ContractComplianceWorkerState",
            "ComplianceSnapshotOperation",
            "ComplianceWorkCalendar",
            "ComplianceComplaintHistory",
            "EmploymentStabilityHistory",
            "TrainingHistory",
            "CareerConsultingHistory",
            "PlannedIntroductionTerms",
            "PlannedIntroductionHistory",
            "DirectHireDisputeHistory",
            "NotificationDifferenceHistory",
            "LedgerWorkSnapshot",
            "DocumentDelivery",
            "ComplianceFinding",
            "Workplace");

    @Test
    void dispatchEntityをportalAIDTOへ直接公開するconsumerが0件() throws Exception {
        Path src = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(src), "src/main/javaが必要です");
        StringBuilder violations = new StringBuilder();
        try (Stream<Path> paths = Files.walk(src)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = src.relativize(path).toString().replace('\\', '/');
                boolean isPortalOrAi = relative.contains("portal") || relative.contains("/ai/")
                        || relative.startsWith("ai/");
                if (!isPortalOrAi) {
                    continue;
                }
                String body = Files.readString(path, StandardCharsets.UTF_8);
                for (String entity : DISPATCH_ENTITIES) {
                    if (body.contains(entity)) {
                        violations.append(relative).append(" -> ").append(entity).append('\n');
                    }
                }
            }
        }
        assertEquals(0, violations.length(),
                "portal/AI consumerがdispatch internal entityを直接参照しています:\n" + violations);

        // 参照ではなくscan対象の正しさも確認：internal entityはcom.ses.entity配下に存在する
        for (String entity : DISPATCH_ENTITIES) {
            assertTrue(Files.exists(src.resolve("com/ses/entity/" + entity + ".java")),
                    "entityが存在しません: " + entity);
        }
    }
}
