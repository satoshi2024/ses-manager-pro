package com.ses.privacy.foundation;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Spring Security contextからだけproduction policy照会者を構築するpackage内provider。 */
final class SecurityContextCallerContextProvider {
    private static final String ADMIN_ROLE = "管理者";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final String PRIVACY_ADMIN_READ = "PRIVACY_ADMIN_READ";
    private static final Set<String> OIDC_TENANT_CLAIMS = Set.of("tenant_id", "tenantId", "tenant");

    private final LegalEntityContextService legalEntityContextService;
    private final SysUserMapper sysUserMapper;
    private final Clock identityObservationClock;

    SecurityContextCallerContextProvider(LegalEntityContextService legalEntityContextService,
                                         SysUserMapper sysUserMapper,
                                         Clock identityObservationClock) {
        this.legalEntityContextService = Objects.requireNonNull(
                legalEntityContextService, "legalEntityContextService must not be null");
        this.sysUserMapper = Objects.requireNonNull(sysUserMapper, "sysUserMapper must not be null");
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
            // 現在のpersisted userをtenant付きread-only queryで取り直し、stale session状態を信用しない。
            SysUser sysUser = sysUserMapper.selectByIdAndTenant(principalUser.getId(), tenantId);
            if (!isCurrentActiveAccount(sysUser) || !hasExplicitPrivacyAdminAuthority(authentication, sysUser)) {
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

    private boolean hasExplicitPrivacyAdminAuthority(Authentication authentication, SysUser sysUser) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        return ADMIN_ROLE.equals(sysUser.getRole())
                && authorities.contains(ADMIN_AUTHORITY)
                && authorities.contains(PRIVACY_ADMIN_READ);
    }
}
