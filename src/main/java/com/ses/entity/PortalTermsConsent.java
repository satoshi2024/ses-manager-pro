package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ポータル利用規約同意エンティティ（t_portal_terms_consent）。
 * append-only（版ごとに1行。UNIQUE(user_id, terms_version)で二重同意を防ぐ: design §6.3）。
 */
@Data
@TableName("t_portal_terms_consent")
public class PortalTermsConsent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** portal user ID */
    private Long userId;

    /** 同意した利用規約version */
    private String termsVersion;

    /** 同意日時 */
    private LocalDateTime consentedAt;

    /** 同意時IPのSHA-256 hash（監査用。R4.2） */
    private String ipHash;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;
}
