package com.ses.service.security;

import com.ses.dto.security.SessionSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.List;

/** DBに失効状態を持つsession metadataサービス。 */
public interface PersistentSessionService {

    String TRACKED_ATTRIBUTE = "SES_SESSION_TRACKED";
    String SESSION_HASH_ATTRIBUTE = "SES_SESSION_HASH";

    void register(HttpServletRequest request, Authentication authentication);

    boolean validateAndTouch(HttpServletRequest request, Authentication authentication);

    List<SessionSummary> list(Authentication authentication, HttpServletRequest request);

    void revoke(Long sessionId, Long userId, String currentHash, String reason);

    void revokeOthers(Long userId, String currentHash, String reason);

    void revokeAllForUser(Long userId, String reason);
}
