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
            "/external-api/v1/invoice-statuses/{publicInvoiceId}");
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

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

    private String finiteOutcome(String decision) {
        if (decision == null) return "OTHER";
        if (decision.contains("RATE")) return "RATE_LIMITED";
        if (decision.contains("SCOPE") || decision.contains("DATA_SCOPE")) return "SCOPE_REJECTED";
        if (decision.contains("AUTH") || decision.contains("CLIENT") || decision.contains("NONCE")) {
            return "AUTH_REJECTED";
        }
        if (decision.contains("DISABLED")) return "DISABLED";
        if (decision.contains("INVALID") || decision.contains("UNKNOWN_ROUTE")) return "REQUEST_REJECTED";
        if (decision.contains("AUTHORIZED") || decision.contains("SUCCESS")) return "SUCCESS";
        if (decision.contains("INTERNAL")) return "INTERNAL_ERROR";
        return "OTHER";
    }

    private String finiteTier(String tier) {
        if ("STANDARD".equals(tier) || "PREMIUM".equals(tier) || "INTERNAL_TEST".equals(tier)) {
            return tier;
        }
        return "UNKNOWN";
    }
}
