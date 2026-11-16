package com.ses.service.lifecycle.dual;

import com.ses.common.exception.BusinessException;

import java.util.Objects;

/**
 * 二者確認の決定的な拒否（再試行の予算に数えない。NF01細粒度epoch計画 §5.6）。
 *
 * <p>応答（HTTP状態・message key）と拒否eventのreasonを一緒に運ぶ。attempt内で投げるとattemptはrollbackされ、
 * coordinatorがrollback後に拒否eventを追記（{@link LifecycleDualConfirmationDenyRecorder}）してから
 * {@link #toBusinessException()} を応答にする。{@code recordable=false} は拒否eventに記録しない拒否
 * （スイッチOFF・bodyの形式違反、§5.8）。</p>
 */
public final class DualConfirmationRejection extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String messageKey;
    private final DualConfirmationDenyReason reason;
    private final boolean recordable;

    private DualConfirmationRejection(int status, String messageKey, DualConfirmationDenyReason reason,
                                      boolean recordable) {
        super(messageKey, null, false, false);
        this.status = status;
        this.messageKey = Objects.requireNonNull(messageKey, "messageKey");
        this.reason = reason;
        this.recordable = recordable;
    }

    /** 拒否eventに記録する拒否。 */
    public static DualConfirmationRejection of(int status, String messageKey, DualConfirmationDenyReason reason) {
        return new DualConfirmationRejection(status, messageKey, Objects.requireNonNull(reason, "reason"), true);
    }

    /** 拒否eventに記録しない拒否（スイッチOFF・bodyの形式違反・主体を解決できない）。 */
    public static DualConfirmationRejection unrecorded(int status, String messageKey) {
        return new DualConfirmationRejection(status, messageKey, null, false);
    }

    public int status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }

    public DualConfirmationDenyReason reason() {
        return reason;
    }

    public boolean recordable() {
        return recordable;
    }

    public BusinessException toBusinessException() {
        return BusinessException.of(status, messageKey);
    }
}
