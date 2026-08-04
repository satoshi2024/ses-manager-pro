package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 承認routeのstep定義。同一step_noを共有する複数行が1つの並列groupを成す。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_approval_route_step")
public class ApprovalRouteStep extends BaseEntity {

    private Long routeId;
    private Integer stepNo;
    private Integer parallelGroup;
    /** USER / ROLE / PERMISSION_GROUP / APPLICANT_MANAGER / ORGANIZATION_MANAGER / FINANCE_MANAGER */
    private String approverType;
    private String approverValue;
    private Integer slaHours;
}
