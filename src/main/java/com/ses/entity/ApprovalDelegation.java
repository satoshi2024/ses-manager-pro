package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 承認代理設定。代理は承認操作の実行時点で評価する(申請時点ではない)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_delegation")
public class ApprovalDelegation extends BaseEntity {

    private Long fromUserId;
    private Long toUserId;
    private LocalDate validFrom;
    private LocalDate validTo;
    /** NULL=全種別対象。JSON配列文字列(request_type一覧)。 */
    private String requestTypesJson;
    private String reason;
    private Long approvedBy;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
