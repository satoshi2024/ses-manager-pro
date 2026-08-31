package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 外部アカウント参照台帳エンティティ (t_external_account_reference)
 * ※秘密非保存原則: password, token, key などのフィールドは保持しない。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_external_account_reference")
public class ExternalAccountReference extends BaseEntity {

    /**
     * 外部システムID (m_external_account_system.id)
     */
    private Long systemId;

    /**
     * 外部識別子 (メールアドレス、アカウントID等 - PII扱い)
     */
    private String accountIdentifier;

    /**
     * 貸与先区分: ENGINEER, USER
     */
    private String assigneeType;

    /**
     * 要員IDまたはユーザーID
     */
    private Long assigneeId;

    /**
     * 権限区分: ADMIN, DEVELOPER, MEMBER, READONLY
     */
    private String permissionLevel;

    /**
     * ステータス: ACTIVE, SUSPENDED, REVOKED, PENDING_CONFIRMATION, UNKNOWN, EXCEPTION_HOLD
     */
    private String status;

    /**
     * 発行/割当日時
     */
    private LocalDateTime provisionedAt;

    /**
     * 失効要求冪等性キー
     */
    private String idempotencyKey;

    /**
     * リトライ・ポーリング回数
     */
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 次回ポーリング予定日時
     */
    private LocalDateTime nextRetryAt;

    /**
     * 直近エラー要約 (秘密非含有)
     */
    private String lastErrorMessage;

    /**
     * 失効要求送信日時
     */
    private LocalDateTime revokeRequestedAt;

    /**
     * 失効完了確認日時 (NULL=失効未確認)
     */
    private LocalDateTime revokeConfirmedAt;

    /**
     * 失効確認者ユーザーID
     */
    private Long revokeConfirmedBy;

    /**
     * 外部連携ステータス: NONE, SYNC_PENDING, SYNC_SUCCESS, SYNC_FAILED, TIMEOUT
     */
    @Builder.Default
    private String externalSyncStatus = "NONE";

    /**
     * 外部連携エラー要約 (秘密値非含有)
     */
    private String syncErrorMessage;

    /**
     * 楽観ロック用バージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;
}
