package com.ses.service.pwa;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.pwa.PwaRestoreConfirmRequest;
import com.ses.dto.pwa.PwaRestorePermitResponse;
import com.ses.dto.pwa.PwaRestoreProofResponse;
import com.ses.dto.pwa.PwaRestoreReauthenticateRequest;
import com.ses.service.pwa.PwaRestoreIdentityService.CurrentIdentity;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** document単位の短命proofをsession内だけで発行・消費するNF04専用認可境界。 */
@Service
@RequiredArgsConstructor
public class PwaRestoreAuthorizationService {
    public static final String SESSION_STATE_ATTRIBUTE = PwaRestoreAuthorizationService.class.getName() + ".STATE";
    private static final String SECURITY_CONTEXT_ATTRIBUTE = "SPRING_SECURITY_CONTEXT";
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
    private static final Pattern PROOF = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Duration PROOF_TTL = Duration.ofSeconds(120);
    private static final Duration PERMIT_TTL = Duration.ofMinutes(15);
    private static final int MAX_PROOFS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PwaRestoreIdentityService identityService;
    private final PwaUserContextService userContextService;
    private final PwaRestoreFailureRecorder failureRecorder;
    private final PwaRestoreSessionRegistry sessionRegistry;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PwaRestoreProofResponse reauthenticate(PwaRestoreReauthenticateRequest request,
                                                   HttpSession session, Authentication authentication) {
        if (request == null || request.getPassword() == null || request.getPassword().isEmpty()
                || request.getPassword().length() > 1024) reject();
        validateBoundary(request.getDocumentNonce(), request.getUserScope(), session, authentication);
        String tenantId = SecurityUtils.currentTenantId();
        Long userId = SecurityUtils.currentUserId();
        CurrentIdentity identity = loadIdentity(userId, tenantId);
        if (!isEligible(identity, userId, tenantId) || !isCurrentAuthentication(authentication, identity)) {
            reject();
        }
        if (identity.locked()) reject();
        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(request.getPassword(), identity.passwordHash());
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.pwa.restore.unavailable");
        }
        if (!passwordMatches) {
            try {
                failureRecorder.record(userId, tenantId);
            } catch (RuntimeException e) {
                throw BusinessException.of(503, "error.pwa.restore.unavailable");
            }
            reject();
        }
        userContextService.assertRestoreScope(request.getUserScope(), tenantId, userId,
                identity.engineerId(), identity.linkId(), identity.linkedAt());

        String nonceHash = digest(request.getDocumentNonce());
        String scopeHash = digest(request.getUserScope());
        String rawProof = randomProof();
        String proofHash = digest(rawProof);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(PROOF_TTL);
        String sessionId = session.getId();
        AuthSnapshot authSnapshot = snapshot(authentication);
        ProofEntry entry = new ProofEntry(proofHash, nonceHash, scopeHash, sessionId, userId, tenantId,
                identity.engineerId(), identity.linkId(), identity.linkedAt(), identity.role(),
                digest(identity.passwordHash()), issuedAt, expiresAt, authentication, authSnapshot);
        // current identity readerのREQUIRES_NEW transactionがcommitしてからsessionへproofを登録する。
        CurrentIdentity current = loadIdentity(userId, tenantId);
        if (!sameIdentity(identity, current) || !isCurrentAuthentication(authentication, current)
                || !sessionStillAuthenticated(session, sessionId, authentication)
                || sessionRegistry.isDestroyed(sessionId)) reject();
        synchronized (WebUtils.getSessionMutex(session)) {
            if (sessionRegistry.isDestroyed(sessionId)
                    || !sessionStillAuthenticated(session, sessionId, authentication)) reject();
            try {
                SessionState state = state(session, true);
                purge(state, clock.instant());
                state.proofs.values().removeIf(old -> old.nonceHash.equals(nonceHash));
                state.permits.values().removeIf(old -> old.nonceHash.equals(nonceHash));
                if (state.proofs.size() >= MAX_PROOFS) reject();
                long generation = state.nonceGenerations.getOrDefault(nonceHash, 0L) + 1L;
                state.nonceGenerations.put(nonceHash, generation);
                entry.generation = generation;
                state.proofs.put(proofHash, entry);
                session.setAttribute(SESSION_STATE_ATTRIBUTE, state);
            } catch (IllegalStateException e) {
                reject();
            }
        }
        return new PwaRestoreProofResponse(rawProof, expiresAt);
    }

    public PwaRestorePermitResponse confirm(PwaRestoreConfirmRequest request,
                                            HttpSession session, Authentication authentication) {
        if (request == null) reject();
        validateBoundary(request.getDocumentNonce(), request.getUserScope(), session, authentication);
        if (request.getProof() == null || !PROOF.matcher(request.getProof()).matches()) reject();
        String sessionId = session.getId();
        String proofHash = digest(request.getProof());
        String nonceHash = digest(request.getDocumentNonce());
        String scopeHash = digest(request.getUserScope());
        ProofEntry entry;
        long claimedGeneration;
        synchronized (WebUtils.getSessionMutex(session)) {
            if (sessionRegistry.isDestroyed(sessionId) || !sessionStillAuthenticated(session, sessionId, authentication)) {
                reject();
            }
            SessionState state = state(session, false);
            if (state == null) reject();
            purge(state, clock.instant());
            entry = state.proofs.remove(proofHash);
            session.setAttribute(SESSION_STATE_ATTRIBUTE, state);
            if (entry == null || !entry.expiresAt.isAfter(clock.instant())
                    || !entry.sessionId.equals(sessionId) || entry.authentication != authentication
                    || !entry.authSnapshot.equals(snapshot(authentication))
                    || !constantTimeEquals(entry.nonceHash, nonceHash)
                    || !constantTimeEquals(entry.scopeHash, scopeHash)) reject();
            claimedGeneration = state.nonceGenerations.getOrDefault(nonceHash, 0L);
            if (entry.generation != claimedGeneration) reject();
        }

        CurrentIdentity current = loadIdentity(entry.userId, entry.tenantId);
        if (!sameBinding(entry, current) || current.locked()
                || !constantTimeEquals(entry.passwordFingerprint, digest(current.passwordHash()))
                || !isCurrentAuthentication(authentication, current)) reject();
        userContextService.assertRestoreScope(request.getUserScope(), entry.tenantId, entry.userId,
                entry.engineerId, entry.linkId, entry.linkedAt);

        Instant now = clock.instant();
        int maxInactive = session.getMaxInactiveInterval();
        Instant sessionExpiry = maxInactive <= 0 ? now.plus(PERMIT_TTL) : now.plusSeconds(maxInactive);
        Instant permitExpiresAt = now.plus(PERMIT_TTL);
        if (sessionExpiry.isBefore(permitExpiresAt)) permitExpiresAt = sessionExpiry;
        if (!permitExpiresAt.isAfter(now)) reject();
        synchronized (WebUtils.getSessionMutex(session)) {
            Instant finalNow = clock.instant();
            SessionState state = state(session, false);
            if (state == null || !entry.expiresAt.isAfter(finalNow)
                    || state.nonceGenerations.getOrDefault(nonceHash, 0L) != claimedGeneration
                    || sessionRegistry.isDestroyed(sessionId)
                    || !sessionStillAuthenticated(session, sessionId, authentication)
                    || entry.authentication != authentication
                    || !entry.authSnapshot.equals(snapshot(authentication))
                    || !isCurrentAuthentication(authentication, current)) reject();
            if (!permitExpiresAt.isAfter(finalNow)) reject();
            state.permits.values().removeIf(old -> old.nonceHash.equals(nonceHash));
            state.permits.put(nonceHash, new PermitEntry(nonceHash, scopeHash, entry.userId,
                    entry.tenantId, entry.engineerId, entry.linkId, permitExpiresAt));
            session.setAttribute(SESSION_STATE_ATTRIBUTE, state);
        }
        return new PwaRestorePermitResponse(request.getDocumentNonce(), request.getUserScope(), now, permitExpiresAt);
    }

    private static void validateBoundary(String documentNonce, String userScope, HttpSession session,
                                         Authentication authentication) {
        if (session == null || authentication == null || !authentication.isAuthenticated()
                || userScope == null || userScope.length() > 4096 || documentNonce == null
                || !NONCE.matcher(documentNonce).matches()) reject();
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(documentNonce);
            if (nonce.length < 16 || !Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    .equals(documentNonce)) reject();
        } catch (IllegalArgumentException e) {
            reject();
        }
    }

    private static boolean isEligible(CurrentIdentity identity, Long userId, String tenantId) {
        return identity != null && userId.equals(identity.userId()) && tenantId.equals(identity.tenantId())
                && "要員".equals(identity.role()) && identity.engineerId() != null
                && identity.linkId() != null && identity.linkedAt() != null;
    }

    private static boolean sameIdentity(CurrentIdentity first, CurrentIdentity second) {
        return first != null && sameBinding(first.userId(), first.tenantId(), first.role(), first.engineerId(),
                first.linkId(), first.linkedAt(), second)
                && constantTimeEquals(digest(first.passwordHash()), digest(second.passwordHash()))
                && first.locked() == second.locked();
    }

    private static boolean sameBinding(ProofEntry entry, CurrentIdentity current) {
        return current != null && sameBinding(entry.userId, entry.tenantId, entry.role, entry.engineerId,
                entry.linkId, entry.linkedAt, current);
    }

    private static boolean sameBinding(Long userId, String tenantId, String role, Long engineerId,
                                       Long linkId, Instant linkedAt, CurrentIdentity current) {
        return current != null && userId.equals(current.userId()) && tenantId.equals(current.tenantId())
                && role.equals(current.role()) && engineerId.equals(current.engineerId())
                && linkId.equals(current.linkId()) && linkedAt.equals(current.linkedAt());
    }

    private static boolean isCurrentAuthentication(Authentication authentication, CurrentIdentity identity) {
        if (identity == null || SecurityContextHolder.getContext().getAuthentication() != authentication
                || !authentication.isAuthenticated() || !identity.username().equals(authentication.getName())) return false;
        Set<String> authorities = new TreeSet<>();
        authentication.getAuthorities().forEach(authority -> authorities.add(authority.getAuthority()));
        return authorities.equals(Set.of("ROLE_要員"));
    }

    private static boolean sessionStillAuthenticated(HttpSession session, String expectedId,
                                                     Authentication authentication) {
        try {
            if (!expectedId.equals(session.getId())) return false;
            Object context = session.getAttribute(SECURITY_CONTEXT_ATTRIBUTE);
            return context instanceof SecurityContext securityContext
                    && securityContext.getAuthentication() == authentication;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static AuthSnapshot snapshot(Authentication authentication) {
        Set<String> authorities = new TreeSet<>();
        authentication.getAuthorities().forEach(authority -> authorities.add(authority.getAuthority()));
        return new AuthSnapshot(authentication.getClass().getName(), authentication.getName(),
                List.copyOf(authorities));
    }

    private static SessionState state(HttpSession session, boolean create) {
        Object value = session.getAttribute(SESSION_STATE_ATTRIBUTE);
        if (value instanceof SessionState state) return state;
        if (value != null || !create) return null;
        return new SessionState();
    }

    private static void purge(SessionState state, Instant now) {
        state.proofs.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
        state.permits.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
    }

    private static String randomProof() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("PWA復元proofの摘要生成に失敗しました", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static void reject() {
        throw BusinessException.of(403, "error.pwa.restore.rejected");
    }

    private CurrentIdentity loadIdentity(Long userId, String tenantId) {
        try {
            return identityService.load(userId, tenantId);
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.pwa.restore.unavailable");
        }
    }

    private static final class SessionState implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, ProofEntry> proofs = new HashMap<>();
        private final Map<String, PermitEntry> permits = new HashMap<>();
        private final Map<String, Long> nonceGenerations = new HashMap<>();
    }

    private static final class ProofEntry implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final String proofHash, nonceHash, scopeHash, sessionId, tenantId, role, passwordFingerprint;
        private final Long userId, engineerId, linkId;
        private final Instant linkedAt, issuedAt, expiresAt;
        private final transient Authentication authentication;
        private final AuthSnapshot authSnapshot;
        private long generation;
        private ProofEntry(String proofHash, String nonceHash, String scopeHash, String sessionId,
                           Long userId, String tenantId, Long engineerId, Long linkId, Instant linkedAt,
                           String role, String passwordFingerprint, Instant issuedAt, Instant expiresAt,
                           Authentication authentication, AuthSnapshot authSnapshot) {
            this.proofHash = proofHash; this.nonceHash = nonceHash; this.scopeHash = scopeHash;
            this.sessionId = sessionId; this.userId = userId; this.tenantId = tenantId;
            this.engineerId = engineerId; this.linkId = linkId; this.linkedAt = linkedAt; this.role = role;
            this.passwordFingerprint = passwordFingerprint; this.issuedAt = issuedAt; this.expiresAt = expiresAt;
            this.authentication = authentication; this.authSnapshot = authSnapshot;
        }
    }

    private record AuthSnapshot(String type, String name, List<String> authorities) implements java.io.Serializable { }
    private record PermitEntry(String nonceHash, String scopeHash, Long userId, String tenantId,
                               Long engineerId, Long linkId, Instant expiresAt) implements java.io.Serializable { }
}
