package com.ses.service.integrationhub;

import java.time.LocalDate;

/**
 * 請求精算・消込状態（settlementStatus）を公開API用大文字ASCIIコードへ導出・変換する独立マッパー。
 * 入金日または請求ステータスに基づき判定し、未知状態はUNKNOWNへフェイルクローズする。
 */
public final class ExternalApiSettlementStatusMapper {
    public static final String SETTLED = "SETTLED";
    public static final String PARTIALLY_SETTLED = "PARTIALLY_SETTLED";
    public static final String OUTSTANDING = "OUTSTANDING";
    public static final String UNKNOWN = "UNKNOWN";

    private ExternalApiSettlementStatusMapper() {
    }

    /**
     * 請求内部ステータスと入金日から外部決済ステータスコードを導出する。
     *
     * @param internalInvoiceStatus 請求ステータス内部値
     * @param paidDate              入金日（null許容）
     * @return 安定した大文字ASCIIコード
     */
    public static String toExternalStatus(String internalInvoiceStatus, LocalDate paidDate) {
        if (paidDate != null) {
            return SETTLED;
        }
        if (internalInvoiceStatus == null || internalInvoiceStatus.isBlank() || internalInvoiceStatus.length() > 64) {
            return UNKNOWN;
        }
        return switch (internalInvoiceStatus.trim()) {
            case "入金済", "PAID", "SETTLED" -> SETTLED;
            case "一部入金", "PARTIALLY_PAID", "PARTIAL", "PARTIALLY_SETTLED" -> PARTIALLY_SETTLED;
            case "未送付", "送付済", "UNSENT", "SENT", "DRAFT", "OUTSTANDING" -> OUTSTANDING;
            default -> UNKNOWN;
        };
    }

    /**
     * 単一コード文字列からの直接変換。
     *
     * @param status 外部コードまたは内部値
     * @return 安定した大文字ASCIIコード
     */
    public static String toExternalStatus(String status) {
        return toExternalStatus(status, null);
    }
}
