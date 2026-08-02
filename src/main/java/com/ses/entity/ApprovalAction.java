package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 承認アクション履歴。イベントとして不変(更新・論理削除なし)なため{@link com.ses.common.base.BaseEntity}を継承しない。
 * approverSlotUserId = COALESCE(delegatedFrom, approverUserId)。同一slotへの二重action防止に使う。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_approval_action")
public class ApprovalAction implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long requestId;
    private Integer stepNo;
    /** 実際に操作したuser(代理時は代理者本人)。 */
    private Long approverUserId;
    /** 解決されたslotの持ち主(代理時は委任元)。 */
    private Long approverSlotUserId;
    /** APPROVE / REJECT / RETURN / WITHDRAW */
    private String action;
    private String comment;
    /** 代理実行時の委任元user(本人操作時はNULL)。 */
    private Long delegatedFrom;
    private LocalDateTime actedAt;
}
