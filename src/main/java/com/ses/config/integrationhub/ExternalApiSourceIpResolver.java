package com.ses.config.integrationhub;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公開APIのsource IP解決（N5-P2-2）。
 *
 * <p>servletの {@code getRemoteAddr()} と転送headerの見え方は使わない。
 * {@code server.forward-headers-strategy: framework} のForwardedHeaderFilterがX-Forwarded-Forを隠し、
 * remoteAddrを最左（clientが自由に書ける値）へ書き換えるためである。代わりにTomcat valveが
 * filter前に捕捉したTCP peerと転送header原文だけを入力にする。
 *
 * <ul>
 *   <li>TCP peerがtrusted proxyでない: 転送headerがあれば拒否し、無ければTCP peerを返す。</li>
 *   <li>TCP peerがtrusted proxy: 設定したheader種別だけを読み、trusted proxyが追記した右端1 hopを返す。
 *       右端より左はclientが書けるため一切使わない。headerが無い、右端が不正、右端もtrusted proxy
 *       （多段proxy）の場合は拒否する。</li>
 *   <li>valveの捕捉値が無い（valveを経由しない経路）場合はservletの値を信用せず拒否する。</li>
 * </ul>
 */
@Component
public class ExternalApiSourceIpResolver {
    /** Tomcat connector境界で捕捉したTCP peer。ForwardedHeaderFilterのrewrite前に設定される。 */
    public static final String CONNECTOR_PEER_ATTRIBUTE =
            ExternalApiSourceIpResolver.class.getName() + ".CONNECTOR_PEER";

    public String resolve(HttpServletRequest request, IntegrationHubExternalApiProperties.Security security) {
        if (request == null) {
            throw ExternalApiSecurityException.authentication("SOURCE_IP_MISSING");
        }
        List<String> trustedProxies = security == null || security.getTrustedProxies() == null
                ? List.of() : security.getTrustedProxies();
        IntegrationHubExternalApiProperties.ForwardedHeader headerType = security == null
                || security.getForwardedHeader() == null
                ? IntegrationHubExternalApiProperties.ForwardedHeader.X_FORWARDED_FOR
                : security.getForwardedHeader();
        String connectorPeer = connectorPeer(request);
        ExternalApiRawForwardedHeaders raw = rawForwardedHeaders(request);
        if (connectorPeer == null || raw == null) {
            throw ExternalApiSecurityException.authentication("SOURCE_IP_INVALID");
        }
        if (raw.overflow()) {
            throw ExternalApiSecurityException.authentication("INVALID_PROXY_HEADER");
        }
        if (!ExternalApiCidrMatcher.matchesAny(connectorPeer, trustedProxies)) {
            if (raw.hasAny()) {
                throw ExternalApiSecurityException.authentication("UNTRUSTED_PROXY");
            }
            return connectorPeer;
        }
        List<String> lines = headerType == IntegrationHubExternalApiProperties.ForwardedHeader.FORWARDED
                ? raw.forwarded() : raw.xForwardedFor();
        if (lines.isEmpty()) {
            throw ExternalApiSecurityException.authentication("PROXY_HEADER_MISSING");
        }
        String combined = String.join(",", lines);
        String rightmost = headerType == IntegrationHubExternalApiProperties.ForwardedHeader.FORWARDED
                ? rightmostForwardedFor(combined) : rightmostXForwardedFor(combined);
        if (rightmost == null) {
            throw ExternalApiSecurityException.authentication("INVALID_PROXY_HEADER");
        }
        if (ExternalApiCidrMatcher.matchesAny(rightmost, trustedProxies)) {
            // trusted proxyの前段にさらにproxyがある構成は未対応。どちらがclientか判定できないため拒否する。
            throw ExternalApiSecurityException.authentication("UNSUPPORTED_PROXY_CHAIN");
        }
        return rightmost;
    }

    private String connectorPeer(HttpServletRequest request) {
        Object attribute = request.getAttribute(CONNECTOR_PEER_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) {
            return ExternalApiCidrMatcher.normalizeIp(value);
        }
        return null;
    }

    private ExternalApiRawForwardedHeaders rawForwardedHeaders(HttpServletRequest request) {
        Object attribute = request.getAttribute(ExternalApiRawForwardedHeaders.ATTRIBUTE);
        return attribute instanceof ExternalApiRawForwardedHeaders value ? value : null;
    }

    /** RFC 7230に従い複数行をカンマ連結した値の右端要素。portや空要素は不正として扱う。 */
    private String rightmostXForwardedFor(String combined) {
        int comma = combined.lastIndexOf(',');
        String last = (comma < 0 ? combined : combined.substring(comma + 1)).trim();
        if (last.isEmpty()) {
            return null;
        }
        return ExternalApiCidrMatcher.normalizeIp(last);
    }

    /** RFC 7239の右端forwarded-elementのfor=。右端要素内のquoted commaや重複for=は不正として扱う。 */
    private String rightmostForwardedFor(String combined) {
        int comma = combined.lastIndexOf(',');
        String element = (comma < 0 ? combined : combined.substring(comma + 1)).trim();
        if (element.isEmpty()) {
            return null;
        }
        String found = null;
        for (String parameter : element.split(";", -1)) {
            String[] pair = parameter.trim().split("=", -1);
            if (pair.length != 2 || pair[0].isBlank()) {
                return null;
            }
            if ("for".equalsIgnoreCase(pair[0].trim())) {
                if (found != null) {
                    return null;
                }
                String candidate = pair[1].trim();
                if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() >= 2) {
                    candidate = candidate.substring(1, candidate.length() - 1);
                }
                if (candidate.startsWith("[") && candidate.endsWith("]")) {
                    candidate = candidate.substring(1, candidate.length() - 1);
                }
                found = ExternalApiCidrMatcher.normalizeIp(candidate);
                if (found == null) {
                    return null;
                }
            }
        }
        return found;
    }
}
