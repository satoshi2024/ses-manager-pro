package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** issuer/providerとsubjectで内部ユーザーへ紐付ける外部identity。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_external_identity")
public class UserExternalIdentity extends BaseEntity {
    private String tenantId;
    private Long userId;
    private Long providerId;
    private String subject;
    private String emailSnapshot;
    private LocalDateTime linkedAt;
}
