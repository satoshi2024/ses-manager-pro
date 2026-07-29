package com.ses.service.security;

import com.ses.entity.BreakGlassIncident;

public interface BreakGlassService {
    boolean hasActiveIncident();
    boolean isLoginAllowed(String username);
    BreakGlassIncident create(Long actorId, String reason, boolean idpOutageConfirmed,
                              int durationMinutes, String correlationId);
    BreakGlassIncident approve(Long actorId, Long incidentId);
    void close(Long actorId, Long incidentId);
}
