package com.ses.service.pwa;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.entity.EngineerAccountLink;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
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
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
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
            boolean tenantBound = isTenantBoundScope(presentedScope);
            if (SecurityUtils.currentTenantId() != null && !tenantBound) {
                // tenant署名前のscopeはtenant移行後も同一user/linkに見えるためqueueを引き継がない。
                return new ContextResolution(issueContext(userId, engineerId), false);
            }
            if (!clock.instant().isAfter(presented.issuedAt().plusSeconds(MAX_LEASE_AGE_SECONDS))) {
                return new ContextResolution(presented, true);
            }
            // scope leaseの更新はrecordの30日retentionとは別管理にする。
            return new ContextResolution(issueContext(userId, engineerId), true);
        }
        return new ContextResolution(issueContext(userId, engineerId), false);
    }

    private static boolean isTenantBoundScope(String opaqueScope) {
        if (opaqueScope == null) return false;
        String[] parts = opaqueScope.split("\\.", -1);
        if (parts.length != 2) return false;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\.", -1);
            return fields.length == 6 && "v2".equals(fields[0]);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private CurrentContext issueContext(Long userId, Long engineerId) {
        Instant issuedAt = clock.instant();
        String tenantId = SecurityUtils.currentTenantId();
        String scope;
        if (tenantId == null) {
            scope = issue(userId, engineerId, issuedAt);
        } else {
            EngineerAccountLink link = engineerAccountLinkMapper.selectByUserIdAndTenant(userId, tenantId);
            if (tenantId.isBlank() || link == null || !engineerId.equals(link.getEngineerId())
                    || link.getId() == null || link.getLinkedAt() == null) {
                throw BusinessException.of(403, "error.pwa.restore.rejected");
            }
            scope = issueV2(userId, engineerId, tenantId, link, issuedAt);
        }
        return new CurrentContext(userId, engineerId, scope, issuedAt);
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

    /** 復元proof専用。legacy scopeはtenantへの署名がないため、ここでは受け付けない。 */
    public CurrentContext assertRestoreScope(String opaqueScope, String expectedTenantId,
                                             Long expectedUserId, Long expectedEngineerId, Long expectedLinkId,
                                             Instant expectedLinkedAt) {
        if (opaqueScope == null || expectedTenantId == null || expectedTenantId.isBlank()
                || expectedUserId == null || expectedEngineerId == null || expectedLinkId == null
                || expectedLinkedAt == null) {
            throw BusinessException.of(403, "error.pwa.restore.rejected");
        }
        String[] parts = opaqueScope.split("\\.", -1);
        if (parts.length != 2) throw BusinessException.of(403, "error.pwa.restore.rejected");
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw BusinessException.of(403, "error.pwa.restore.rejected");
        }
        String[] fields = payload.split("\\.", -1);
        if (fields.length != 6 || !"v2".equals(fields[0])) {
            throw BusinessException.of(403, "error.pwa.restore.rejected");
        }
        String tenant;
        Long linkId;
        Instant linkedAt;
        Instant issuedAt;
        String nonce;
        try {
            tenant = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            linkId = Long.parseLong(fields[2]);
            linkedAt = Instant.parse(new String(Base64.getUrlDecoder().decode(fields[3]), StandardCharsets.US_ASCII));
            issuedAt = Instant.parse(new String(Base64.getUrlDecoder().decode(fields[4]), StandardCharsets.US_ASCII));
            nonce = UUID.fromString(fields[5]).toString();
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw BusinessException.of(403, "error.pwa.restore.rejected");
        }
        Instant now = clock.instant();
        if (!MessageDigest.isEqual(tenant.getBytes(StandardCharsets.UTF_8),
                    expectedTenantId.getBytes(StandardCharsets.UTF_8))
                || !expectedLinkId.equals(linkId) || !expectedLinkedAt.equals(linkedAt)
                || !MessageDigest.isEqual(signV2(tenant, expectedUserId, expectedEngineerId, linkId,
                    linkedAt, issuedAt, nonce, payload)
                    .getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))
                || issuedAt.isAfter(now.plusSeconds(5 * 60L))
                || !issuedAt.plusSeconds(MAX_LEASE_AGE_SECONDS).isAfter(now)) {
            throw BusinessException.of(403, "error.pwa.restore.rejected");
        }
        return new CurrentContext(expectedUserId, expectedEngineerId, opaqueScope, issuedAt);
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
        String expectedSignature;
        Instant issuedAt;
        String[] versioned = payload.split("\\.", -1);
        if (versioned.length == 6 && "v2".equals(versioned[0])) {
            String tenantId;
            try {
                tenantId = new String(Base64.getUrlDecoder().decode(versioned[1]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                return null;
            }
            String currentTenant = SecurityUtils.currentTenantId();
            if (currentTenant == null || !MessageDigest.isEqual(currentTenant.getBytes(StandardCharsets.UTF_8),
                    tenantId.getBytes(StandardCharsets.UTF_8))) return null;
            EngineerAccountLink link = engineerAccountLinkMapper.selectByUserIdAndTenant(userId, currentTenant);
            if (link == null || link.getId() == null || link.getLinkedAt() == null
                    || !engineerId.equals(link.getEngineerId())) return null;
            Instant linkedAt = link.getLinkedAt().toInstant(java.time.ZoneOffset.UTC);
            try {
                if (Long.parseLong(versioned[2]) != link.getId()
                        || !linkedAt.equals(Instant.parse(new String(Base64.getUrlDecoder().decode(versioned[3]),
                        StandardCharsets.US_ASCII)))) return null;
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                return null;
            }
            String nonce;
            try {
                issuedAt = Instant.parse(new String(Base64.getUrlDecoder().decode(versioned[4]),
                        StandardCharsets.US_ASCII));
                nonce = UUID.fromString(versioned[5]).toString();
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                return null;
            }
            expectedSignature = signV2(tenantId, userId, engineerId, link.getId(),
                    linkedAt, issuedAt, nonce, payload);
        } else {
            expectedSignature = sign(payload, userId, engineerId);
            String[] values = payload.split(":", -1);
            if (values.length != 2 || values[0].isBlank() || values[1].isBlank()) return null;
            try {
                issuedAt = Instant.ofEpochMilli(Long.parseLong(values[0]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) return null;
        if (issuedAt.isAfter(clock.instant().plusSeconds(5 * 60L))) return null;
        return new CurrentContext(userId, engineerId, opaqueScope, issuedAt);
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

    private String issueV2(Long userId, Long engineerId, String tenantId, EngineerAccountLink link, Instant issuedAt) {
        Instant linkedAt = link.getLinkedAt().toInstant(java.time.ZoneOffset.UTC);
        String nonce = UUID.randomUUID().toString();
        String linked = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(linkedAt.toString().getBytes(StandardCharsets.US_ASCII));
        String issued = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(issuedAt.toString().getBytes(StandardCharsets.US_ASCII));
        String payload = "v2." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                tenantId.getBytes(StandardCharsets.UTF_8)) + "." + link.getId() + "."
                + linked + "." + issued
                + "." + nonce;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + signV2(tenantId, userId, engineerId, link.getId(), linkedAt, issuedAt, nonce, payload);
    }

    private String signV2(String tenantId, Long userId, Long engineerId, Long linkId,
                          Instant linkedAt, Instant issuedAt, String nonce, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] domain = "ses-manager:pwa-user-scope:v2".getBytes(StandardCharsets.UTF_8);
            byte[] purpose = "offline-draft-restore".getBytes(StandardCharsets.US_ASCII);
            byte[] tenant = tenantId.getBytes(StandardCharsets.UTF_8);
            byte[] user = userId.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] engineer = engineerId.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] link = linkId.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] linked = linkedAt.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] issued = issuedAt.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] nonceBytes = nonce.getBytes(StandardCharsets.US_ASCII);
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            ByteBuffer input = ByteBuffer.allocate(4 * 10 + domain.length + purpose.length + tenant.length + user.length
                    + engineer.length + link.length + linked.length + issued.length + nonceBytes.length + body.length);
            putField(input, domain);
            putField(input, purpose);
            putField(input, tenant);
            putField(input, user);
            putField(input, engineer);
            putField(input, link);
            putField(input, linked);
            putField(input, issued);
            putField(input, nonceBytes);
            putField(input, body);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.array()));
        } catch (Exception e) {
            throw new IllegalStateException("PWA user scopeの署名に失敗しました", e);
        }
    }

    private static void putField(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length).put(value);
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
