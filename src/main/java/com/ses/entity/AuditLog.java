package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API操作監査ログエンティティ（t_audit_log）。
 * BaseEntityは継承しない（更新日時・論理削除の概念を持たない追記専用ログ）。
 */
@Data
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String method;

    private String uri;

    private Integer status;
    private String applicationCode;
    private Boolean successFlag;

    /** 対象種別・対象参照ID（ドメイン監査の場合）。 */
    private String referenceType;
    private Long referenceId;

    /** 確認主体・確認チャネル。 */
    private String actorType;
    private String confirmationSource;
    private Long humanUserId;
    private String beforeState;
    private String afterState;
    private String correlationId;
    private String idempotencyKey;

    /** 電子請求書・ジョブ診断フィールド（秘匿安全）。 */
    private String invoiceId;
    private String digitalInvoiceId;
    private String jobId;
    private String providerOperationId;
    private String errorCode;
    private String errorCategory;

    private LocalDateTime createdAt;
}
