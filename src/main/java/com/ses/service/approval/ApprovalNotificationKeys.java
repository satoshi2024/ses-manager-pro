package com.ses.service.approval;

/** 承認通知のevent単位dedupe keyを一元化する。宛先user suffixは通知サービスが付与する。 */
public final class ApprovalNotificationKeys {

    private ApprovalNotificationKeys() {
    }

    public static String requested(Long requestId, int round, int step) {
        return base("approval-requested", requestId, round, step);
    }

    public static String approved(Long requestId, int round, int step, Long slotOwnerId) {
        return base("approval-approved", requestId, round, step) + ":slot:" + slotOwnerId;
    }

    public static String returned(Long requestId, int round, int step, Long slotOwnerId) {
        return base("approval-returned", requestId, round, step) + ":slot:" + slotOwnerId;
    }

    public static String rejected(Long requestId, int round, int step, Long slotOwnerId) {
        return base("approval-rejected", requestId, round, step) + ":slot:" + slotOwnerId;
    }

    public static String conflict(Long requestId, int round, int step) {
        return base("approval-conflict", requestId, round, step);
    }

    public static String slaOverdue(Long requestId, int round, int step) {
        return base("approval-sla-overdue", requestId, round, step);
    }

    private static String base(String event, Long requestId, int round, int step) {
        return event + ":" + requestId + ":round:" + round + ":step:" + step;
    }
}
