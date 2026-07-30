package com.ses.service.security;

import com.ses.entity.BreakGlassIncident;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Set;

public interface BreakGlassService {
    String INCIDENT_ID_ATTRIBUTE = "SES_BREAK_GLASS_INCIDENT_ID";
    String EXPIRES_AT_ATTRIBUTE = "SES_BREAK_GLASS_EXPIRES_AT";

    enum BreakGlassDecision {
        ALLOW,
        DENY_SCOPE,
        REVOKE
    }

    boolean hasActiveIncident();
    boolean isLoginAllowed(String username);
    BreakGlassIncident create(Long actorId, String reason, boolean idpOutageConfirmed,
                              int durationMinutes, String correlationId, Set<String> allowedActions);
    BreakGlassIncident approve(Long actorId, Long incidentId);
    void close(Long actorId, Long incidentId);
    boolean bindSession(HttpServletRequest request, String username);
    BreakGlassDecision validateBoundSession(HttpServletRequest request, Authentication authentication);
}
