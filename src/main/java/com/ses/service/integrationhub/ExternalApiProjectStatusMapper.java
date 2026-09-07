package com.ses.service.integrationhub;

/**
 * 案件（Project）の内部ステータスを公開API用大文字ASCIIコードへ変換する独立マッパー。
 * 内部の日本語状態を隠蔽し、未知値や不正長はUNKNOWNへフェイルクローズする。
 */
public final class ExternalApiProjectStatusMapper {
    public static final String OPEN = "OPEN";
    public static final String SELECTING = "SELECTING";
    public static final String FILLED = "FILLED";
    public static final String CLOSED = "CLOSED";
    public static final String UNKNOWN = "UNKNOWN";

    private ExternalApiProjectStatusMapper() {
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
            case "募集中", "OPEN" -> OPEN;
            case "選考中", "SELECTING" -> SELECTING;
            case "充足", "FILLED" -> FILLED;
            case "クローズ", "CLOSED" -> CLOSED;
            default -> UNKNOWN;
        };
    }
}
