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
}
