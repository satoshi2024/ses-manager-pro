package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * タスク世代（{@code t_lifecycle_task.task_generation}）のanchorを、同じtransactionで案件 → タスクの順にlocking readした
 * 行から作る（細粒度epoch計画 §2.2・§4.2、W3-10 review P3-10）。
 *
 * <p>引数は「案件行をX lockした後にlockしたタスク行」に限る。増分SQLは案件のtenant照合を副問合せで行うため、案件行のX lock
 * より前に増分すると副問合せが案件行に共有lockを付け、後から案件をX lockした時にS→X昇格になる。案件X → タスクX の後に
 * 呼ぶことで、副問合せの読む行は保持済みのlockで済む。tenantは要求・sessionではなく、lockした案件行の値を使う（W3-10 review
 * P3-5）。タスクが案件に属さない、IDかtenantが読めない場合は世代を進められないため、業務更新ごと409
 * {@code error.authorization.epochUnavailable} で止める。</p>
 */
@Slf4j
public final class TaskGenerationAnchors {

    private TaskGenerationAnchors() {
    }

    /**
     * lock済みの案件行とその配下のlock済みタスク行からanchorを作る。
     *
     * @throws BusinessException 409 案件・タスクのID・tenantが無い、またはタスクが案件に属さない場合
     */
    public static AuthorizationEpochAnchor.TaskGeneration of(LifecycleCase lockedCase, LifecycleTask lockedTask) {
        if (lockedCase == null || lockedTask == null || lockedCase.getId() == null || lockedCase.getId() <= 0
                || lockedTask.getId() == null || lockedTask.getId() <= 0
                || !Objects.equals(lockedCase.getId(), lockedTask.getCaseId())
                || lockedCase.getTenantId() == null || lockedCase.getTenantId().isBlank()) {
            log.warn("[authorization epoch] 案件・タスクのIDまたはtenantを読めないためタスク世代を進められません: caseId={}, taskId={}",
                    lockedCase == null ? null : lockedCase.getId(), lockedTask == null ? null : lockedTask.getId());
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return AuthorizationEpochAnchor.taskGeneration(lockedCase.getTenantId(), lockedCase.getId(), lockedTask.getId());
    }
}
