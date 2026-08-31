package com.ses.config.integrationhub;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** trusted proxyを通過した一段の転送元だけを許可する厳格なsource IP解決。 */
@Component
public class ExternalApiSourceIpResolver {
    /** Tomcat connector境界で捕捉したTCP peer。ForwardedHeaderFilterのrewrite前に設定される。 */
    public static final String CONNECTOR_PEER_ATTRIBUTE =
            ExternalApiSourceIpResolver.class.getName() + ".CONNECTOR_PEER";

    public String resolve(HttpServletRequest request, List<String> trustedProxies) {
        if (request == null) {
            throw ExternalApiSecurityException.authentication("SOURCE_IP_MISSING");
        }
        String connectorPeer = connectorPeer(request);
        if (connectorPeer == null) {
            throw ExternalApiSecurityException.authentication("SOURCE_IP_INVALID");
        }
        String servletAddr = ExternalApiCidrMatcher.normalizeIp(request.getRemoteAddr());
        if (servletAddr == null) {
            throw ExternalApiSecurityException.authentication("SOURCE_IP_INVALID");
        }
        String forwarded = singleHeader(request, "Forwarded");
        String xForwarded = singleHeader(request, "X-Forwarded-For");
        if (forwarded == null && xForwarded == null) {
            if (ExternalApiCidrMatcher.matchesAny(connectorPeer, trustedProxies)) {
                return servletAddr;
            }
            return connectorPeer;
        }
        if (!ExternalApiCidrMatcher.matchesAny(connectorPeer, trustedProxies)
                || (forwarded != null && xForwarded != null)) {
            throw ExternalApiSecurityException.authentication("UNTRUSTED_PROXY");
        }
        String resolved = forwarded == null ? parseXForwardedFor(xForwarded) : parseForwarded(forwarded);
        if (resolved == null) {
            throw ExternalApiSecurityException.authentication("INVALID_PROXY_HEADER");
        }
        return resolved;
    }

    private String connectorPeer(HttpServletRequest request) {
        Object attribute = request.getAttribute(CONNECTOR_PEER_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) {
            return ExternalApiCidrMatcher.normalizeIp(value);
        }
        return ExternalApiCidrMatcher.normalizeIp(request.getRemoteAddr());
    }

    private String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()) {
            throw ExternalApiSecurityException.authentication("DUPLICATE_PROXY_HEADER");
        }
        return value;
    }

    private String parseXForwardedFor(String value) {
        String[] hops = value.split(",", -1);
        if (hops.length != 1) {
            return null;
        }
        return ExternalApiCidrMatcher.normalizeIp(hops[0].trim());
    }

    private String parseForwarded(String value) {
        if (value.contains(",")) {
            return null;
        }
        String found = null;
        for (String parameter : value.split(";", -1)) {
            String[] pair = parameter.trim().split("=", -1);
            if (pair.length != 2 || pair[0].isBlank()) {
                return null;
            }
            if ("for".equalsIgnoreCase(pair[0])) {
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
            }
        }
        return found;
    }
}
