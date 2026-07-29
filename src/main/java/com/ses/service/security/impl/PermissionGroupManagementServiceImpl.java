package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.PermissionGroup;
import com.ses.entity.SysUser;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.PermissionGroupManagementService;
import com.ses.service.security.PersistentSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 権限変更・監査・session失効を同一transactionで確定する。 */
@Service
@RequiredArgsConstructor
public class PermissionGroupManagementServiceImpl implements PermissionGroupManagementService {

    private final PermissionGroupMapper permissionGroupMapper;
    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogMapper auditLogMapper;
    private final PersistentSessionService persistentSessionService;
    private final OidcSecurityProperties oidcProperties;

    @Override
    public List<PermissionGroup> listGroups() {
        return permissionGroupMapper.selectList(new LambdaQueryWrapper<PermissionGroup>()
                .eq(PermissionGroup::getTenantId, tenantId())
                .eq(PermissionGroup::getEnabled, 1)
                .orderByAsc(PermissionGroup::getId));
    }

    @Override
    public Set<Long> assignedGroupIds(Long userId) {
        Set<Long> result = new LinkedHashSet<>();
        userPermissionGroupMapper.selectList(new LambdaQueryWrapper<UserPermissionGroup>()
                        .eq(UserPermissionGroup::getTenantId, tenantId())
                        .eq(UserPermissionGroup::getUserId, userId)
                        .orderByAsc(UserPermissionGroup::getId))
                .stream().map(UserPermissionGroup::getGroupId).forEach(result::add);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAssignments(Long userId, Set<Long> groupIds, Authentication authentication) {
        Long actorId = SecurityUtils.currentUserId();
        if (actorId == null || authentication == null || !authentication.isAuthenticated()) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        if (actorId.equals(userId)) {
            throw BusinessException.of(403, "error.permission.selfChange");
        }

        // target user行をmutexにし、並行するrole/group/session変更を直列化する。
        SysUser target = sysUserMapper.selectByIdForUpdate(userId);
        if (target == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        Set<Long> requested = groupIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(groupIds);
        if (requested.isEmpty()) {
            requested.add(defaultGroupId(target.getRole()));
        }
        List<PermissionGroup> groups = permissionGroupMapper.selectList(new LambdaQueryWrapper<PermissionGroup>()
                .eq(PermissionGroup::getTenantId, tenantId())
                .eq(PermissionGroup::getEnabled, 1)
                .in(PermissionGroup::getId, requested));
        if (groups.size() != requested.size()) {
            throw BusinessException.of(400, "error.permission.invalidGroup");
        }

        userPermissionGroupMapper.deleteAllForUser(tenantId(), userId);
        LocalDateTime now = LocalDateTime.now();
        for (Long groupId : requested) {
            UserPermissionGroup assignment = new UserPermissionGroup();
            assignment.setTenantId(tenantId());
            assignment.setUserId(userId);
            assignment.setGroupId(groupId);
            assignment.setAssignedBy(actorId);
            assignment.setAssignedAt(now);
            if (userPermissionGroupMapper.insert(assignment) != 1) {
                throw BusinessException.of("error.permission.saveFailed");
            }
        }
        persistentSessionService.revokeAllForUser(userId, "PERMISSION_CHANGED");
        insertAudit(authentication.getName(), userId, now);
    }

    private Long defaultGroupId(String role) {
        String groupKey = switch (role) {
            case "管理者" -> "role-admin";
            case "営業" -> "role-sales";
            case "HR" -> "role-hr";
            case "マネージャー" -> "role-manager";
            case "要員" -> "role-member";
            default -> null;
        };
        if (groupKey == null) {
            throw BusinessException.of(400, "error.permission.invalidGroup");
        }
        PermissionGroup group = permissionGroupMapper.selectOne(new LambdaQueryWrapper<PermissionGroup>()
                .eq(PermissionGroup::getTenantId, tenantId())
                .eq(PermissionGroup::getGroupKey, groupKey)
                .eq(PermissionGroup::getEnabled, 1));
        if (group == null) {
            throw BusinessException.of(409, "error.permission.defaultGroupMissing");
        }
        return group.getId();
    }

    private void insertAudit(String username, Long userId, LocalDateTime now) {
        AuditLog entry = new AuditLog();
        entry.setUsername(username);
        entry.setMethod("SECURITY");
        entry.setUri("/api/permission-groups/users/" + userId);
        entry.setStatus(200);
        entry.setApplicationCode("PERMISSION_GROUP_CHANGED");
        entry.setSuccessFlag(true);
        entry.setCreatedAt(now);
        if (auditLogMapper.insert(entry) != 1) {
            throw BusinessException.of("error.permission.auditFailed");
        }
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }
}

