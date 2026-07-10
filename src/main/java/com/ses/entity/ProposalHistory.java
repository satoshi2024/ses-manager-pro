package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提案履歴エンティティ
 */
@Data
@TableName("t_proposal_history")
public class ProposalHistory {

    /** ID */
    private Long id;

    /** 提案ID */
    private Long proposalId;

    /** 変更前ステータス */
    private String fromStatus;

    /** 変更後ステータス */
    private String toStatus;

    /** 変更者ID */
    private Long changedBy;

    /** 変更日時 */
    private LocalDateTime changedAt;

    /** 備考 */
    private String remarks;
}
