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

    /**
     * NF01（細粒度epoch計画 §4.1）: SecurityContextを使わず、DBから読んだ明示actor（tenant・利用者ID・role）で
     * {@link #isAllowed} と同じaction判定を行う。{@code ExplicitActorAuthorizationEvaluator} が使う。
     *
     * <p>既定実装は拒否する（fail-closed）。実装を持たない代替bean・mockで許可へ倒れないようにするため。</p>
     */
    default boolean isAllowedForActor(String tenantId, Long userId, String role, String actionKey) {
        return false;
    }
}
