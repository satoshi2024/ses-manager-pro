package com.ses.service.ai;

import com.ses.entity.BpAvailability;
import com.ses.entity.Project;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
        if (engineerId == null || context == null) return false;
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
        if (snapshot != null) {
            return snapshot.engineerIds();
        }
        if (context == null) return Set.of();
        LocalDate asOfDate = context.asOfDate();
        if ((dataScopeService == null || !dataScopeService.isScoped())
                && (organizationScopeService == null || organizationScopeService.hasFullAccess())) {
            return null;
        }
        Set<Long> data = (dataScopeService != null && dataScopeService.isScoped())
                ? dataScopeService.allowedEngineerIds(asOfDate) : null;
        Set<Long> org = (organizationScopeService != null && !organizationScopeService.hasFullAccess())
                ? organizationScopeService.allowedEngineerIds(asOfDate) : null;
        return intersect(data, org);
    }

    /** nullは全件許可ではなく、追加ID predicate不要を表す（full access時のみ）。 */
    public Set<Long> allowedProjectIds(CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = snapshot(context);
        if (snapshot != null) {
            return snapshot.projectIds();
        }
        if (context == null) return Set.of();
        LocalDate asOfDate = context.asOfDate();
        if ((dataScopeService == null || !dataScopeService.isScoped())
                && (organizationScopeService == null || organizationScopeService.hasFullAccess())) {
            return null;
        }
        Set<Long> data = (dataScopeService != null && dataScopeService.isScoped())
                ? dataScopeService.allowedProjectIds(asOfDate) : null;
        Set<Long> org = (organizationScopeService != null && !organizationScopeService.hasFullAccess())
                ? organizationScopeService.allowedProjectIds(asOfDate) : null;
        return intersect(data, org);
    }

    /** BP在現行モデルに組織列を持たないため、非全社組織scopeでは推測せず公開しない。 */
    public boolean allowsBp(BpAvailability bp, Project project, CopilotExecutionContext context) {
        if (bp == null || project == null || context == null
                || bp.getLegalEntityId() == null || project.getLegalEntityId() == null) {
            return false;
        }
        EffectiveScopeSnapshot snapshot = snapshot(context);
        Long legalEntityId = snapshot != null ? snapshot.legalEntityId() : context.legalEntityId();
        if (!legalEntityId.equals(project.getLegalEntityId())
                || !legalEntityId.equals(bp.getLegalEntityId())) {
            return false;
        }
        boolean orgFullAccess = snapshot != null ? snapshot.organizationFullAccess()
                : (organizationScopeService == null || organizationScopeService.hasFullAccess());
        return allowsProject(project.getId(), context) && orgFullAccess;
    }

    public boolean sameLegalEntity(Long rowLegalEntityId, CopilotExecutionContext context) {
        if (rowLegalEntityId == null || context == null) return false;
        EffectiveScopeSnapshot snapshot = snapshot(context);
        Long expected = snapshot != null ? snapshot.legalEntityId() : context.legalEntityId();
        return rowLegalEntityId.equals(expected);
    }

    private static Set<Long> intersect(Set<Long> first, Set<Long> second) {
        if (first == null) return second;
        if (second == null) return first;
        Set<Long> result = new HashSet<>(first);
        result.retainAll(second);
        return result;
    }

    private EffectiveScopeSnapshot snapshot(CopilotExecutionContext context) {
        if (context == null || context.effectiveScopeSnapshot() == null
                || context.scope() != context.effectiveScopeSnapshot().scope()) {
            return null;
        }
        return context.effectiveScopeSnapshot();
    }
}
