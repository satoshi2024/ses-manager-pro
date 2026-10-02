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
        boolean explicitAllow = grants.stream().anyMatch(grant -> !grant.denied() && ACTION.equals(grant.actionKey()));
        boolean explicitDeny = grants.stream().anyMatch(grant -> grant.denied() && DENY_MATCHES.contains(grant.actionKey()));
        if (!explicitAllow || explicitDeny) {
            throw BusinessException.of(403, "error.forbidden");
        }

        long legalEntityId;
        try {
            legalEntityId = legalEntityContextService.requireCurrentLegalEntityId();
        } catch (RuntimeException failure) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        return new ImportAuthorizedContext(tenantId, actorId, legalEntityId);
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
}
