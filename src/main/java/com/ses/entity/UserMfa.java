package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** ユーザーのMFA設定。TOTP secretは暗号化済み値だけを保持する。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_mfa")
public class UserMfa extends BaseEntity {
    private String tenantId;
    private Long userId;
    private String encryptedTotpSecret;
    private String secretKeyVersion;
    private LocalDateTime enabledAt;
    private Long lastUsedStep;
}
