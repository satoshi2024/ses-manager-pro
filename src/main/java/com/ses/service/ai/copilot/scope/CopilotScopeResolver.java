package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** role / DataScope / 組織scopeをquery開始時のsnapshotへ収束させる。 */
@Component
public class CopilotScopeResolver {
    public static final String POLICY_VERSION = EffectiveScopeSnapshotFactory.POLICY_VERSION;

    /** 既存unit testと互換のconstructor。scopeの再計算には使用しない。 */
    public CopilotScopeResolver(DataScopeService dataScopeService,
                                OrganizationScopeService organizationScopeService) {
    }

    @Autowired
    public CopilotScopeResolver(EffectiveScopeSnapshotFactory snapshotFactory) {
    }

    public CopilotScopeContext resolve(SemanticCatalogEntry entry) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    public CopilotScopeContext resolve(SemanticCatalogEntry entry, CopilotExecutionContext context) {
        assertRoleAndCatalog(entry);
        if (context == null) {
            throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
        }
        EffectiveScopeSnapshot snapshot = context.effectiveScopeSnapshot();
        if (snapshot != null) {
            if (context.scope() != snapshot.scope()) {
                throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
            }
            return snapshot.scope();
        }
        // scopeはquery開始時にfactoryが一度だけ束縛したsnapshotからのみ取得する。
        // ここで再計算すると、同一pipeline内でDataScope/OrganizationScopeが変化した際に
        // parameter・result・citationの認可集合が分裂するため、互換fallbackも許可しない。
        throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
    }

    private void assertRoleAndCatalog(SemanticCatalogEntry entry) {
        String role = SecurityUtils.currentRole();
        if (entry == null || role == null || !entry.allowedRoles().contains(role)
                || "HR".equals(role) || "要員".equals(role)) {
            throw BusinessException.of(403, "SCOPE_DENIED");
        }
    }
}
