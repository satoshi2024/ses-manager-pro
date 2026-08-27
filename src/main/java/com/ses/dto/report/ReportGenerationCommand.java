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
        String recipientPreviewHash) {

    public static ReportGenerationCommand manual(Long templateVersionId, YearMonth period,
                                                  String cutoffKind) {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                false, null, false, null, null);
    }

    public static ReportGenerationCommand scheduled(Long templateVersionId, YearMonth period,
                                                    String cutoffKind, Long scheduleId,
                                                    Long effectivePrincipalUserId) {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                false, scheduleId, true, effectivePrincipalUserId, null);
    }

    public ReportGenerationCommand forRegeneration() {
        return new ReportGenerationCommand(templateVersionId, period, cutoffKind,
                true, scheduleId, systemPrincipal, principalUserId, null);
    }
}
