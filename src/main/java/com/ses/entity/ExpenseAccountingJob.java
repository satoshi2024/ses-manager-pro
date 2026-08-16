package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 経費の会計連携outbox job（t_expense_accounting_job）。
 * 外部APIはDB transaction外で呼ぶ（platform-invariants §3.3）。
 * UNIQUE(expense_request_id)で同一経費から2件のjobを作らない（冪等。design §6.3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@TableName("t_expense_accounting_job")
public class ExpenseAccountingJob {

    /** 経費申請ID（UNIQUE） */
    private Long expenseRequestId;

    /** PENDING/PROCESSING/SUCCEEDED/FAILED */
    private String status;

    /** 相関ID（外部連携追跡） */
    private String correlationId;

    /** 送信payloadのSHA-256 */
    private String payloadHash;

    /** 試行回数 */
    private Integer attemptCount;

    /** 再試行可能時刻 */
    private LocalDateTime nextAttemptAt;

    /** PIIを含まない分類code */
    private String lastErrorCode;

    /** 外部送信成功日時 */
    private LocalDateTime sentAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
