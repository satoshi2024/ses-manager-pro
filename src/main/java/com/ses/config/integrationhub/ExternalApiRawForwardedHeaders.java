package com.ses.config.integrationhub;

import java.util.List;

/**
 * Tomcat connector境界で捕捉した転送ヘッダーの原文（N5-P2-2）。
 *
 * <p>{@code server.forward-headers-strategy: framework} の ForwardedHeaderFilter は
 * Forwarded/X-Forwarded-For を servlet から隠し、remoteAddr を最左（client が書ける値）へ書き換える。
 * そのため公開APIのsource IP判定は servlet の見え方を使わず、valve がfilter前に保存したこの値だけを使う。
 * 値はrequest attributeにだけ保持し、監査・log・metricsへ出さない。
 *
 * @param xForwardedFor X-Forwarded-For の各header行（受信順）
 * @param forwarded     Forwarded の各header行（受信順）
 * @param overflow      行数・総文字数の上限超過。超過時は値を信用せず拒否する
 */
public record ExternalApiRawForwardedHeaders(List<String> xForwardedFor, List<String> forwarded, boolean overflow) {
    public static final String ATTRIBUTE = ExternalApiRawForwardedHeaders.class.getName();
    /** 同名header行の上限。正規のproxy経路で複数行になることはほぼ無い。 */
    static final int MAX_HEADER_LINES = 8;
    /** 両header合計の文字数上限。右端1 hopだけを使うため、長大な左側は不要。 */
    static final int MAX_TOTAL_CHARS = 2048;

    private static final ExternalApiRawForwardedHeaders NONE =
            new ExternalApiRawForwardedHeaders(List.of(), List.of(), false);

    public ExternalApiRawForwardedHeaders {
        xForwardedFor = xForwardedFor == null ? List.of() : List.copyOf(xForwardedFor);
        forwarded = forwarded == null ? List.of() : List.copyOf(forwarded);
    }

    /** 転送headerを一切持たないrequest。 */
    public static ExternalApiRawForwardedHeaders none() {
        return NONE;
    }

    /** 捕捉した行から上限判定込みで生成する。nullの行は不正として上限超過扱いにする。 */
    public static ExternalApiRawForwardedHeaders of(List<String> xForwardedFor, List<String> forwarded) {
        List<String> xff = xForwardedFor == null ? List.of() : xForwardedFor;
        List<String> fwd = forwarded == null ? List.of() : forwarded;
        boolean overflow = xff.size() > MAX_HEADER_LINES || fwd.size() > MAX_HEADER_LINES;
        long total = 0;
        for (List<String> lines : List.of(xff, fwd)) {
            for (String line : lines) {
                if (line == null) {
                    overflow = true;
                    continue;
                }
                total += line.length();
            }
        }
        overflow = overflow || total > MAX_TOTAL_CHARS;
        if (overflow) {
            return new ExternalApiRawForwardedHeaders(List.of(), List.of(), true);
        }
        return new ExternalApiRawForwardedHeaders(xff, fwd, false);
    }

    boolean hasAny() {
        return !xForwardedFor.isEmpty() || !forwarded.isEmpty();
    }

    @Override
    public String toString() {
        // 値はclientが書ける原文を含むため、toStringからも出さない。
        return "ExternalApiRawForwardedHeaders[xForwardedForLines=" + xForwardedFor.size()
                + ", forwardedLines=" + forwarded.size() + ", overflow=" + overflow + "]";
    }
}
