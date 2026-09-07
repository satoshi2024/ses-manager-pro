package com.ses.service.integrationhub;

/**
 * 請求状態（Invoice Status）の内部ステータスを公開API用大文字ASCIIコードへ変換する独立マッパー。
 * 内部の日本語状態を隠蔽し、未知値や不正長はUNKNOWNへフェイルクローズする。
 */
public final class ExternalApiInvoiceStatusMapper {
    public static final String UNSENT = "UNSENT";
    public static final String SENT = "SENT";
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PAID = "PAID";
    public static final String UNKNOWN = "UNKNOWN";

    private ExternalApiInvoiceStatusMapper() {
    }

    /**
     * 内部ステータスを外部公開コードへ変換する。
     *
     * @param internalStatus DB内部ステータス値
     * @return 安定した大文字ASCIIコード（未知・空はUNKNOWN）
     */
    public static String toExternalStatus(String internalStatus) {
        if (internalStatus == null || internalStatus.isBlank() || internalStatus.length() > 64) {
            return UNKNOWN;
        }
        return switch (internalStatus.trim()) {
            case "未送付", "UNSENT", "DRAFT" -> UNSENT;
            case "送付済", "SENT" -> SENT;
            case "一部入金", "PARTIALLY_PAID", "PARTIAL" -> PARTIALLY_PAID;
            case "入金済", "PAID" -> PAID;
            default -> UNKNOWN;
        };
    }
}
