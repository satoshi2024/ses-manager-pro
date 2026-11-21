package com.ses.backup.quiesce;

import com.ses.common.util.AuditedDownloadPaths;
import com.ses.common.util.RequestPaths;
import com.ses.config.ProductionBootstrapMode;
import com.ses.service.security.BreakGlassService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.List;

/**
 * backup の書込み静止の間の HTTP の要求の分類（design.md §6.3・§6.4）。純粋な関数で、DB を読まない。
 *
 * <ul>
 *   <li>{@link Kind#ALWAYS}: 静的資源・{@code /actuator/health/**}・{@code /actuator/prometheus}・{@code /actuator/metrics/**}
 *       （GET・HEAD）・error dispatch。静止中も permit なしで通す。</li>
 *   <li>{@link Kind#READ}: その他の GET・HEAD・OPTIONS。permit なしで通す（業務の接続は読取り専用）。</li>
 *   <li>{@link Kind#WRITE}: 更新の method、書込みを伴う既知の GET（OIDC・freee の連携）、file の download（U-10）、
 *       bootstrap・break-glass の session の要求（毎回の監査を残せないため。logout を除く）。portal の logout もここ（U-8 の対象外）。</li>
 *   <li>{@link Kind#LOGOUT}: 社内の {@code POST /logout}（U-8）。bootstrap・break-glass の session の logout も含む（2b review P3-1）。</li>
 *   <li>{@link Kind#EXTERNAL}: 外部 API {@code /external-api/**}（U-6。全 method が監査行を書く）。</li>
 * </ul>
 * 判定の path は復号・正規化済みのアプリ内 path（{@link RequestPaths#pathWithinApplication}。認可・監査と同じ）。外部 API の
 * path だけは生の URI でも照合し、表記ゆれで外部 API の形の 503 を外さない。
 */
public final class BackupQuiesceRequestClassifier {

    /** 静止中の扱い。 */
    public enum Kind {
        ALWAYS, READ, WRITE, LOGOUT, EXTERNAL
    }

    /** 分類の理由（試験・metric の label の材料）。 */
    public enum Classification {
        ERROR_DISPATCH(Kind.ALWAYS),
        STATIC_RESOURCE(Kind.ALWAYS),
        ACTUATOR(Kind.ALWAYS),
        EXTERNAL_API(Kind.EXTERNAL),
        PRIVILEGED_SESSION(Kind.WRITE),
        LOGOUT(Kind.LOGOUT),
        UPDATE_METHOD(Kind.WRITE),
        WRITING_GET(Kind.WRITE),
        DOWNLOAD(Kind.WRITE),
        READ(Kind.READ);

        private final Kind kind;

        Classification(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** 書込みを伴う既知の GET の path の接頭辞（OIDC の開始・callback、freee の連携の開始・callback）。 */
    static final List<String> WRITING_GET_PREFIXES = List.of(
            "/oauth2/authorization/", "/login/oauth2/code/", "/integrations/freee/");

    private BackupQuiesceRequestClassifier() {
    }

    public static Classification classify(HttpServletRequest request) {
        boolean errorDispatch = request.getDispatcherType() == DispatcherType.ERROR;
        HttpSession session = request.getSession(false);
        boolean privileged = ProductionBootstrapMode.isBootstrapSession(session)
                || session != null && session.getAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE) != null;
        return classify(request.getMethod(), RequestPaths.pathWithinApplication(request), rawPath(request),
                errorDispatch, privileged);
    }

    /**
     * @param method     HTTP の method
     * @param path       復号・正規化済みのアプリ内 path
     * @param rawPath    context path を除いた生の URI
     * @param errorDispatch error dispatch か
     * @param privilegedSession bootstrap・break-glass の session か
     */
    public static Classification classify(String method, String path, String rawPath, boolean errorDispatch,
                                          boolean privilegedSession) {
        if (errorDispatch) {
            return Classification.ERROR_DISPATCH;
        }
        String normalized = path == null ? "" : path;
        if (isExternalApi(normalized) || isExternalApi(rawPath)) {
            return Classification.EXTERNAL_API;
        }
        boolean safe = isSafeMethod(method);
        boolean readOnlyMethod = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        if (readOnlyMethod && isStaticResource(normalized)) {
            return Classification.STATIC_RESOURCE;
        }
        if (readOnlyMethod && isAlwaysActuator(normalized)) {
            return Classification.ACTUATOR;
        }
        // 社内の logout は特権の session（bootstrap・break-glass）でも U-8 で終わらせる（HTTP session を直ちに無効にし、失効と
        // 監査を解除の後に書く。特権の session を静止の間も直ちに終えられる方が安全。2b review P3-1・統括の決定）
        if ("POST".equalsIgnoreCase(method) && "/logout".equals(normalized)) {
            return Classification.LOGOUT;
        }
        if (privilegedSession) {
            return Classification.PRIVILEGED_SESSION;
        }
        if (!safe) {
            return Classification.UPDATE_METHOD;
        }
        if (readOnlyMethod && WRITING_GET_PREFIXES.stream().anyMatch(prefix -> normalized.startsWith(prefix)
                || normalized.equals(prefix.substring(0, prefix.length() - 1)))) {
            return Classification.WRITING_GET;
        }
        if ("GET".equalsIgnoreCase(method) && AuditedDownloadPaths.isDownloadUri(normalized)) {
            return Classification.DOWNLOAD;
        }
        return Classification.READ;
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    /** {@code SecurityInfrastructureUtils.isStaticResource} と同じ path（method の判定は呼び出し側）。 */
    private static boolean isStaticResource(String path) {
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/lib/")
                || path.startsWith("/img/") || path.equals("/favicon.ico");
    }

    private static boolean isAlwaysActuator(String path) {
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/prometheus")
                || path.equals("/actuator/metrics") || path.startsWith("/actuator/metrics/");
    }

    private static boolean isExternalApi(String path) {
        return path != null && (path.equals("/external-api") || path.startsWith("/external-api/"));
    }

    private static String rawPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
