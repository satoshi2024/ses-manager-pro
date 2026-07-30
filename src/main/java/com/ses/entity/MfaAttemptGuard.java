package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_mfa_attempt_guard")
public class MfaAttemptGuard extends BaseEntity {
    private String tenantId;
    private Long userId;
    private String sessionKeyHash;
    private String sourceHash;
    private Integer failedCount;
    private LocalDateTime windowStartedAt;
    private LocalDateTime lockedUntil;
}
