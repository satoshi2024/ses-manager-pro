package com.ses.config.integrationhub;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 外部API metricsの有限label境界。識別子をlabelへ入れない。 */
@Component
@RequiredArgsConstructor
public class ExternalApiMetricsRecorder {
    private static final String METRIC = "integration_hub_external_api_requests_total";
    private static final Set<String> FINITE_ROUTES = Set.of(
            "/external-api/v1",
            "/external-api/v1/engineer-availability",
            "/external-api/v1/engineer-availability/{publicEngineerId}",
            "/external-api/v1/projects",
            "/external-api/v1/projects/count",
            "/external-api/v1/projects/{publicProjectId}",
            "/external-api/v1/contract-statuses",
            "/external-api/v1/contract-statuses/count",
            "/external-api/v1/contract-statuses/{publicContractId}",
            "/external-api/v1/invoice-statuses",
            "/external-api/v1/invoice-statuses/count",
            "/external-api/v1/invoice-statuses/{publicInvoiceId}",
            "/external-api/v1/webhooks/{provider}");
    /** 監査行を永続化しなかったrequestの集約counter（N5-P2-1）。labelは理由の有限集合だけ。 */
    static final String AUDIT_SUPPRESSED_METRIC = "integration_hub_external_api_audit_suppressed_total";
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 監査行を書かなかった理由。値はmetrics labelにそのまま使う。 */
    public enum AuditSuppressionReason {
        /** public-api無効時。DISABLED_DENYはDBへ書かずcounterだけを残す。 */
        PUBLIC_API_DISABLED,
        /** 有効時の未認証rejectがnode単位の1分上限を超えた。 */
        UNAUTHENTICATED_BUDGET
    }

    public void recordAuditSuppressed(AuditSuppressionReason reason) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null || reason == null) {
            return;
        }
        Counter.builder(AUDIT_SUPPRESSED_METRIC)
                .description("NF-05 external API requests whose audit row was not persisted")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }

    public void record(String routeTemplate, String method, int status, String decision, String clientTier) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC)
                .description("NF-05 external API request decisions")
                .tag("route", finiteRoute(routeTemplate))
                .tag("method", finiteMethod(method))
                .tag("status_class", finiteStatus(status))
                .tag("outcome", finiteOutcome(decision))
                .tag("client_tier", finiteTier(clientTier))
                .register(registry)
                .increment();
    }

    private String finiteRoute(String route) {
        return FINITE_ROUTES.contains(route) ? route : "EXTERNAL_UNKNOWN_ROUTE";
    }

    private String finiteMethod(String method) {
        if ("GET".equals(method)) return "GET";
        if ("POST".equals(method)) return "POST";
        if ("PUT".equals(method)) return "PUT";
        if ("PATCH".equals(method)) return "PATCH";
        if ("DELETE".equals(method)) return "DELETE";
        if ("OPTIONS".equals(method)) return "OPTIONS";
        return "OTHER";
    }

    private String finiteStatus(int status) {
        if (status < 100 || status > 599) return "other";
        return (status / 100) + "xx";
    }

    static String finiteOutcome(String decision) {
        if (decision == null) return "OTHER";
        return switch (decision) {
            case "AUTHORIZED", "AUTHENTICATED", "SCOPE_ALLOWED", "SUCCESS", "ALLOWED",
                    "INTERSECTION_ALLOWED", "READ_ALLOWED", "INBOUND_RECEIVE_ALLOWED" -> "SUCCESS";
            case "RATE_LIMITED" -> "RATE_LIMITED";
            case "FORBIDDEN_SCOPE", "SCOPE_OR_DATA_SCOPE_DENIED", "DATA_SCOPE_INVALID",
                    "DATA_SCOPE_INTERSECTION_DENIED", "INTERSECTION_DENIED" -> "SCOPE_REJECTED";
            case "UNTRUSTED_PROXY", "PROXY_HEADER_MISSING", "UNSUPPORTED_PROXY_CHAIN",
                    "INVALID_PROXY_HEADER", "DUPLICATE_PROXY_HEADER", "SOURCE_IP_INVALID", "SOURCE_IP_MISSING" -> "SOURCE_REJECTED";
            case "AUTH_HEADER_DUPLICATE", "AUTH_HEADER_MISSING", "CLIENT_ID_INVALID", "CLIENT_INVALID",
                    "CLIENT_IP_NOT_ALLOWED", "CREDENTIAL_INVALID", "CREDENTIAL_VERSION_INVALID", "KEY_ID_INVALID",
                    "NONCE_INVALID", "NONCE_REPLAY", "SIGNATURE_INVALID", "TIMESTAMP_INVALID",
                    "SESSION_NOT_ALLOWED", "BROWSER_REQUEST_NOT_ALLOWED", "EXTERNAL_PRINCIPAL_MISSING",
                    "AUTHENTICATION_FAILED" -> "AUTH_REJECTED";
            case "DISABLED_DENY", "PUBLIC_API_DISABLED" -> "DISABLED";
            case "INTERNAL_ERROR" -> "INTERNAL_ERROR";
            case "BODY_LENGTH_INVALID", "BODY_TOO_LARGE", "CONTENT_ENCODING_UNSUPPORTED", "CONTENT_LENGTH_INVALID",
                    "HEADER_BLOCK_INVALID", "HEADER_BLOCK_TOO_LARGE", "METHOD_INVALID", "REQUEST_INVALID",
                    "REQUEST_TARGET_INVALID", "REQUEST_TARGET_TOO_LARGE", "RAW_REQUEST_TARGET_UNAVAILABLE",
                    "QUERY_INVALID", "QUERY_TOO_LARGE", "CORRELATION_ID_INVALID", "CURSOR_INVALID",
                    "UNKNOWN_ROUTE_OR_METHOD", "EXTERNAL_UNKNOWN_ROUTE", "RESOURCE_NOT_FOUND",
                    "INBOUND_PAYLOAD_CONFLICT", "HTTP_REJECTED" -> "REQUEST_REJECTED";
            default -> "OTHER";
        };
    }

    private String finiteTier(String tier) {
        if ("STANDARD".equals(tier) || "PREMIUM".equals(tier) || "INTERNAL_TEST".equals(tier)) {
            return tier;
        }
        return "UNKNOWN";
    }
}
