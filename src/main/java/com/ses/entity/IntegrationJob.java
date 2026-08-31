package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 外部連携非同期ジョブ (t_integration_job / Outbox)。
 * DB transaction外で外部連携を実行し、冪等性・リトライ・エラー分類・相関IDを保持する。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_integration_job")
public class IntegrationJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接続ID */
    private Long connectionId;

    /** ジョブ種別 (SALES_INVOICE_SYNC, SALES_INVOICE_CANCEL, BP_PURCHASE_SYNC, EXPENSE_DEAL_SYNC, PAYMENT_SYNC) */
    private String jobType;

    /** 対象種別 (INVOICE, BP_PAYMENT, EXPENSE_REQUEST, PAYMENT) */
    private String targetType;

    /** 対象エンティティID */
    private Long targetId;

    /** テナントID */
    @Builder.Default
    private String tenantId = "default";

    /** 法人ID */
    private Long legalEntityId;

    /** スコープ解決用組織IDスナップショット */
    private Long organizationId;

    /** 冪等性キー (UNIQUE) */
    private String idempotencyKey;

    /** 送信時canonical byte列 (不変スナップショットJSON) */
    private String payloadSnapshot;

    /** 送信ペイロードSHA-256ハッシュ */
    private String payloadHash;

    /** 状態 (PENDING / RUNNING / SUCCEEDED / RETRYABLE / FAILED / CANCELLED) */
    private String status;

    /** Worker lease UUID */
    private String leaseToken;

    /** Worker lease 期限 */
    private LocalDateTime leaseExpiresAt;

    /** 試行回数 */
    private Integer attemptCount;

    /** 最大試行回数 */
    private Integer maxAttempts;

    /** 次回再試行予定日時 */
    private LocalDateTime nextRetryAt;

    /** 外部取引/伝票ID */
    private String externalId;

    /** 外部リクエストID (X-Freee-Request-ID等) */
    private String providerRequestId;

    /** API・ワーカーを横断する相関ID */
    private String correlationId;

    /** プロバイダが返した操作ID（照合用） */
    private String providerOperationId;

    /** 分類エラーコード */
    private String errorCode;

    /** 業務エラーかシステムエラーかの分類 */
    private String errorCategory;

    /** 安全なエラー要約 (PII/Secret除外) */
    private String errorMessageSafe;

    /** 送信成功日時 */
    private LocalDateTime sentAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deletedFlag;

    @Version
    private Integer version;
}
