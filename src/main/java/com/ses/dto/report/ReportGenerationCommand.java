package com.ses.dto.report;

import java.time.YearMonth;

/** レポート生成要求。schedulerからはsystemPrincipal=trueで明示principalを渡す。 */
public record ReportGenerationCommand(
        Long templateVersionId,
        YearMonth period,
        String cutoffKind,
        boolean explicitRegeneration,
        Long scheduleId,
        boolean systemPrincipal,
        Long principalUserId,
        Long regenerationOfRunId,
        String recipientPreviewHash,
        ReportScopeSnapshot scopeSnapshot) {

    public static ReportGenerationCommand manual(Long templateVersionId, YearMonth period,
                                                  String cutoffKind) {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                false, null, false, null, null, null, null);
    }

    public static ReportGenerationCommand scheduled(Long templateVersionId, YearMonth period,
                                                    String cutoffKind, Long scheduleId,
                                                    Long effectivePrincipalUserId) {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                false, scheduleId, true, effectivePrincipalUserId, null, null, null);
    }

    public static ReportGenerationCommand scheduled(Long templateVersionId, YearMonth period,
                                                    String cutoffKind, Long scheduleId,
                                                    Long effectivePrincipalUserId,
                                                    ReportScopeSnapshot scopeSnapshot) {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                false, scheduleId, true, effectivePrincipalUserId, null, null, scopeSnapshot);
    }

    public ReportGenerationCommand forRegeneration() {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                true, scheduleId, systemPrincipal, principalUserId, null, null, scopeSnapshot);
    }

    public ReportGenerationCommand forRegenerationOf(Long previousRunId) {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                true, scheduleId, systemPrincipal, principalUserId, previousRunId, null, scopeSnapshot);
    }
}
