package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.PermissionGroup;
import com.ses.entity.PermissionGroupAction;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/** permission groupを優先し、未割当ユーザーだけlegacy role fallbackを使う。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationServiceImpl implements AuthorizationService {

    private static final String ROLE_ADMIN = "管理者";
    private static final String ROLE_HR = "HR";
    private static final String ROLE_MEMBER = "要員";
    /** 既知inventoryのactionを旧menuとの積集合で維持するrole。 */
    private static final Set<String> INTERNAL_ROLES = Set.of("営業", ROLE_HR, "マネージャー");
    /** roleに関係なく管理者だけが実行できるaction。SecurityConfigでも重ねて制限している。 */
    private static final Set<String> ADMIN_ONLY_ACTIONS =
            Set.of("permission.manage", "audit.security.view", "file.scan.retry", "mfa.reset");

    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final PermissionGroupActionMapper permissionGroupActionMapper;
    private final com.ses.mapper.PermissionGroupMapper permissionGroupMapper;
    private final OidcSecurityProperties oidcProperties;

    @Override
    public boolean isAllowed(Authentication authentication, String actionKey) {
        if (authentication == null || !authentication.isAuthenticated() || !StringUtils.hasText(actionKey)) {
            return false;
        }
        String role = SecurityUtils.currentRole();
        if ("one-on-one.confidential".equals(actionKey)) {
            if (ROLE_HR.equals(role)) {
                return true;
            }
            if (!ROLE_ADMIN.equals(role)) {
                return false;
            }
        } else if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        if (ADMIN_ONLY_ACTIONS.contains(actionKey)) {
            return false;
        }
        if (actionKey.startsWith("profile.")) {
            return true;
        }
        try {
            Long userId = SecurityUtils.currentUserId();
            if (userId != null) {
                Set<Long> assignedGroupIds = userPermissionGroupMapper.selectList(new LambdaQueryWrapper<UserPermissionGroup>()
                                .eq(UserPermissionGroup::getTenantId, tenantId())
                                .eq(UserPermissionGroup::getUserId, userId))
                        .stream().map(UserPermissionGroup::getGroupId).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                if (!assignedGroupIds.isEmpty()) {
                    Set<Long> enabledGroupIds = permissionGroupMapper.selectList(new LambdaQueryWrapper<PermissionGroup>()
                                    .eq(PermissionGroup::getTenantId, tenantId())
                                    .eq(PermissionGroup::getEnabled, 1)
                                    .in(PermissionGroup::getId, assignedGroupIds))
                            .stream().map(PermissionGroup::getId).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toSet());
                    if (enabledGroupIds.isEmpty()) {
                        return false;
                    }
                    Set<String> candidates = candidateKeys(actionKey);
                    // 明示拒否はbaseline許可（action_key='*'）より優先する。先に評価しないと
                    // baselineを与えたroleから機密actionを外せない。
                    if (matches(enabledGroupIds, candidates, 1)) {
                        return false;
                    }
                    return matches(enabledGroupIds, candidates, 0);
                }
            }
            return legacyRoleAllows(role, actionKey);
        } catch (RuntimeException e) {
            log.warn("action権限のDB判定に失敗したため拒否します: action={}", actionKey, e);
            return false;
        }
    }

    /**
     * group未割当ユーザーのfallback。inventory登録済みactionだけを旧menu層へ渡す。
     * 未知actionを追加コードなしで許可しないことが境界である。
     */
    private boolean legacyRoleAllows(String role, String actionKey) {
        if (role == null) {
            return false;
        }
        if ("one-on-one.confidential".equals(actionKey)) {
            return ROLE_HR.equals(role);
        }
        if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        // 要員はSecurityConfigのanyRequest().hasAnyRole(...)から除外されており、
        // 到達できる経路自体が本人向けに限定されている。baselineは与えず許可listで表す。
        if (ROLE_MEMBER.equals(role)) {
            return actionKey.equals("file.download") || actionKey.startsWith("profile.")
                    || actionKey.startsWith("my.") || actionKey.startsWith("notifications.");
        }
        if (!INTERNAL_ROLES.contains(role)
                || !com.ses.service.security.ActionPermissionResolver.isKnownAction(actionKey)) {
            return false;
        }
        if (actionKey.startsWith("user.")) {
            return false;
        }
        // payroll menuはV21で管理者とHRにだけ付与されている。
        if (actionKey.equals("payroll.view")) {
            return ROLE_HR.equals(role);
        }
        // R3.3の原価マスキング。HRは原価を参照しない。
        if (actionKey.equals("contract.cost.view")) {
            return !ROLE_HR.equals(role);
        }
        return true;
    }

    /**
     * actionKey本体・resource wildcard・baselineの3種を1回のSQLで突き合わせる。
     *
     * <p>ドットを含まないkeyではwildcardがactionKey自身と一致するため、
     * {@code Set.of}（重複で例外）ではなく重複を許す集合で組み立てる。
     */
    private Set<String> candidateKeys(String actionKey) {
        int separator = actionKey.indexOf('.');
        Set<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(actionKey);
        if (separator > 0) {
            candidates.add(actionKey.substring(0, separator + 1) + "*");
        }
        candidates.add("*");
        return candidates;
    }

    private boolean matches(Set<Long> enabledGroupIds, Set<String> candidates, int denyFlag) {
        return permissionGroupActionMapper.selectCount(new LambdaQueryWrapper<PermissionGroupAction>()
                .eq(PermissionGroupAction::getTenantId, tenantId())
                .in(PermissionGroupAction::getGroupId, enabledGroupIds)
                .eq(PermissionGroupAction::getDenyFlag, denyFlag)
                .in(PermissionGroupAction::getActionKey, candidates)) > 0;
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }
}
