package com.ses.service.lifecycle.dual;

/**
 * 二者確認の入口（NF01細粒度epoch計画 §5.2）。{@link #INTERNAL} は社内画面の {@code /api/lifecycle}（lifecycle menu）、
 * {@link #SELF} は要員本人の {@code /api/my/lifecycle}（myLifecycle menu）。本人の入口には確認routeを作らない。
 *
 * <p>明示actorのroute判定（{@code ExplicitActorAuthorizationEvaluator#isRouteAllowed}）には、ここで組み立てる固定のrouteだけを
 * 渡す（menu判定対象外のpathを渡すとtrueになるため。W3-15 review P3-1）。</p>
 */
public enum DualConfirmationChannel {
    INTERNAL("/api/lifecycle/tasks/"),
    SELF("/api/my/lifecycle/tasks/");

    private final String taskRoot;

    DualConfirmationChannel(String taskRoot) {
        this.taskRoot = taskRoot;
    }

    /** 提出のroute（POST）。 */
    public String submitRoute(long taskId) {
        return taskRoot + taskId + "/dual-confirmation/submissions";
    }

    /** 照会のroute（GET）。 */
    public String viewRoute(long taskId) {
        return taskRoot + taskId + "/dual-confirmation";
    }

    /** 確認・差戻しのroute（POST）。社内の入口だけが持つ。 */
    public static String decisionRoute(long taskId, long submissionId) {
        return INTERNAL.taskRoot + taskId + "/dual-confirmation/submissions/" + submissionId + "/decision";
    }

    /** 提出者のroleから、提出者が使う入口を決める（要員は本人の入口、それ以外は社内の入口）。 */
    public static DualConfirmationChannel forSubmitterRole(String role) {
        return "要員".equals(role) ? SELF : INTERNAL;
    }
}
