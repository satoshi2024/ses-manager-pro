package com.ses.service.security;

import org.springframework.security.core.Authentication;

/** menuだけに依存しないaction単位の認可境界。 */
public interface AuthorizationService {

    boolean isAllowed(Authentication authentication, String actionKey);

    default void assertAllowed(Authentication authentication, String actionKey) {
        if (!isAllowed(authentication, actionKey)) {
            throw new org.springframework.security.access.AccessDeniedException("action permission denied");
        }
    }
}
