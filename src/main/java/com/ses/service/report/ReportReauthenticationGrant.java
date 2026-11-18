package com.ses.service.report;

import com.ses.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP requestごとの認証instance/sessionへ再認証grantを束縛する。 */
@Component
public class ReportReauthenticationGrant {

    private static final String SESSION_ATTRIBUTE = ReportReauthenticationGrant.class.getName() + ".grants";
    private static final Duration GRANT_LIFETIME = Duration.ofMinutes(10);

    private final Clock clock;

    public ReportReauthenticationGrant(Clock clock) {
        this.clock = clock;
    }

    public void issue(HttpServletRequest request, String tenantId, Long userId, Long deliveryId, String scopeHash) {
        HttpSession session = request == null ? null : request.getSession(false);
        Authentication authentication = currentAuthentication();
        if (session == null || authentication == null || userId == null || deliveryId == null
                || tenantId == null || scopeHash == null || scopeHash.isBlank()) {
            throw reauthenticationRequired();
        }
        grants(session, true).put(deliveryId, new Grant(session.getId(), authentication, tenantId, userId,
                deliveryId, scopeHash, authorities(authentication), clock.instant().plus(GRANT_LIFETIME)));
    }

    public void require(HttpServletRequest request, String tenantId, Long userId,
                        Long deliveryId, String currentScopeHash) {
        HttpSession session = request == null ? null : request.getSession(false);
        if (session == null) throw reauthenticationRequired();
        Map<Long, Grant> grants = grants(session, false);
        Grant grant = grants == null ? null : grants.get(deliveryId);
        Authentication authentication = currentAuthentication();
        boolean valid = grant != null && authentication != null
                && grant.sessionId.equals(session.getId())
                && grant.authentication == authentication
                && grant.tenantId.equals(tenantId)
                && grant.userId.equals(userId)
                && grant.deliveryId.equals(deliveryId)
                && grant.scopeHash.equals(currentScopeHash)
                && grant.authorities.equals(authorities(authentication))
                && clock.instant().isBefore(grant.expiresAt);
        if (!valid) {
            if (grants != null && deliveryId != null) grants.remove(deliveryId);
            throw reauthenticationRequired();
        }
    }

    private Map<Long, Grant> grants(HttpSession session, boolean create) {
        synchronized (session) {
            Object value = session.getAttribute(SESSION_ATTRIBUTE);
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked") Map<Long, Grant> typed = (Map<Long, Grant>) map;
                return typed;
            }
            if (!create) return null;
            Map<Long, Grant> created = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_ATTRIBUTE, created);
            return created;
        }
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }

    private Set<String> authorities(Authentication authentication) {
        return new TreeSet<>(authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).toList());
    }

    private BusinessException reauthenticationRequired() {
        return BusinessException.of(403, "error.managementReport.reauthenticationRequired");
    }

    private record Grant(String sessionId, Authentication authentication, String tenantId, Long userId,
                         Long deliveryId, String scopeHash, Set<String> authorities, Instant expiresAt)
            implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
