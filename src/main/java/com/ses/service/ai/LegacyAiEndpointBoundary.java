package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import org.springframework.stereotype.Component;

/**
 * 既存 /api/ai/match と /api/ai/chat はNF08 management-copilot入口とは別のlegacy local-only入口。
 * ただし本番provider・組織・営業・法人scopeを迂回することは許さない。
 */
@Component
public class LegacyAiEndpointBoundary {
    private final EngineerService engineerService;
    private final ProjectService projectService;
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;
    private final CopilotExecutionContextFactory contextFactory;
    private final LegacyAiExecutionContextBinder contextBinder;

    public LegacyAiEndpointBoundary(EngineerService engineerService, ProjectService projectService,
                                    DataScopeService dataScopeService,
                                    OrganizationScopeService organizationScopeService,
                                    CopilotExecutionContextFactory contextFactory,
                                    LegacyAiExecutionContextBinder contextBinder) {
        this.engineerService = engineerService;
        this.projectService = projectService;
        this.dataScopeService = dataScopeService;
        this.organizationScopeService = organizationScopeService;
        this.contextFactory = contextFactory;
        this.contextBinder = contextBinder;
    }

    public Long requireLegalEntityId() {
        return createContext().legalEntityId();
    }

    public Engineer assertEngineer(Long id) {
        return assertEngineer(id, createContext());
    }

    /** 同一リクエストの認可判定で共有するExecutionContextを明示的に受け取る。 */
    public Engineer assertEngineer(Long id, CopilotExecutionContext context) {
        if (id == null) throw denied();
        EffectiveScopeSnapshot snapshot = requireSnapshot(context);
        if (!snapshot.allowsEngineer(id)) throw denied();
        Engineer engineer = engineerService.getById(id);
        if (engineer == null || engineer.getLegalEntityId() == null
                || !snapshot.legalEntityId().equals(engineer.getLegalEntityId())) throw denied();
        return engineer;
    }

    public Project assertProject(Long id) {
        return assertProject(id, createContext());
    }

    /** 同一リクエストの認可判定で共有するExecutionContextを明示的に受け取る。 */
    public Project assertProject(Long id, CopilotExecutionContext context) {
        if (id == null) throw denied();
        EffectiveScopeSnapshot snapshot = requireSnapshot(context);
        if (!snapshot.allowsProject(id)) throw denied();
        Project project = projectService.getById(id);
        if (project == null || project.getLegalEntityId() == null
                || !snapshot.legalEntityId().equals(project.getLegalEntityId())) throw denied();
        return project;
    }

    /** proposal-draftのような複合入力は、両方を同じ法人・組織scopeで再認可する。 */
    public void assertSameLegalEntity(Long engineerId, Long projectId) {
        assertSameLegalEntity(engineerId, projectId, createContext());
    }

    public void assertSameLegalEntity(Long engineerId, Long projectId, CopilotExecutionContext context) {
        requireSnapshot(context);
        Engineer engineer = assertEngineer(engineerId, context);
        Project project = assertProject(projectId, context);
        if (!engineer.getLegalEntityId().equals(project.getLegalEntityId())) {
            throw denied();
        }
    }

    public CopilotExecutionContext createContext() {
        if (contextBinder == null) throw denied();
        return contextBinder.bind(contextFactory.create());
    }

    private EffectiveScopeSnapshot requireSnapshot(CopilotExecutionContext context) {
        if (context == null || context.effectiveScopeSnapshot() == null
                || context.scope() != context.effectiveScopeSnapshot().scope()) {
            throw denied();
        }
        return context.effectiveScopeSnapshot();
    }

    private BusinessException denied() {
        return BusinessException.of(404, "error.scope.notFound");
    }
}
