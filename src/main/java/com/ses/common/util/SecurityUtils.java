package com.ses.common.util;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcLoginUser) {
            return ((OidcLoginUser) principal).getSysUser().getId();
        }
        if (principal instanceof LoginUser) {
            return ((LoginUser) principal).getSysUser().getId();
        }
        // 標準UserDetails/OIDC adapterがローカルIDをusernameとして渡す場合に対応する。
        // 解決できない外部subjectはnullのままにし、組織scopeを推測で拡張しない。
        return parseLong(principal instanceof UserDetails
                ? ((UserDetails) principal).getUsername() : authentication.getName());
    }

    public static String currentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcLoginUser) {
            return ((OidcLoginUser) authentication.getPrincipal()).getSysUser().getRole();
        }
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser) {
            return ((LoginUser) authentication.getPrincipal()).getSysUser().getRole();
        }
        if (authentication != null) {
            return authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                    .map(authority -> authority.substring("ROLE_".length()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    public static boolean isHrRole() {
        return com.ses.common.constant.StatusConstants.ROLE_HR.equals(currentRole());
    }

    public static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcLoginUser) {
            return ((OidcLoginUser) authentication.getPrincipal()).getUsername();
        }
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser) {
            return ((LoginUser) authentication.getPrincipal()).getUsername();
        }
        if (authentication != null && authentication.getPrincipal() instanceof String) {
            return (String) authentication.getPrincipal();
        }
        if (authentication != null && authentication.getPrincipal() instanceof UserDetails) {
            return ((UserDetails) authentication.getPrincipal()).getUsername();
        }
        return null;
    }

    /**
     * 現在の認証principalへ明示的に束縛されたtenantだけを返す。
     * OIDC claim、local loginの認証時束縛値、authentication details以外からは推測しない。
     */
    public static String currentTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcLoginUser oidc) {
            String claim = firstText(oidc.getClaims(), "tenant_id", "tenantId", "tenant");
            return claim;
        }
        if (principal instanceof LoginUser loginUser && hasText(loginUser.getTenantId())) {
            return loginUser.getTenantId().trim();
        }
        if (principal instanceof com.ses.portal.PortalLoginUser portalUser && hasText(portalUser.getTenantId())) {
            return portalUser.getTenantId().trim();
        }
        if (authentication.getDetails() instanceof Map<?, ?> details) {
            Object tenant = details.get("tenant_id");
            if (tenant == null) tenant = details.get("tenantId");
            if (tenant != null && hasText(tenant.toString())) {
                return tenant.toString().trim();
            }
        }
        return null;
    }

    private static String firstText(Map<String, Object> claims, String... names) {
        if (claims == null) return null;
        for (String name : names) {
            Object value = claims.get(name);
            if (value != null && hasText(value.toString())) return value.toString().trim();
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
