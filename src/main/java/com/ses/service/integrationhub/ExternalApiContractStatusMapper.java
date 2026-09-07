package com.ses.service.integrationhub;

/**
 * 契約状態（Contract Status）の内部ステータスを公開API用大文字ASCIIコードへ変換する独立マッパー。
 * 内部の日本語状態を隠蔽し、未知値や不正長はUNKNOWNへフェイルクローズする。
 */
public final class ExternalApiContractStatusMapper {
    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String UNKNOWN = "UNKNOWN";

    private ExternalApiContractStatusMapper() {
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
            case "準備中", "DRAFT", "PREPARING" -> DRAFT;
            case "稼動中", "ACTIVE" -> ACTIVE;
            case "終了", "COMPLETED", "ENDED" -> COMPLETED;
            case "解約", "CANCELLED", "TERMINATED" -> CANCELLED;
            default -> UNKNOWN;
        };
    }
}
