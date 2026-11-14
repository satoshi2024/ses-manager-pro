package com.ses.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

import java.nio.charset.StandardCharsets;

/**
 * 認可・監査・rate limit・MFA強制などの判定に使う「アプリ内path」（FWD-SANITIZE PRE-P1-1）。
 *
 * <p>{@code getRequestURI()} はcontext pathを含み、framework strategyのForwardedHeaderFilterを通ると
 * {@code X-Forwarded-Prefix} が先頭に付く（{@code /x/api/...}）。%エンコードも未復号のままである。
 * 一方、Spring Securityのmatcherと MVCのhandler解決はcontext path（＝prefix）を除いた復号後のpathで照合するため、
 * {@code getRequestURI()} で判定するとcontrollerには到達するのに判定だけ外れる（menu・action権限の素通り、
 * 監査漏れ、rate limit無効、{@code /mfa}・{@code /css} 等を前置したallow-list判定の誤一致）。
 *
 * <p>ここで返すpathはcontext pathを除き、%復号・セミコロン除去・連続スラッシュ正規化をしたもの
 * （{@code UrlPathHelper#getPathWithinApplication}。{@code FeatureSwitchFilter} と同じ取得方法）。
 * 不正な%エンコードはServlet container/firewallで拒否されるため、復号に失敗したときはcontext pathを除いた
 * 生のpathを返す。
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** context pathを除いた、復号・正規化済みのアプリ内path。requestがnullなら空文字。 */
    public static String pathWithinApplication(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return "";
        }
        if (request.getContextPath() == null) {
            // Servlet containerはnullを返さない（mock等だけ）。UrlPathHelperはnullを扱えないため生のpathで返す。
            return rawPathWithinApplication(request);
        }
        try {
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (path != null && !path.isEmpty()) {
                return path;
            }
        } catch (IllegalArgumentException malformed) {
            // 不正な%エンコード。下の生のpathで判定する（containerが先に拒否するため通常は来ない）。
        }
        return rawPathWithinApplication(request);
    }

    /** アプリ内pathが {@code /api/} 配下か（応答をJSONにするかの判定用）。 */
    public static boolean isApiPath(HttpServletRequest request) {
        return pathWithinApplication(request).startsWith("/api/");
    }

    /** 監査・logへ書くアプリ内path（{@link #forAudit(String)} 済み）。判定には {@link #pathWithinApplication} を使う。 */
    public static String auditPath(HttpServletRequest request) {
        return forAudit(pathWithinApplication(request));
    }

    /**
     * 監査（{@code t_audit_log.uri}）・logへ書く前に、復号で現れた制御文字・書式文字を {@code %XX}（UTF-8）へ戻す。
     *
     * <p>CR・LF・NULはfirewallが拒否するが、ESC（{@code %1b}）やbidi制御（{@code %E2%80%AE} 等）は通るため、
     * そのまま書くと端末・log viewerの表示を偽装できる。対象はUnicodeの制御文字（Cc）・書式文字（Cf）・
     * 行/段落区切り・単独surrogate。表示用の変換であり、権限判定には使わない。
     */
    public static String forAudit(String path) {
        if (path == null) {
            return null;
        }
        StringBuilder escaped = null;
        int i = 0;
        while (i < path.length()) {
            int codePoint = path.codePointAt(i);
            int length = Character.charCount(codePoint);
            if (needsEscape(codePoint)) {
                if (escaped == null) {
                    escaped = new StringBuilder(path.length() + 16).append(path, 0, i);
                }
                byte[] bytes = Character.getType(codePoint) == Character.SURROGATE
                        ? "�".getBytes(StandardCharsets.UTF_8)
                        : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    escaped.append('%').append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
                }
            } else if (escaped != null) {
                escaped.append(path, i, i + length);
            }
            i += length;
        }
        return escaped == null ? path : escaped.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static boolean needsEscape(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE;
    }

    private static String rawPathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }
}
