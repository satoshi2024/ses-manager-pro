package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.PermissionGroup;
import com.ses.entity.PermissionGroupAction;
import com.ses.entity.SysUser;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.service.NotificationService;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.PermissionGroupManagementService;
import com.ses.service.security.PersistentSessionService;
import com.ses.service.security.epoch.ActorEpochAnchors;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 権限変更・監査・session失効を同一transactionで確定する。
 *
 * <p>NF01 F1（計画 §4.1）: 割当の置換は対象利用者行（actor epochのanchor、lock rank 3）をX lockしてから旧割当を読み、
 * 割当group集合が変わった時だけactor epochを同じtransactionで1進める。tenantはlockした行の値を使う。
 * {@code SysUserServiceImpl} の作成・role変更から入れ子で呼ばれても、同じ利用者の増分は1回になる（E-4）。</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionGroupManagementServiceImpl implements PermissionGroupManagementService {

    private static final String ADMIN_ROLE = "管理者";
    /** NF07 W-5 の管理者専用 group（V191）。privacy.* の唯一の許可根拠。 */
    static final String PRIVACY_ADMIN_GROUP_KEY = "privacy-admin";
    private static final String PRIVACY_WILDCARD = "privacy.*";
    static final String PRIVACY_ADMIN_AUDIT_CODE = "PRIVACY_ADMIN_GROUP_CHANGED";
    static final String PRIVACY_ADMIN_NOTIFICATION_TYPE = "PRIVACY_ADMIN_CHANGED";
    /** t_audit_log.before_state / after_state の列長（V136）。 */
    private static final int AUDIT_STATE_MAX_LENGTH = 255;

    private final PermissionGroupMapper permissionGroupMapper;
    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogMapper auditLogMapper;
    private final PersistentSessionService persistentSessionService;
    private final OidcSecurityProperties oidcProperties;
    private final PermissionGroupActionMapper permissionGroupActionMapper;
    private final NotificationService notificationService;
    private final AuthorizationEpochFence authorizationEpochFence;

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
        Set<String> requestedKeys = groupKeys(groups);
        if (requestedKeys.contains(PRIVACY_ADMIN_GROUP_KEY)) {
            guardPrivacyAdminAssignment(target, groups);
        }
        // privacy-admin の割当・解除（role変更による既定groupへの再割当を含む）は、変更前の所属と
        // 既存の privacy-admin を書込み前に確定してから、同一transactionで監査・通知する（G-ID 署名までの暫定規則）。
        // 旧割当は対象利用者行のX lock保持下で読む（actor epochの増分判定にも使う）。
        Set<Long> beforeGroupIds = currentGroupIds(userId);
        Set<String> beforeKeys = groupKeysOf(beforeGroupIds);
        boolean privacyAdminChanged = beforeKeys.contains(PRIVACY_ADMIN_GROUP_KEY)
                != requestedKeys.contains(PRIVACY_ADMIN_GROUP_KEY);
        List<Long> existingPrivacyAdmins = privacyAdminChanged
                ? userPermissionGroupMapper.selectActiveUserIdsByGroupKey(tenantId(), PRIVACY_ADMIN_GROUP_KEY)
                : List.of();

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
        // 割当group集合が変わった時だけactor epochを1進める（同じ集合の再保存では進めない。E-4）。
        authorizationEpochFence.advanceIfChanged(ActorEpochAnchors.of(target),
                new TreeSet<>(beforeGroupIds), new TreeSet<>(requested));
        persistentSessionService.revokeAllForUser(userId, "PERMISSION_CHANGED");
        insertAudit(authentication.getName(), userId, now);
        if (privacyAdminChanged) {
            boolean granted = requestedKeys.contains(PRIVACY_ADMIN_GROUP_KEY);
            insertPrivacyAdminAudit(authentication.getName(), userId, beforeKeys, requestedKeys, now);
            notifyPrivacyAdmins(existingPrivacyAdmins, userId, granted, now);
        }
    }

    /**
     * privacy-admin の割当 guard（production-bridge-phased-plan.md §6.7）。
     * <ul>
     *   <li>対象ユーザーの role が管理者でなければ400。privacy-admin は '*' を持つので、誤割当を data の deny だけに任せない。</li>
     *   <li>privacy.* の拒否行を持つ他の group との同時割当は409（deny 優先で privacy が使えなくなるため）。
     *       判定は group key の固定値ではなく拒否行の有無で行う。</li>
     * </ul>
     */
    private void guardPrivacyAdminAssignment(SysUser target, List<PermissionGroup> groups) {
        if (!ADMIN_ROLE.equals(target.getRole())) {
            throw BusinessException.of(400, "error.permission.adminOnlyGroup");
        }
        // break-glass username は incident 外の OIDC session が失効させられ、break-glass session では privacy が
        // DENY_SCOPE になる。privacy 管理者として使えない口座を割り当てさせない（W3-14 review P3-11）。
        if (isBreakGlassUsername(target.getUsername())) {
            throw BusinessException.of(400, "error.permission.breakGlassPrivacyAdmin");
        }
        Set<Long> otherGroupIds = new LinkedHashSet<>();
        for (PermissionGroup group : groups) {
            if (group.getId() != null && !PRIVACY_ADMIN_GROUP_KEY.equals(group.getGroupKey())) {
                otherGroupIds.add(group.getId());
            }
        }
        if (otherGroupIds.isEmpty()) {
            return;
        }
        Long denying = permissionGroupActionMapper.selectCount(new LambdaQueryWrapper<PermissionGroupAction>()
                .eq(PermissionGroupAction::getTenantId, tenantId())
                .in(PermissionGroupAction::getGroupId, otherGroupIds)
                .eq(PermissionGroupAction::getDenyFlag, 1)
                .in(PermissionGroupAction::getActionKey, List.of(PRIVACY_WILDCARD, "*")));
        if (denying == null || denying > 0) {
            throw BusinessException.of(409, "error.permission.privacyGroupConflict");
        }
    }

    /** 対象ユーザーの現在の割当group ID（有効・無効を問わない）。 */
    private Set<Long> currentGroupIds(Long userId) {
        Set<Long> groupIds = new LinkedHashSet<>();
        for (UserPermissionGroup assignment : userPermissionGroupMapper.selectList(
                new LambdaQueryWrapper<UserPermissionGroup>()
                        .eq(UserPermissionGroup::getTenantId, tenantId())
                        .eq(UserPermissionGroup::getUserId, userId))) {
            if (assignment.getGroupId() != null) {
                groupIds.add(assignment.getGroupId());
            }
        }
        return groupIds;
    }

    /** 割当group IDの group key（有効・無効を問わない）。 */
    private Set<String> groupKeysOf(Set<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return new java.util.TreeSet<>();
        }
        return groupKeys(permissionGroupMapper.selectList(new LambdaQueryWrapper<PermissionGroup>()
                .eq(PermissionGroup::getTenantId, tenantId())
                .in(PermissionGroup::getId, groupIds)));
    }

    private static Set<String> groupKeys(List<PermissionGroup> groups) {
        Set<String> keys = new java.util.TreeSet<>();
        if (groups != null) {
            for (PermissionGroup group : groups) {
                if (group != null && StringUtils.hasText(group.getGroupKey())) {
                    keys.add(group.getGroupKey());
                }
            }
        }
        return keys;
    }

    /**
     * privacy-admin の割当・解除の required 監査。変更前後の group key を残す（既存の監査は group key を持たない）。
     * 書けなければ例外で割当ごと rollback する。本文に data subject の識別子は入れない。
     */
    private void insertPrivacyAdminAudit(String username, Long userId, Set<String> beforeKeys,
                                         Set<String> afterKeys, LocalDateTime now) {
        AuditLog entry = new AuditLog();
        entry.setUsername(username);
        entry.setMethod("SECURITY");
        entry.setUri("/api/permission-groups/users/" + userId);
        entry.setStatus(200);
        entry.setApplicationCode(PRIVACY_ADMIN_AUDIT_CODE);
        entry.setSuccessFlag(true);
        entry.setReferenceType("SYS_USER");
        entry.setReferenceId(userId);
        entry.setBeforeState(auditState(beforeKeys));
        entry.setAfterState(auditState(afterKeys));
        entry.setCreatedAt(now);
        if (auditLogMapper.insert(entry) != 1) {
            throw BusinessException.of("error.permission.auditFailed");
        }
    }

    private static String auditState(Set<String> keys) {
        String joined = "groups=" + String.join(",", new java.util.TreeSet<>(keys));
        return joined.length() <= AUDIT_STATE_MAX_LENGTH ? joined : joined.substring(0, AUDIT_STATE_MAX_LENGTH);
    }

    /**
     * 変更前から privacy-admin に所属していた有効なユーザーへ通知する（本文に対象者・subject の識別子は入れない）。
     * 対象者本人以外に既存の privacy-admin がいない場合（最初の割当・最後の解除）は、対象者以外の有効な管理者全員へ
     * 通知する（W3-14 review P3-4。通知先が無いまま監査だけが残る状態を作らない）。
     */
    private void notifyPrivacyAdmins(List<Long> existingPrivacyAdmins, Long targetUserId, boolean granted,
                                     LocalDateTime now) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (existingPrivacyAdmins != null) {
            existingPrivacyAdmins.stream().filter(java.util.Objects::nonNull).forEach(recipients::add);
        }
        boolean othersExist = recipients.stream().anyMatch(recipient -> !recipient.equals(targetUserId));
        if (!othersExist) {
            List<SysUser> administrators = sysUserMapper.selectActiveByRoleAndTenant(ADMIN_ROLE, tenantId());
            if (administrators != null) {
                administrators.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(SysUser::getId)
                        .filter(id -> id != null && !id.equals(targetUserId))
                        .forEach(recipients::add);
            }
        }
        if (recipients.isEmpty()) {
            return;
        }
        String title = granted ? "プライバシー管理者の割当が追加されました" : "プライバシー管理者の割当が解除されました";
        String message = "監査ログ（" + PRIVACY_ADMIN_AUDIT_CODE + "）で変更内容を確認してください";
        String change = granted ? "grant" : "revoke";
        for (Long recipient : recipients) {
            notificationService.publishToUser(recipient, PRIVACY_ADMIN_NOTIFICATION_TYPE, title, message,
                    "/audit-log", "privacy-admin-changed:" + targetUserId + ":" + change + ":" + now + ":" + recipient,
                    "audit-log");
        }
    }

    private Long defaultGroupId(String role) {
        if (!StringUtils.hasText(role)) {
            throw BusinessException.of(400, "error.permission.invalidGroup");
        }
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

    /**
     * break-glass username か。DB の照合順序（大文字小文字を区別しない）で break-glass 行へ解決される表記揺れも
     * 同じ口座とみなす（{@code SecurityConfig} の HTTP Basic 防護と同じ比較。W3-14b review P3-2）。
     */
    private boolean isBreakGlassUsername(String username) {
        if (!StringUtils.hasText(username) || oidcProperties.getBreakGlassUsernames() == null) {
            return false;
        }
        String normalized = username.trim();
        return oidcProperties.getBreakGlassUsernames().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(normalized::equalsIgnoreCase);
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }
}

