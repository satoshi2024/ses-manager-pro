package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ポータルセッション（t_portal_session）。
 * portal専用のDB永続session。生tokenは保存せずSHA-256 hashのみ保存する。
 * 失効（revoked_at）・期限・user/組織停止はPortalSessionFilterが毎リクエスト検証する（G3）。
 */
@Data
@TableName("t_portal_session")
public class PortalSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** portal user ID */
    private Long userId;

    /** session tokenのSHA-256 hash（生tokenは保存しない） */
    private String tokenHash;

    /** 発行日時 */
    private LocalDateTime issuedAt;

    /** 最終アクセス日時 */
    private LocalDateTime lastSeenAt;

    /** アイドル期限 */
    private LocalDateTime idleExpiresAt;

    /** 絶対期限 */
    private LocalDateTime expiresAt;

    /** 接続元IPのSHA-256 hash */
    private String ipHash;

    /** User-Agent */
    private String userAgent;

    /** 失効日時。NULL=有効 */
    private LocalDateTime revokedAt;

    /** 失効理由 */
    private String revokedReason;
}
