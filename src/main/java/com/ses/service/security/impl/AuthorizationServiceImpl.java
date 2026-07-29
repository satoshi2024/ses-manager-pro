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
        if ("管理者".equals(role)) {
            return true;
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
                    int separator = actionKey.indexOf('.');
                    String resourceWildcard = separator > 0 ? actionKey.substring(0, separator + 1) + "*" : actionKey;
                    return permissionGroupActionMapper.selectCount(new LambdaQueryWrapper<PermissionGroupAction>()
                            .eq(PermissionGroupAction::getTenantId, tenantId())
                            .in(PermissionGroupAction::getGroupId, enabledGroupIds)
                            .in(PermissionGroupAction::getActionKey, actionKey, resourceWildcard, "*")) > 0;
                }
            }
            return legacyRoleAllows(role, actionKey);
        } catch (RuntimeException e) {
            log.warn("action権限のDB判定に失敗したため拒否します: action={}", actionKey, e);
            return false;
        }
    }

    private boolean legacyRoleAllows(String role, String actionKey) {
        if (role == null) {
            return false;
        }
        if ("管理者".equals(role)) {
            return true;
        }
        if (actionKey.startsWith("user.") || actionKey.equals("permission.manage")
                || actionKey.equals("payroll.view") || actionKey.equals("file.scan.retry")) {
            return false;
        }
        if ("HR".equals(role)) {
            return actionKey.startsWith("engineer.") || actionKey.startsWith("candidate.")
                    || actionKey.startsWith("file.") || actionKey.equals("export.execute")
                    || actionKey.equals("autocomplete.view");
        }
        if ("営業".equals(role)) {
            return actionKey.startsWith("engineer.") || actionKey.startsWith("customer.")
                    || actionKey.startsWith("project.") || actionKey.startsWith("proposal.")
                    || actionKey.startsWith("contract.") || actionKey.startsWith("candidate.")
                    || actionKey.equals("export.execute") || actionKey.equals("autocomplete.view")
                    || actionKey.equals("file.download")
                    || actionKey.equals("file.upload");
        }
        if ("マネージャー".equals(role)) {
            return !actionKey.startsWith("user.") && !actionKey.equals("permission.manage")
                    && !actionKey.equals("payroll.view");
        }
        if ("要員".equals(role)) {
            return actionKey.equals("file.download");
        }
        return false;
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }
}
