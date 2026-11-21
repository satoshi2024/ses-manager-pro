package com.ses.backup.quiesce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.result.ApiResult;
import com.ses.common.util.RequestPaths;
import com.ses.config.integrationhub.ExternalApiErrorWriter;
import com.ses.config.integrationhub.ExternalApiMetricsRecorder;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * backup の書込み静止の HTTP の入口（design.md §6.4・§6.10）。
 *
 * <p>順は {@link #ORDER}（{@code FeatureSwitchFilter} の +30 の後、Spring Security の -100 より前）。OFF の機能は静止中も 404 の
 * まま返し、login の POST は {@code LoginFailureHandler} が失敗の回数を数える前に止まる。servlet の filter なので社内・portal・
 * 外部 API の全ての security chain の前に効く。
 *
 * <ul>
 *   <li>{@code WRITE}・{@code EXTERNAL}: I-4 の順で HTTP の permit を取って通し、要求の終わりに返す。取れなければ 503。</li>
 *   <li>{@code READ}・{@code ALWAYS}: permit を取らずに通す（業務の接続は読取り専用。§6.5）。</li>
 *   <li>{@code LOGOUT}: 取れれば permit を持って従来どおり。取れなければ permit なしで通し、logout の handler が失効と監査を
 *       解除の後へ回す（U-8。§6.13）。</li>
 * </ul>
 * chain 全体を包み、静止による拒否（{@link BackupWriteQuiescedException}、gate が書込みを許さない間の読取り専用の拒否（server の
 * 1792 か Connector/J の client 側の S1009））を、応答が
 * 確定していなければ 503 に変える（最後の防線の変換）。
 *
 * <p>gate・metric・文言の部品は {@code ObjectProvider} で使う時に引く。{@code @WebMvcTest} 等で gate が無ければ、また
 * INERT（test profile の既定）なら何もしない。
 */
@Component
@Order(BackupWriteGateFilter.ORDER)
public class BackupWriteGateFilter extends OncePerRequestFilter {

    /** {@code FeatureSwitchFilter.ORDER}（+30）の後、Spring Security（-100）より前。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 35;

    private static final Logger log = LoggerFactory.getLogger(BackupWriteGateFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int OWNER_MAX_LENGTH = 160;

    private final ObjectProvider<BackupWriteGate> gateProvider;
    private final ObjectProvider<BackupQuiesceMetrics> metricsProvider;
    private final ObjectProvider<ExternalApiMetricsRecorder> externalMetricsProvider;
    private final ObjectProvider<MessageSource> messageSourceProvider;
    private final ObjectProvider<LocaleResolver> localeResolverProvider;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final ObjectProvider<BackupQuiesceRejectionRecorder> rejectionRecorderProvider;
    private final ObjectProvider<IntegrationHubExternalApiProperties> externalApiPropertiesProvider;

    public BackupWriteGateFilter(ObjectProvider<BackupWriteGate> gateProvider,
                                 ObjectProvider<BackupQuiesceMetrics> metricsProvider,
                                 ObjectProvider<ExternalApiMetricsRecorder> externalMetricsProvider,
                                 ObjectProvider<MessageSource> messageSourceProvider,
                                 ObjectProvider<LocaleResolver> localeResolverProvider,
                                 ObjectProvider<ObjectMapper> objectMapperProvider,
                                 ObjectProvider<BackupQuiesceRejectionRecorder> rejectionRecorderProvider,
                                 ObjectProvider<IntegrationHubExternalApiProperties> externalApiPropertiesProvider) {
        this.gateProvider = gateProvider;
        this.metricsProvider = metricsProvider;
        this.externalMetricsProvider = externalMetricsProvider;
        this.messageSourceProvider = messageSourceProvider;
        this.localeResolverProvider = localeResolverProvider;
        this.objectMapperProvider = objectMapperProvider;
        this.rejectionRecorderProvider = rejectionRecorderProvider;
        this.externalApiPropertiesProvider = externalApiPropertiesProvider;
    }

    /** NF05 の公開 API（inbound）が有効か（{@code ExternalApiDisabledFilter} と同じ判定。設定が無ければ無効）。 */
    private boolean publicApiEnabled() {
        IntegrationHubExternalApiProperties properties = externalApiPropertiesProvider.getIfAvailable();
        return properties != null && properties.getPublicApi() != null
                && Boolean.TRUE.equals(properties.getPublicApi().getEnabled());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        BackupWriteGate gate = gateProvider.getIfAvailable();
        if (gate == null || gate.mode() == BackupWriteGate.Mode.INERT) {
            filterChain.doFilter(request, response);
            return;
        }
        BackupQuiesceRequestClassifier.Classification classification = BackupQuiesceRequestClassifier.classify(request);
        BackupQuiesceRequestClassifier.Kind kind = classification.kind();
        if (kind == BackupQuiesceRequestClassifier.Kind.EXTERNAL && !publicApiEnabled()) {
            // NF05 の公開 API が OFF の間は、静止中も平時と同じ 404（ExternalApiDisabledFilter。DB へ書かず
            // audit_suppressed{PUBLIC_API_DISABLED} だけを数える）を返す（§6.4「OFF の機能は静止中も 404」。2b review P3-2）
            kind = BackupQuiesceRequestClassifier.Kind.READ;
        }
        BackupWritePermit permit = null;
        switch (kind) {
            case WRITE, EXTERNAL -> {
                permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, owner(request));
                if (permit == null) {
                    reject(request, response, gate, classification);
                    return;
                }
            }
            case LOGOUT -> permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, owner(request));
            default -> {
                // READ・ALWAYS は permit を取らない
            }
        }
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            if (response.isCommitted() || !BackupQuiesceRejections.isQuiesceRejection(failure, gate)) {
                throw failure;
            }
            convertToRejection(request, response, gate, classification, failure);
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    /** 静止中の書込みの要求を 503 にする（認証より前）。 */
    private void reject(HttpServletRequest request, HttpServletResponse response, BackupWriteGate gate,
                        BackupQuiesceRequestClassifier.Classification classification) throws IOException {
        BackupQuiesceMetrics metrics = metricsProvider.getIfAvailable();
        int retryAfter = BackupQuiesceRejections.retryAfterSeconds(gate);
        if (classification == BackupQuiesceRequestClassifier.Classification.EXTERNAL_API) {
            rejectExternal(request, response, retryAfter);
            if (metrics != null) {
                metrics.rejected("external");
            }
            return;
        }
        String path = RequestPaths.pathWithinApplication(request);
        boolean json = BackupQuiesceRejections.isJsonRequest(request, path);
        if (metrics != null) {
            metrics.rejected(switch (classification) {
                case DOWNLOAD -> "download";
                case PRIVILEGED_SESSION -> "session";
                default -> json ? "api" : "page";
            });
        }
        if (log.isDebugEnabled()) {
            log.debug("backup の静止中のため更新の要求を 503 にしました（{} {}、分類={}）", request.getMethod(),
                    RequestPaths.forAudit(path), classification);
        }
        writeRejection(request, response, json, retryAfter);
    }

    /** 外部 API（U-6）: {@code ExternalApiErrorWriter} の 503。監査行は書かず、抑止の metric だけを数える。 */
    private void rejectExternal(HttpServletRequest request, HttpServletResponse response, int retryAfter)
            throws IOException {
        String correlationId = ExternalApiErrorWriter.assignCorrelationId(request, response);
        response.setHeader(BackupQuiesceRejections.ERROR_CODE_HEADER, BackupQuiesceRejections.ERROR_CODE);
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(() -> JSON);
        ExternalApiErrorWriter.write(response, mapper, correlationId, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                BackupQuiesceRejections.ERROR_CODE, true, retryAfter);
        ExternalApiMetricsRecorder externalMetrics = externalMetricsProvider.getIfAvailable();
        if (externalMetrics != null) {
            externalMetrics.recordAuditSuppressed(ExternalApiMetricsRecorder.AuditSuppressionReason.BACKUP_QUIESCED);
        }
    }

    /** chain の中で静止による拒否（最後の防線の読取り専用の拒否等）が起きた: 応答が確定していなければ 503 にする。 */
    private void convertToRejection(HttpServletRequest request, HttpServletResponse response, BackupWriteGate gate,
                                    BackupQuiesceRequestClassifier.Classification classification, Throwable failure)
            throws IOException {
        String path = RequestPaths.pathWithinApplication(request);
        boolean external = classification == BackupQuiesceRequestClassifier.Classification.EXTERNAL_API;
        boolean json = BackupQuiesceRejections.isJsonRequest(request, path);
        // 数え方と読取り専用の拒否の WARN の間引きは API の handler と共有する（2b review P2-1）
        BackupQuiesceRejectionRecorder recorder = rejectionRecorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.record(failure, external, json,
                    request.getMethod() + " " + RequestPaths.forAudit(path) + "、分類=" + classification, "filter");
        }
        response.resetBuffer();
        int retryAfter = BackupQuiesceRejections.retryAfterSeconds(gate);
        if (external) {
            rejectExternal(request, response, retryAfter);
            return;
        }
        writeRejection(request, response, json, retryAfter);
    }

    private void writeRejection(HttpServletRequest request, HttpServletResponse response, boolean json,
                                int retryAfter) throws IOException {
        response.setHeader(BackupQuiesceRejections.RETRY_AFTER_HEADER, Integer.toString(retryAfter));
        response.setHeader(BackupQuiesceRejections.ERROR_CODE_HEADER, BackupQuiesceRejections.ERROR_CODE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (json) {
            String message = BackupQuiesceRejections.writeUnavailableMessage(messageSourceProvider.getIfAvailable(),
                    resolveLocale(request));
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write(JSON.writeValueAsString(
                    ApiResult.error(HttpServletResponse.SC_SERVICE_UNAVAILABLE, message)));
            return;
        }
        request.setAttribute(BackupWriteGate.REJECTED_ATTRIBUTE, Boolean.TRUE);
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    private Locale resolveLocale(HttpServletRequest request) {
        LocaleResolver resolver = localeResolverProvider.getIfUnique();
        if (resolver != null) {
            try {
                Locale resolved = resolver.resolveLocale(request);
                if (resolved != null) {
                    return resolved;
                }
            } catch (RuntimeException ignored) {
                // locale の解決の失敗で 503 の応答自体を失敗させない。既定（日本語）へ倒す
            }
        }
        return Locale.JAPANESE;
    }

    /** permit の持ち主（method と path。個人データを含めない）。 */
    private static String owner(HttpServletRequest request) {
        String owner = request.getMethod() + " " + RequestPaths.forAudit(RequestPaths.pathWithinApplication(request));
        return owner.length() > OWNER_MAX_LENGTH ? owner.substring(0, OWNER_MAX_LENGTH) : owner;
    }
}
