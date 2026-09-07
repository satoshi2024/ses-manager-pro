package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 資格取得renew継続グループ（continuity group）エンティティ。
 * 資格取得チェーンの一意なDB発番キーを永続化する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_certification_continuity_group")
public class CertificationContinuityGroup extends BaseEntity {

    private String tenantId;
    private Long engineerId;
    private Long certificationId;
    private Long createdBy;
}
