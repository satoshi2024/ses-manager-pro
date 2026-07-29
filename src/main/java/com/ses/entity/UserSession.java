package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 失効可能な永続session metadata。生session IDは保存しない。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_session")
public class UserSession extends BaseEntity {
    private String tenantId;
    private String sessionIdHash;
    private Long userId;
    private LocalDateTime issuedAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime idleExpiresAt;
    private LocalDateTime expiresAt;
    private String ipHash;
    private String userAgent;
    private LocalDateTime revokedAt;
    private String revokeReason;
}
