package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * ポータルユーザーエンティティ（t_portal_user）。
 * 内部sys_userとは別identity（G3）。全user TOTP MFA必須・recovery codeは1回限りhash保存。
 * emailは全組織で一意。論理削除済みemailの再招待はservice側でreactivateする。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_portal_user")
public class PortalUser extends BaseEntity {

    /** ポータル組織ID */
    private Long portalOrgId;

    /** login email（全組織で一意） */
    private String email;

    /** 表示名（招待受諾時に設定） */
    private String displayName;

    /** パスワードhash（招待受諾時に設定。BCrypt） */
    private String passwordHash;

    /** 状態: ACTIVE / SUSPENDED（停止時はsession失効） */
    private String status;

    /** MFA方針（既定REQUIRED=全user必須） */
    private String mfaPolicy;

    /** TOTP secret暗号化値（平文は保存しない） */
    private String totpSecretEncrypted;

    /** TOTP secretの暗号鍵version */
    private String totpSecretKeyVersion;

    /** MFA設定完了日時（NULL=未設定でlogin不可） */
    private LocalDateTime mfaEnabledAt;

    /** 1回限りrecovery codeのhash */
    private String recoveryCodeHash;

    /** recovery code使用日時（NULL=未使用） */
    private LocalDateTime recoveryCodeUsedAt;

    /** 最後に受理したTOTP step（同一コードの再使用をCASで拒否） */
    private Long lastUsedStep;

    /** 最終login日時 */
    private LocalDateTime lastLoginAt;

    /** 楽観ロック */
    @Version
    private Integer version;
}
