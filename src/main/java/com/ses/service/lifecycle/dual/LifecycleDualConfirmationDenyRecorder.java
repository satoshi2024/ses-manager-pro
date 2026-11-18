package com.ses.service.lifecycle.dual;

import com.ses.entity.LifecycleDualEvent;
import com.ses.mapper.LifecycleDualEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 二者確認の拒否eventを {@code t_lifecycle_dual_event}（{@code event_type = 'DENIED'}）へ追記する
 * （NF01細粒度epoch計画 §5.4・§5.8）。
 *
 * <p>coordinatorがattemptのrollback後に呼ぶ別beanで、自前の {@code REQUIRES_NEW} transactionでcommitするため、拒否の原因と
 * なったattemptのrollbackに巻き込まれない。表はFKを持たない監査ログで、他tenantのIDが要求されてもinsertは失敗しない。
 * 失敗時の扱い（WARNだけで応答を変えない）は呼出側のcoordinatorが持つ。</p>
 *
 * <p>記録するのはID・操作・理由code・HTTP状態・試行回数だけで、文書title・storage path/key・業務comment・clientが送ったhash・
 * 氏名は受け取らない（{@link DenyRecord} がそれらの欄を持たない）。</p>
 */
@Component
@RequiredArgsConstructor
public class LifecycleDualConfirmationDenyRecorder {

    static final String EVENT_TYPE_DENIED = "DENIED";

    private final LifecycleDualEventMapper eventMapper;

    /**
     * 拒否eventの内容。{@code tenantId} は主体（提出者・確認者）のDB上のtenant、{@code caseId}・{@code taskGeneration} は
     * 主体のtenant内で解決できた場合だけ設定する。
     *
     * @param operation {@code SUBMIT}・{@code CONFIRM}・{@code REJECT}
     */
    public record DenyRecord(String tenantId, long actorUserId, String operation, Long requestedTaskId,
                             Long requestedSubmissionId, Long caseId, Long taskId, Long taskGeneration,
                             Long submissionId, DualConfirmationDenyReason reason, int httpStatus, int attemptCount) {
        public DenyRecord {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(reason, "reason");
            if (actorUserId <= 0) {
                throw new IllegalArgumentException("拒否eventの主体IDは正数で指定してください");
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(DenyRecord record) {
        Objects.requireNonNull(record, "record");
        eventMapper.insert(LifecycleDualEvent.builder()
                .tenantId(record.tenantId())
                .caseId(record.caseId())
                .taskId(record.taskId())
                .taskGeneration(record.taskGeneration())
                .submissionId(record.submissionId())
                .requestedTaskId(record.requestedTaskId())
                .requestedSubmissionId(record.requestedSubmissionId())
                .actorUserId(record.actorUserId())
                .operation(record.operation())
                .eventType(EVENT_TYPE_DENIED)
                .reasonCode(record.reason().name())
                .httpStatus(record.httpStatus())
                .attemptCount(Math.max(0, Math.min(record.attemptCount(), Byte.MAX_VALUE)))
                .occurredAt(LocalDateTime.now())
                .build());
    }
}
