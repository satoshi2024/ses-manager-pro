package com.ses.privacy.foundation;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Spring Security contextからだけproduction policy照会者を構築するpackage内provider。
 *
 * <p>privacy read 権限の正本は permission group の action 権限（{@code privacy.policy.view}。privacy-admin group だけが
 * 許可）である（B-NF07-2、production-bridge-phased-plan.md §6.4）。GrantedAuthority 文字列（旧 {@code PRIVACY_ADMIN_READ}）
 * は判定に使わず、login 時に権限を焼き込まない（group変更から再loginまで古い権限が残るため）。
 * 永続 {@link SysUser} を tenant 付きで再読し、role が管理者であることを確認した上で、
 * {@link AuthorizationService#isAllowed} が true の場合だけ呼び出し元を返す。
 * {@code AuthorizationService} は設定tenantで group を評価するため、principal の tenant と設定tenantが
 * 一致しない場合は拒否する（別tenantの group で評価させない）。{@code AuthorizationService} が取れない・例外は拒否。
 */
final class SecurityContextCallerContextProvider {
    private static final String ADMIN_ROLE = "管理者";
    /** caller 判定に使う privacy action（説明照会の入口と同じ）。 */
    static final String PRIVACY_POLICY_VIEW_ACTION = "privacy.policy.view";
    private static final Set<String> OIDC_TENANT_CLAIMS = Set.of("tenant_id", "tenantId", "tenant");

    private final LegalEntityContextService legalEntityContextService;
    private final SysUserMapper sysUserMapper;
    private final Supplier<AuthorizationService> authorizationServiceSupplier;
    private final String authorizationTenantId;
    private final Clock identityObservationClock;

    SecurityContextCallerContextProvider(LegalEntityContextService legalEntityContextService,
                                         SysUserMapper sysUserMapper,
                                         Supplier<AuthorizationService> authorizationServiceSupplier,
                                         String authorizationTenantId,
                                         Clock identityObservationClock) {
        this.legalEntityContextService = Objects.requireNonNull(
                legalEntityContextService, "legalEntityContextService must not be null");
        this.sysUserMapper = Objects.requireNonNull(sysUserMapper, "sysUserMapper must not be null");
        // supplierとtenantの欠落は構築時に拒否せず、照会時に拒否する（取れない = 拒否）。
        this.authorizationServiceSupplier = authorizationServiceSupplier;
        this.authorizationTenantId = authorizationTenantId;
        this.identityObservationClock = Objects.requireNonNull(
                identityObservationClock, "identityObservationClock must not be null");
    }

    Optional<TrustedCallerContext> getCallerContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        try {
            Object principal = authentication.getPrincipal();
            LoginUser loginUser;
            String tenantId;
            if (principal instanceof OidcLoginUser oidcLoginUser) {
                loginUser = oidcLoginUser;
                tenantId = resolveOidcTenant(oidcLoginUser);
            } else if (principal instanceof LoginUser internalLoginUser) {
                loginUser = internalLoginUser;
                tenantId = internalLoginUser.getTenantId();
            } else {
                // External API、Portal、service principal、anonymousおよび未知型は本sliceでは許可しない。
                return Optional.empty();
            }

            SysUser principalUser = loginUser.getSysUser();
            if (principalUser == null || !isCurrentActiveAccount(principalUser)
                    || tenantId == null || tenantId.isBlank()) {
                return Optional.empty();
            }
            // AuthorizationServiceImpl は設定tenantで group を評価する。principal の tenant と異なれば、
            // 別tenantの group で privacy 権限を判定することになるため拒否する。
            if (authorizationTenantId == null || !authorizationTenantId.equals(tenantId)) {
                return Optional.empty();
            }
            // 現在のpersisted userをtenant付きread-only queryで取り直し、stale session状態を信用しない。
            SysUser sysUser = sysUserMapper.selectByIdAndTenant(principalUser.getId(), tenantId);
            if (!isCurrentActiveAccount(sysUser) || !hasPrivacyPolicyViewAction(authentication)) {
                return Optional.empty();
            }

            Long legalEntityId = legalEntityContextService.requireCurrentLegalEntityId();
            if (legalEntityId == null || legalEntityId <= 0L) {
                return Optional.empty();
            }

            // authenticatedAtはログイン時刻ではなく、このproviderがidentity contextを観察した時刻。
            return Optional.of(new TrustedCallerContext(
                    tenantId, Long.toString(sysUser.getId()), TrustedActorRole.ROLE_ADMIN,
                    identityObservationClock.instant()));
        } catch (RuntimeException rejected) {
            return Optional.empty();
        }
    }

    private String resolveOidcTenant(OidcLoginUser oidcLoginUser) {
        String internalBinding = oidcLoginUser.getTenantId();
        if (internalBinding == null || internalBinding.isBlank()) {
            return null;
        }
        Map<String, Object> claims = oidcLoginUser.getClaims();
        if (claims == null) {
            return null;
        }
        for (String claimName : OIDC_TENANT_CLAIMS) {
            if (!claims.containsKey(claimName)) {
                continue;
            }
            Object claim = claims.get(claimName);
            if (!(claim instanceof String claimTenant) || !internalBinding.equals(claimTenant)) {
                return null;
            }
        }
        return internalBinding;
    }

    private boolean isCurrentActiveAccount(SysUser sysUser) {
        if (sysUser == null || sysUser.getId() == null
                || !Integer.valueOf(1).equals(sysUser.getStatus())
                || !Integer.valueOf(0).equals(sysUser.getDeletedFlag())
                || !ADMIN_ROLE.equals(sysUser.getRole())) {
            return false;
        }
        LocalDateTime lockedUntil = sysUser.getLockedUntil();
        return lockedUntil == null || !lockedUntil.isAfter(LocalDateTime.now(identityObservationClock));
    }

    /** permission group の action 権限で判定する。判定部品が無い・例外は拒否（fail-closed）。 */
    private boolean hasPrivacyPolicyViewAction(Authentication authentication) {
        if (authorizationServiceSupplier == null) {
            return false;
        }
        try {
            AuthorizationService authorizationService = authorizationServiceSupplier.get();
            return authorizationService != null
                    && authorizationService.isAllowed(authentication, PRIVACY_POLICY_VIEW_ACTION);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
