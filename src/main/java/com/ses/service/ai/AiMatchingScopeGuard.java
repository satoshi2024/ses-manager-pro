package com.ses.service.ai;

import com.ses.entity.BpAvailability;
import com.ses.entity.Project;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * legacy matching providersが共有する法人・DataScope・OrganizationScope境界。
 * providerごとに認可条件を複製するとRule/Geminiの結果がずれるため、ここを唯一の判定にする。
 */
@Component
public class AiMatchingScopeGuard {
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;
    private final CopilotExecutionContextFactory contextFactory;
    private final LegacyAiExecutionContextBinder contextBinder;

    public AiMatchingScopeGuard(DataScopeService dataScopeService,
                                OrganizationScopeService organizationScopeService,
                                CopilotExecutionContextFactory contextFactory,
                                LegacyAiExecutionContextBinder contextBinder) {
        this.dataScopeService = dataScopeService;
        this.organizationScopeService = organizationScopeService;
        this.contextFactory = contextFactory;
        this.contextBinder = contextBinder;
    }

    public CopilotExecutionContext createContext() {
        if (contextBinder == null) return null;
        return contextBinder.bind(contextFactory.create());
    }

    public boolean allowsEngineer(Long engineerId, CopilotExecutionContext context) {
        if (engineerId == null || context == null || context.effectiveScopeSnapshot() == null) return false;
        Set<Long> allowed = allowedEngineerIds(context);
        return allowed == null || allowed.contains(engineerId);
    }

    public boolean allowsProject(Long projectId, CopilotExecutionContext context) {
        if (projectId == null || context == null) return false;
        Set<Long> allowed = allowedProjectIds(context);
        return allowed == null || allowed.contains(projectId);
    }

    /** nullは全件許可ではなく、追加ID predicate不要を表す（full access時のみ）。 */
    public Set<Long> allowedEngineerIds(CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = snapshot(context);
        return snapshot == null ? Set.of() : snapshot.engineerIds();
    }

    /** nullは全件許可ではなく、追加ID predicate不要を表す（full access時のみ）。 */
    public Set<Long> allowedProjectIds(CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = snapshot(context);
        return snapshot == null ? Set.of() : snapshot.projectIds();
    }

    /** BP在現行モデルに組織列を持たないため、非全社組織scopeでは推測せず公開しない。 */
    public boolean allowsBp(BpAvailability bp, Project project, CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = snapshot(context);
        if (bp == null || project == null || context == null
                || bp.getLegalEntityId() == null || project.getLegalEntityId() == null
                || snapshot == null || !snapshot.legalEntityId().equals(project.getLegalEntityId())
                || !snapshot.legalEntityId().equals(bp.getLegalEntityId())) {
            return false;
        }
        return allowsProject(project.getId(), context) && snapshot.organizationFullAccess();
    }

    public boolean sameLegalEntity(Long rowLegalEntityId, CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = snapshot(context);
        return rowLegalEntityId != null && snapshot != null
                && snapshot.legalEntityId().equals(rowLegalEntityId);
    }

    private EffectiveScopeSnapshot snapshot(CopilotExecutionContext context) {
        if (context == null || context.effectiveScopeSnapshot() == null
                || context.scope() != context.effectiveScopeSnapshot().scope()) {
            return null;
        }
        return context.effectiveScopeSnapshot();
    }
}
