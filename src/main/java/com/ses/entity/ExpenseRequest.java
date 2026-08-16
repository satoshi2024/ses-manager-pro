package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 要員経費申請（t_expense_request）。
 * 状態機械: 下書き→申請中→承認済→会計連携済→支払済（design §6.3）。
 * accounting_job_id UNIQUEで会計連携の冪等を担保。金額は円（BigDecimal）。
 * 領収書は文書台帳（t_document）経由でscan=CLEANのものだけを参照できる（fail-closed）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_expense_request")
public class ExpenseRequest extends BaseEntity {

    /** 申請元要員ID */
    private Long engineerId;

    /** 経費番号（初回申請時にEX-{id}を採番） */
    private String expenseNo;

    /** 経費発生日 */
    private LocalDate expenseDate;

    /** 交通費/立替経費（本人は任意の科目codeを送れない。design §4） */
    private String category;

    /** 金額（円） */
    private BigDecimal amount;

    /** 顧客ID（任意） */
    private Long customerId;

    /** 案件ID（任意） */
    private Long projectId;

    /** 理由 */
    private String description;

    /** 領収書の文書台帳ID（t_document） */
    private Long receiptDocumentId;

    /** 下書き/申請中/承認済/会計連携済/支払済 */
    private String status;

    /** 承認ワークフロー申請ID */
    private Long approvalRequestId;

    /** 会計連携job ID（UNIQUE。二重連携防止） */
    private Long accountingJobId;

    /** 支払日時（NULL=未払） */
    private LocalDateTime paidAt;

    @Version
    private Integer version;
}
