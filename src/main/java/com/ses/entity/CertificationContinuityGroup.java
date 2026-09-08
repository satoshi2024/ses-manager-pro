package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/** 資格renew履歴の複合tenant/engineer/certification親行。 */
@Data
@TableName("t_certification_continuity_group")
public class CertificationContinuityGroup {
    private String tenantId;
    private Long engineerId;
    private Long certificationId;
    @TableId(value = "continuity_group_id", type = IdType.AUTO)
    private Long continuityGroupId;
}
