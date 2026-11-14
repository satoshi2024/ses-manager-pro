package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.SecurityHashUtil;
import com.ses.config.MfaSecurityProperties;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.PersistentSessionProperties;
import com.ses.entity.MfaAttemptGuard;
import com.ses.mapper.MfaAttemptGuardMapper;
import com.ses.service.security.MfaAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MfaAttemptServiceImpl implements MfaAttemptService {

    private final MfaAttemptGuardMapper mapper;
    private final MfaSecurityProperties properties;
    private final PersistentSessionProperties sessionProperties;
    private final OidcSecurityProperties oidcProperties;
    private final Clock clock;

    /**
     * 送信元scopeのIP解決（FWD-SANITIZE PRE-P2-1）。X-Forwarded-Forの最左はclientが書けるため直接読まない。
     * 既存constructorを保つためsetter注入にし、未注入時は信頼プロキシ無し（清浄化filterのattribute、無ければTCP peer）。
     */
    private ClientIpResolver clientIpResolver = new ClientIpResolver("");

    @Autowired(required = false)
    public void setClientIpResolver(ClientIpResolver clientIpResolver) {
        if (clientIpResolver != null) {
            this.clientIpResolver = clientIpResolver;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertAllowed(Long userId, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Scope scope : scopes(request)) {
            MfaAttemptGuard guard = select(userId, scope);
            if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
                throw BusinessException.of(429, "error.mfa.rateLimited");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailure(Long userId, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Scope scope : scopes(request)) {
            recordFailure(userId, scope, now);
        }
    }

    private void recordFailure(Long userId, Scope scope, LocalDateTime now) {
        MfaAttemptGuard guard = select(userId, scope);
        if (guard == null) {
            guard = new MfaAttemptGuard();
            guard.setTenantId(tenantId());
            guard.setUserId(userId);
            guard.setSessionKeyHash(scope.sessionKeyHash());
            guard.setSourceHash(scope.sourceHash());
            guard.setFailedCount(1);
            guard.setWindowStartedAt(now);
            if (properties.getMaxAttempts() <= 1) {
                guard.setLockedUntil(now.plusSeconds(properties.getLockSeconds()));
            }
            try {
                mapper.insert(guard);
            } catch (DuplicateKeyException e) {
                // 同一scopeの並行失敗は回数を過少計上せず、安全側で一時拒否する。
                throw BusinessException.of(429, "error.mfa.rateLimited");
            }
            return;
        }
        boolean newWindow = guard.getWindowStartedAt() == null
                || !guard.getWindowStartedAt().plusSeconds(properties.getAttemptWindowSeconds()).isAfter(now);
        int failed = newWindow ? 1 : Math.max(0, guard.getFailedCount() == null ? 0 : guard.getFailedCount()) + 1;
        guard.setFailedCount(failed);
        guard.setWindowStartedAt(newWindow ? now : guard.getWindowStartedAt());
        if (failed >= properties.getMaxAttempts()) {
            guard.setLockedUntil(now.plusSeconds(properties.getLockSeconds()));
        }
        mapper.updateById(guard);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordSuccess(Long userId, HttpServletRequest request) {
        for (Scope scope : scopes(request)) {
            MfaAttemptGuard guard = select(userId, scope);
            if (guard != null) {
                guard.setFailedCount(0);
                guard.setWindowStartedAt(null);
                guard.setLockedUntil(null);
                mapper.updateById(guard);
            }
        }
    }

    private MfaAttemptGuard select(Long userId, Scope scope) {
        return mapper.selectForUpdate(tenantId(), userId, scope.sessionKeyHash(), scope.sourceHash());
    }

    private List<Scope> scopes(HttpServletRequest request) {
        String anySession = scopedHash("ANY_SESSION");
        String anySource = scopedHash("ANY_SOURCE");
        return List.of(
                new Scope(anySession, anySource),
                new Scope(sessionHash(request), anySource),
                new Scope(anySession, sourceHash(request)));
    }

    private String sessionHash(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return scopedHash(session == null ? "NO_SESSION" : session.getId());
    }

    private String sourceHash(HttpServletRequest request) {
        String source = clientIpResolver.resolve(request);
        return scopedHash(StringUtils.hasText(source) && !"unknown".equals(source) ? source : "UNKNOWN_SOURCE");
    }

    private String scopedHash(String value) {
        return SecurityHashUtil.sha256(sessionProperties.getHashKey() + ":mfa-attempt:" + value);
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }

    private record Scope(String sessionKeyHash, String sourceHash) {
    }
}
