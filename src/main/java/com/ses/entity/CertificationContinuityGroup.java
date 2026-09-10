package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 資格renew履歴の複合tenant/engineer/certification親行。 */
@Data
@TableName("t_certification_continuity_group")
public class CertificationContinuityGroup {
    private String tenantId;
    private Long engineerId;
    private Long certificationId;
    private Long continuityGroupId;
}
