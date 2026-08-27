package com.ses.service.pwa;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** PWA commandを現在の内部ユーザー・要員linkへ束縛するopaque context。 */
@Service
@RequiredArgsConstructor
public class PwaUserContextService {
    private static final String DEFAULT_SECRET = "dev-only-change-this-pwa-context-secret";
    private static final long MAX_LEASE_AGE_SECONDS = 30 * 24 * 60 * 60L;

    private final EngineerAccountLinkService engineerAccountLinkService;
    private final Environment environment;
    private final Clock clock;

    @Value("${app.pwa.context-secret:}")
    private String contextSecret;

    @PostConstruct
    void validateSecret() {
        if (environment.acceptsProfiles("prod")
                && (contextSecret == null || contextSecret.isBlank()
                || DEFAULT_SECRET.equals(contextSecret))) {
            throw new IllegalStateException("PWA context secretはprodで必須です");
        }
    }

    public CurrentContext current() {
        return resolve(null).context();
    }

    /** 同一ユーザーの再認証では、未失効の既存scopeを再提示してqueueを継続する。 */
    public CurrentContext current(String presentedScope) {
        return resolve(presentedScope).context();
    }

    /** scopeの更新理由をclientへ伝え、同一ユーザーのrecordだけを保持できるようにする。 */
    public ContextResolution resolve(String presentedScope) {
        Long userId = requireUserId();
        Long engineerId = engineerAccountLinkService.findEngineerIdByUserId(userId);
        if (engineerId == null) throw BusinessException.of(403, "error.my.notLinked");
        CurrentContext presented = parseAndVerify(presentedScope, userId, engineerId);
        if (presented != null) {
            if (!clock.instant().isAfter(presented.issuedAt().plusSeconds(MAX_LEASE_AGE_SECONDS))) {
                return new ContextResolution(presented, true);
            }
            // scope leaseの更新はrecordの30日retentionとは別管理にする。
            return new ContextResolution(issueContext(userId, engineerId), true);
        }
        return new ContextResolution(issueContext(userId, engineerId), false);
    }

    private CurrentContext issueContext(Long userId, Long engineerId) {
        Instant issuedAt = clock.instant();
        return new CurrentContext(userId, engineerId, issue(userId, engineerId, issuedAt), issuedAt);
    }

    public CurrentContext assertCurrent(String opaqueScope) {
        if (opaqueScope == null || opaqueScope.isBlank()) {
            throw BusinessException.of(400, "error.pwa.userScopeRequired");
        }
        Long userId = requireUserId();
        Long engineerId = engineerAccountLinkService.findEngineerIdByUserId(userId);
        if (engineerId == null) {
            throw BusinessException.of(403, "error.pwa.userScopeMismatch");
        }
        CurrentContext context = parseAndVerify(opaqueScope, userId, engineerId);
        if (context == null) throw BusinessException.of(403, "error.pwa.userScopeMismatch");
        return context;
    }

    private CurrentContext parseAndVerify(String opaqueScope, Long userId, Long engineerId) {
        if (opaqueScope == null || opaqueScope.isBlank()) return null;
        String[] parts = opaqueScope.split("\\.", -1);
        if (parts.length != 2) return null;
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(sign(payload, userId, engineerId).getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) return null;
        String[] values = payload.split(":", -1);
        if (values.length != 2 || values[0].isBlank() || values[1].isBlank()) return null;
        try {
            Instant issuedAt = Instant.ofEpochMilli(Long.parseLong(values[0]));
            if (issuedAt.isAfter(clock.instant().plusSeconds(5 * 60L))) return null;
            return new CurrentContext(userId, engineerId, opaqueScope, issuedAt);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String hashScope(String opaqueScope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(opaqueScope.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("PWA user scope hashに失敗しました", e);
        }
    }

    private String issue(Long userId, Long engineerId, Instant issuedAt) {
        // 内部IDをtoken本体へ出さず、現在のprincipalとのHMAC束縛だけでscopeを検証する。
        String payload = issuedAt.toEpochMilli() + ":" + UUID.randomUUID();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + sign(payload, userId, engineerId);
    }

    private String sign(String payload, Long userId, Long engineerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((userId + ":" + engineerId + ":" + payload)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("PWA user scopeの署名に失敗しました", e);
        }
    }

    private String secret() {
        return contextSecret == null || contextSecret.isBlank() ? DEFAULT_SECRET : contextSecret;
    }

    private Long requireUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) throw BusinessException.of(401, "error.session.expired");
        return userId;
    }

    public record CurrentContext(Long userId, Long engineerId, String userScope, Instant issuedAt) {
        /** テストおよび既存の呼出元向け。実リクエストではserver発行時刻を必ず持つ。 */
        public CurrentContext(Long userId, Long engineerId, String userScope) {
            this(userId, engineerId, userScope, null);
        }
    }

    /** scope更新時に、同一ユーザーのrecord retentionをscope leaseと独立して維持するための結果。 */
    public record ContextResolution(CurrentContext context, boolean preserveQueue) {
    }
}
