package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.LoginUser;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 現在のactive adminとtenant-bound明示permissionを操作ごとに再確認する。 */
@Service
public final class ImportMetadataAuthorizationService {
    public static final String ACTION = "import.metadata.manage";
    private static final Set<String> DENY_MATCHES = Set.of("*", "import.*", ACTION);
    private final JdbcTemplate jdbc;
    private final LegalEntityContextService legalEntityContextService;

    public ImportMetadataAuthorizationService(JdbcTemplate jdbc, LegalEntityContextService legalEntityContextService) {
        this.jdbc = jdbc;
        this.legalEntityContextService = legalEntityContextService;
    }

    public ImportAuthorizedContext requireCurrentAuthorization() {
        CurrentIdentity identity = requireCurrentIdentity();
        Long actorId = identity.actorId();
        String tenantId = identity.tenantId();

        List<String> roles = jdbc.query("""
                SELECT role
                FROM sys_user
                WHERE id = ? AND tenant_id = ? AND status = 1 AND deleted_flag = 0
                """, (rs, row) -> rs.getString(1), actorId, tenantId);
        if (roles.size() != 1 || !"管理者".equals(roles.get(0))) {
            throw BusinessException.of(403, "error.forbidden");
        }

        List<Grant> grants = jdbc.query("""
                SELECT a.action_key, a.deny_flag
                FROM t_user_permission_group upg
                JOIN m_permission_group pg
                  ON pg.id = upg.group_id AND pg.tenant_id = upg.tenant_id
                JOIN t_permission_group_action a
                  ON a.group_id = pg.id AND a.tenant_id = pg.tenant_id
                WHERE upg.tenant_id = ? AND upg.user_id = ? AND upg.deleted_flag = 0
                  AND pg.enabled = 1 AND pg.deleted_flag = 0 AND a.deleted_flag = 0
                """, (rs, row) -> new Grant(rs.getString(1), rs.getInt(2) == 1), tenantId, actorId);
        requireGrant(grants);

        long legalEntityId = requireLegalEntityId();
        return new ImportAuthorizedContext(tenantId, actorId, legalEntityId);
    }

    /** metadata writeの線形化gate。全認可行をロックし、トランザクションcommitまで保持する。 */
    public ImportAuthorizedContext requireCurrentAuthorizationForWrite(ImportAuthorizedContext expected) {
        CurrentIdentity identity = requireCurrentIdentity();
        if (expected == null || identity.actorId() != expected.actorUserId()
                || !identity.tenantId().equals(expected.tenantId())) {
            throw BusinessException.of(403, "error.forbidden");
        }

        List<ActorState> actors = jdbc.query("""
                SELECT role, status, deleted_flag FROM sys_user
                WHERE id=? AND tenant_id=? FOR UPDATE
                """, (rs, row) -> new ActorState(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                identity.actorId(), identity.tenantId());
        if (actors.size() != 1 || actors.get(0).status() != 1 || actors.get(0).deletedFlag() != 0
                || !"管理者".equals(actors.get(0).role())) {
            throw BusinessException.of(403, "error.forbidden");
        }

        List<Membership> memberships = jdbc.query("""
                SELECT tenant_id, id, group_id, deleted_flag FROM t_user_permission_group
                WHERE tenant_id=? AND user_id=? ORDER BY id FOR UPDATE
                """, (rs, row) -> new Membership(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)),
                identity.tenantId(), identity.actorId());
        List<PermissionGroup> groups = jdbc.query("""
                SELECT tenant_id, id, enabled, deleted_flag FROM m_permission_group
                WHERE tenant_id=? ORDER BY id FOR UPDATE
                """, (rs, row) -> new PermissionGroup(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)),
                identity.tenantId());
        List<GrantRow> actions = jdbc.query("""
                SELECT tenant_id, id, group_id, action_key, deny_flag, deleted_flag FROM t_permission_group_action
                WHERE tenant_id=? ORDER BY id FOR UPDATE
                """, (rs, row) -> new GrantRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getInt(5) == 1, rs.getInt(6)), identity.tenantId());

        java.util.Set<Long> activeGroupIds = groups.stream()
                .filter(group -> identity.tenantId().equals(group.tenantId())
                        && group.enabled() == 1 && group.deletedFlag() == 0)
                .map(PermissionGroup::id).collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> assignedGroupIds = memberships.stream()
                .filter(membership -> identity.tenantId().equals(membership.tenantId())
                        && membership.deletedFlag() == 0 && activeGroupIds.contains(membership.groupId()))
                .map(Membership::groupId).collect(java.util.stream.Collectors.toSet());
        List<Grant> effective = actions.stream()
                .filter(action -> identity.tenantId().equals(action.tenantId())
                        && action.deletedFlag() == 0 && assignedGroupIds.contains(action.groupId()))
                .map(action -> new Grant(action.actionKey(), action.denied())).toList();
        requireGrant(effective);

        long legalEntityId = requireLegalEntityId();
        if (legalEntityId != expected.legalEntityId()) {
            throw BusinessException.of(403, "error.forbidden");
        }
        return new ImportAuthorizedContext(identity.tenantId(), identity.actorId(), legalEntityId);
    }

    private CurrentIdentity requireCurrentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (authentication == null || !authentication.isAuthenticated() || !(principal instanceof LoginUser)) {
            throw BusinessException.of(403, "error.forbidden");
        }

        Long actorId = SecurityUtils.currentUserId();
        String tenantId;
        try {
            tenantId = legalEntityContextService.requireTenantId();
        } catch (RuntimeException failure) {
            throw BusinessException.of(403, "error.forbidden");
        }
        if (actorId == null || tenantId == null || tenantId.isBlank()
                || !"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.forbidden");
        }

        return new CurrentIdentity(actorId, tenantId);
    }

    private long requireLegalEntityId() {
        try {
            return legalEntityContextService.requireCurrentLegalEntityId();
        } catch (RuntimeException failure) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
    }

    private void requireGrant(List<Grant> grants) {
        boolean explicitAllow = grants.stream().anyMatch(grant -> !grant.denied() && ACTION.equals(grant.actionKey()));
        boolean explicitDeny = grants.stream().anyMatch(grant -> grant.denied() && DENY_MATCHES.contains(grant.actionKey()));
        if (!explicitAllow || explicitDeny) throw BusinessException.of(403, "error.forbidden");
    }

    boolean hasCurrentImportPermission(String tenantId, Long actorId) {
        try {
            ImportAuthorizedContext context = requireCurrentAuthorization();
            return context.actorUserId() == actorId && context.tenantId().equals(tenantId);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private record Grant(String actionKey, boolean denied) { }
    private record CurrentIdentity(long actorId, String tenantId) { }
    private record ActorState(String role, int status, int deletedFlag) { }
    private record Membership(String tenantId, long id, long groupId, int deletedFlag) { }
    private record PermissionGroup(String tenantId, long id, int enabled, int deletedFlag) { }
    private record GrantRow(String tenantId, long id, long groupId, String actionKey, boolean denied, int deletedFlag) { }
}
