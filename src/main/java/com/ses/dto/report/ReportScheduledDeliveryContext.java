package com.ses.dto.report;

/** schedulerがHTTP sessionを使わず配布するための明示system context。nullは許可しない。 */
public record ReportScheduledDeliveryContext(Long scheduleId, Long principalUserId) {

    public ReportScheduledDeliveryContext {
        if (scheduleId == null || principalUserId == null) {
            throw new IllegalArgumentException("scheduleIdとprincipalUserIdは必須です");
        }
    }

    public static ReportScheduledDeliveryContext of(Long scheduleId, Long principalUserId) {
        return new ReportScheduledDeliveryContext(scheduleId, principalUserId);
    }
}
