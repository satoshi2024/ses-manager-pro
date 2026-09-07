package com.ses.service.integrationhub;

/**
 * 契約更新判断（renewalStatus）の内部値を公開API用大文字ASCIIコードへ変換する独立マッパー。
 * 未設定（nullまたは空白）はnullを返し、未知値や不正長はUNKNOWNへフェイルクローズする。
 */
public final class ExternalApiRenewalStatusMapper {
    public static final String CONTINUE = "CONTINUE";
    public static final String END = "END";
    public static final String UNKNOWN = "UNKNOWN";

    private ExternalApiRenewalStatusMapper() {
    }

    /**
     * 内部更新判断ステータスを外部公開コードへ変換する。
     *
     * @param internalStatus DB内部 renewal_decision 値
     * @return 安定した大文字ASCIIコード（未設定はnull、未知値はUNKNOWN）
     */
    public static String toExternalStatus(String internalStatus) {
        if (internalStatus == null || internalStatus.isBlank()) {
            return null;
        }
        if (internalStatus.length() > 64) {
            return UNKNOWN;
        }
        return switch (internalStatus.trim()) {
            case "CONTINUE", "継続", "継続確定", "RENEW" -> CONTINUE;
            case "END", "終了", "更新不要", "終了予定", "END_SCHEDULED" -> END;
            default -> UNKNOWN;
        };
    }
}
