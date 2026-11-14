package com.ses.config.integrationhub;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * public-api有効時に、未認証rejectが監査tableへ書く行数をnode単位・1分単位で抑える（N5-P2-1）。
 *
 * <p>認証前の外部trafficは誰でも送れるため、1 request 1 rowのままだと監査tableとHikari接続を
 * 外部から消費させられる。上限内は従来どおり永続化し、超過分は
 * {@code integration_hub_external_api_audit_suppressed_total{reason="UNAUTHENTICATED_BUDGET"}} へ集約する。
 * 認証済みrequestと5xxはこの上限の対象外で、常に永続化を試みる（呼出し側の責務）。
 *
 * <p>上限は {@code integration.hub.public-api.unauthenticated-audit-max-per-minute}。0は未認証rejectを永続化しない。
 * 窓は時計の分境界で切り替える固定窓で、複数nodeでは合計がnode数倍になる。
 */
@Component
@RequiredArgsConstructor
public class ExternalApiUnauthenticatedAuditBudget {
    private static final long WINDOW_MILLIS = 60_000L;

    private final ObjectProvider<IntegrationHubExternalApiProperties> propertiesProvider;
    private final ObjectProvider<Clock> clockProvider;

    private long currentWindow = Long.MIN_VALUE;
    private int used;

    /** 今の窓に永続化枠が残っていれば1件消費してtrue。 */
    public synchronized boolean tryAcquire() {
        int limit = limit();
        if (limit <= 0) {
            return false;
        }
        long window = Math.floorDiv(clock().millis(), WINDOW_MILLIS);
        if (window != currentWindow) {
            currentWindow = window;
            used = 0;
        }
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }

    private int limit() {
        IntegrationHubExternalApiProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null || properties.getPublicApi() == null) {
            return 0;
        }
        return properties.getPublicApi().getUnauthenticatedAuditMaxPerMinute();
    }

    private Clock clock() {
        Clock configured = clockProvider.getIfAvailable();
        return configured != null ? configured : Clock.systemUTC();
    }
}
