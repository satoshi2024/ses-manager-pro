package com.ses.service.lifecycle;

import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;

import java.util.List;

/**
 * ライフサイクル通知サービス
 */
public interface LifecycleNotificationService {

    /**
     * 期日接近タスクの通知
     */
    void notifyTaskDueSoon(LifecycleTask task, LifecycleCase lcCase, long daysLeft);

    /**
     * 期日超過タスクの通知
     */
    void notifyTaskOverdue(LifecycleTask task, LifecycleCase lcCase, long daysOverdue);

    /**
     * 案件完了阻害の通知
     */
    void notifyCaseBlocked(LifecycleCase lcCase, List<LifecycleTask> blockingTasks);

    /**
     * 案件完了の通知
     */
    void notifyCaseCompleted(LifecycleCase lcCase);

    /**
     * 二者確認の確認待ちの通知（NF01 W3-21a。提出のcommit後に呼ぶ）。業務comment・文書titleは含めない。
     */
    void notifyDualConfirmationSubmitted(LifecycleTask task, LifecycleCase lcCase, long submissionId);

    /**
     * 二者確認の確認・差戻しの結果を提出者へ通知する（NF01 W3-21a。判断のcommit後に呼ぶ）。業務comment・文書titleは含めない。
     *
     * @param selfService 提出者が要員本人の画面から提出した場合true（通知のlinkを本人画面にする）
     */
    void notifyDualConfirmationDecided(LifecycleTask task, LifecycleCase lcCase, long submissionId,
                                       long submitterUserId, boolean confirmed, boolean selfService);
}
