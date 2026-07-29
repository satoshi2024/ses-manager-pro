package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 原文を保存しない一回限りのMFA recovery code hash。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_mfa_recovery_code")
public class MfaRecoveryCode extends BaseEntity {
    private String tenantId;
    private Long userId;
    private String codeHash;
    private String hashAlgorithm;
    private LocalDateTime usedAt;
    private LocalDateTime revokedAt;
}
