package com.ses.config.integrationhub;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** request内だけに保持する監査trail。raw target/body/secret/IPは保持しない。 */
final class ExternalApiAuditTrail {
    private static final String ATTRIBUTE = ExternalApiAuditTrail.class.getName();
    private final Map<String, String> decisions = new LinkedHashMap<>();
    private String routeTemplate = "EXTERNAL_UNKNOWN_ROUTE";
    private String correlationId = "unavailable";
    private String postAuthPrincipal = "NONE";
    private Integer credentialVersion;
    private String keyId;

    private ExternalApiAuditTrail() {
        decisions.put("authentication", "NOT_REACHED");
        decisions.put("scope", "NOT_REACHED");
        decisions.put("dataScope", "NOT_REACHED");
        decisions.put("command", "NOT_REACHED");
        decisions.put("rate", "NOT_REACHED");
    }

    static void start(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, new ExternalApiAuditTrail());
    }

    static ExternalApiAuditTrail get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof ExternalApiAuditTrail trail ? trail : null;
    }

    static void route(HttpServletRequest request, String routeTemplate) {
        ExternalApiAuditTrail trail = get(request);
        if (trail != null && routeTemplate != null && !routeTemplate.isBlank()) {
            trail.routeTemplate = routeTemplate;
        }
    }

    static void correlation(HttpServletRequest request, String correlationId) {
        ExternalApiAuditTrail trail = get(request);
        if (trail != null && correlationId != null && !correlationId.isBlank()) {
            trail.correlationId = correlationId;
        }
    }

    static void principal(HttpServletRequest request, ExternalApiPrincipal principal) {
        ExternalApiAuditTrail trail = get(request);
        if (trail != null && principal != null) {
            trail.postAuthPrincipal = principal.clientId();
            trail.credentialVersion = principal.credentialVersion();
            trail.keyId = principal.keyId();
        }
    }

    static void mark(HttpServletRequest request, String category, String value) {
        ExternalApiAuditTrail trail = get(request);
        if (trail != null && trail.decisions.containsKey(category) && value != null && !value.isBlank()) {
            trail.decisions.put(category, bounded(value));
        }
    }

    String routeTemplate() {
        return routeTemplate;
    }

    String correlationId() {
        return correlationId;
    }

    String postAuthPrincipal() {
        return postAuthPrincipal;
    }

    Integer credentialVersion() {
        return credentialVersion;
    }

    String keyId() {
        return keyId;
    }

    String decision(String category) {
        return decisions.getOrDefault(category, "NOT_REACHED");
    }

    private static String bounded(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_~-]", "_");
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }
}
