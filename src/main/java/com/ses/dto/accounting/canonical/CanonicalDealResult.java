package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 外部取引連携結果DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalDealResult {

    /** 送信成否 */
    private boolean success;

    /** 外部システム取引ID */
    private String externalId;

    /** 外部リクエストID (X-Freee-Request-ID等) */
    private String providerRequestId;

    /** 分類エラーコード (VALIDATION_ERROR / UNAUTHORIZED / PLAN_LIMITATION / RATE_LIMITED / SERVER_ERROR / TIMEOUT 等) */
    private String errorCode;

    /** 安全なエラーメッセージ (Secret/PII除外) */
    private String errorMessageSafe;

    /** 再試行可能フラグ */
    private boolean retryable;

    /** 再試行待ち秒数 (Retry-After等) */
    private Integer retryAfterSeconds;

    /** レスポンス本体から抽出した税抜・税・合計金額 (金額照合用) */
    private BigDecimal responseSubtotal;
    private BigDecimal responseTax;
    private BigDecimal responseTotal;
}
