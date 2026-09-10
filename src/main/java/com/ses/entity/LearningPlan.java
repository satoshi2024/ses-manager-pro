package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_learning_plan")
public class LearningPlan extends BaseEntity {

    private String tenantId;
    private Long engineerId;
    private Long createdByUserId;
    private String title;
    private String goalDescription;
    private String attainmentCriteria;
    private LocalDate plannedStartOn;
    private LocalDate plannedEndOn;
    private BigDecimal plannedCostJpy;
    /** 追加承認済みの上限。planned_cost_jpyの申請時snapshotは変更しない。 */
    private BigDecimal amendedCostJpy;
    /** 上記追加承認を特定する承認申請ID。 */
    private Long amendmentApprovalRequestId;
    /** 実費の正本。金額・承認・会計・支払statusはExpenseRequestが所有する。 */
    private Long expenseRequestId;
    private String status;
    private Long approvalRequestId;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
}
