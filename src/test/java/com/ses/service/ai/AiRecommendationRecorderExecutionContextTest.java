package com.ses.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.ai.impl.AiRecommendationRecorderImpl;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** NF08: recommendation run/itemはquery開始時のExecutionContextとscope snapshotに束縛する。 */
@ExtendWith(MockitoExtension.class)
class AiRecommendationRecorderExecutionContextTest {

    @Mock AiArtifactVersionMapper versionMapper;
    @Mock AiRecommendationRunMapper runMapper;
    @Mock AiRecommendationItemMapper itemMapper;
    @Mock BpAvailabilityMapper bpAvailabilityMapper;
    @Mock EngineerMapper engineerMapper;
    @Mock ProjectMapper projectMapper;
    @Mock EngineerSkillMapper engineerSkillMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextなしの旧overloadはfailClosedしrunもitemも書かない() {
        AiRecommendationRecorder recorder = recorder();

        BusinessException denied = assertThrows(BusinessException.class,
                () -> recorder.recordMatch("MATCHING", 1L, List.of(projectResult(20L)), 10L, 20L));

        assertEquals(403, denied.getCode());
        assertEquals("EXECUTION_CONTEXT_REQUIRED", denied.getMessage());
        verifyNoInteractions(versionMapper, runMapper, itemMapper, engineerMapper, projectMapper,
                bpAvailabilityMapper);
    }

    @Test
    void snapshotなしのcontextはrunとitemを書かない() {
        authenticateAs(1L);
        AiRecommendationRecorder recorder = recorder();
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-e1", 77L, Instant.parse("2026-09-09T00:00:00Z"), ZoneId.of("UTC"));

        BusinessException denied = assertThrows(BusinessException.class,
                () -> recorder.recordMatch("MATCHING", null, List.of(projectResult(20L)),
                        10L, 20L, context));

        assertEquals("EXECUTION_CONTEXT_REQUIRED", denied.getMessage());
        verifyNoInteractions(versionMapper, runMapper, itemMapper, engineerMapper, projectMapper,
                bpAvailabilityMapper);
    }

    @Test
    void queryId欠落のcontextはrunとitemを書かない() {
        authenticateAs(1L);
        AiRecommendationRecorder recorder = recorder();
        CopilotExecutionContext context = snapshotOnlyContext();

        BusinessException denied = assertThrows(BusinessException.class,
                () -> recorder.recordMatch("MATCHING", null, List.of(projectResult(20L)),
                        10L, 20L, context));

        assertEquals("EXECUTION_CONTEXT_REQUIRED", denied.getMessage());
        verifyNoInteractions(versionMapper, runMapper, itemMapper, engineerMapper, projectMapper,
                bpAvailabilityMapper);
    }

    @Test
    void parametersのqueryId不一致はrunとitemを書かない() {
        authenticateAs(1L);
        AiRecommendationRecorder recorder = recorder();
        CopilotExecutionContext valid = fullContext();
        EffectiveScopeSnapshot snapshot = valid.effectiveScopeSnapshot();
        CopilotExecutionContext mismatched = org.mockito.Mockito.mock(CopilotExecutionContext.class);
        when(mismatched.effectiveScopeSnapshot()).thenReturn(snapshot);
        when(mismatched.scope()).thenReturn(snapshot.scope());
        when(mismatched.tenantId()).thenReturn(snapshot.tenantId());
        when(mismatched.legalEntityId()).thenReturn(snapshot.legalEntityId());
        when(mismatched.asOf()).thenReturn(valid.asOf());
        when(mismatched.asOfDate()).thenReturn(snapshot.asOf());
        when(mismatched.scopeHash()).thenReturn(snapshot.scopeHash());
        when(mismatched.queryId()).thenReturn("legacy.ai");
        when(mismatched.parameters()).thenReturn(CopilotQueryParameters.ofQuery("other.query"));

        BusinessException denied = assertThrows(BusinessException.class,
                () -> recorder.recordMatch("MATCHING", null, List.of(projectResult(20L)),
                        10L, 20L, mismatched));

        assertEquals("EXECUTION_CONTEXT_REQUIRED", denied.getMessage());
        verifyNoInteractions(versionMapper, runMapper, itemMapper, engineerMapper, projectMapper,
                bpAvailabilityMapper);
    }

    @Test
    void snapshot外のresultはcrossScopeとして拒否しrunとitemを書かない() {
        authenticateAs(1L);
        AiRecommendationRecorder recorder = recorder();
        CopilotExecutionContext context = scopedContext(Set.of(10L), Set.of(20L));
        when(engineerMapper.selectById(10L)).thenReturn(engineer(10L, 77L));
        Project source = project(20L, 77L);
        Project outside = project(21L, 77L);
        when(projectMapper.selectById(20L)).thenReturn(source);
        when(projectMapper.selectById(21L)).thenReturn(outside);

        assertThrows(BusinessException.class,
                () -> recorder.recordMatch("MATCHING", null, List.of(projectResult(21L)),
                        10L, 20L, context));

        verify(runMapper, never()).insert((AiRecommendationRun) any());
        verify(itemMapper, never()).insert((AiRecommendationItem) any());
    }

    @Test
    void sourceの法人不一致は拒否しrunとitemを書かない() {
        authenticateAs(1L);
        AiRecommendationRecorder recorder = recorder();
        CopilotExecutionContext context = fullContext();
        Engineer foreign = engineer(10L, 88L);
        when(engineerMapper.selectById(10L)).thenReturn(foreign);

        assertThrows(BusinessException.class,
                () -> recorder.recordMatch("MATCHING", null, List.of(projectResult(20L)),
                        10L, null, context));

        verify(runMapper, never()).insert((AiRecommendationRun) any());
        verify(itemMapper, never()).insert((AiRecommendationItem) any());
    }

    @Test
    void 成功runはscopeとparameterとtenant法人asOfcatalogDataを保存する() {
        authenticateAs(1L);
        AiRecommendationRecorder recorder = recorder();
        CopilotExecutionContext context = fullContext();
        assertEquals("legacy.ai", context.queryId());
        assertEquals("legacy.ai", context.parameters().queryId());
        Engineer source = engineer(10L, 77L);
        Project sourceProject = project(20L, 77L);
        AiArtifactVersion active = new AiArtifactVersion();
        active.setId(100L);
        active.setUseCase("MATCHING");
        active.setStatus("ACTIVE");
        active.setRuleVersion("rule-2026.09");
        when(engineerMapper.selectById(10L)).thenReturn(source);
        when(projectMapper.selectById(20L)).thenReturn(sourceProject);
        when(engineerSkillMapper.selectDetailByEngineerId(10L)).thenReturn(List.of());
        when(versionMapper.selectOne(any())).thenReturn(active);
        doAnswer(invocation -> {
            AiRecommendationRun run = invocation.getArgument(0);
            run.setId(500L);
            return 1;
        }).when(runMapper).insert(any(AiRecommendationRun.class));
        doAnswer(invocation -> {
            AiRecommendationItem item = invocation.getArgument(0);
            item.setId(600L);
            return 1;
        }).when(itemMapper).insert(any(AiRecommendationItem.class));

        MatchResultDto result = projectResult(20L);
        String traceId = recorder.recordMatch("MATCHING", null, List.of(result),
                10L, 20L, context);

        assertNotNull(traceId);
        assertEquals(500L, result.getRunId());
        assertEquals(600L, result.getItemId());
        ArgumentCaptor<AiRecommendationRun> captor = ArgumentCaptor.forClass(AiRecommendationRun.class);
        verify(runMapper).insert(captor.capture());
        AiRecommendationRun run = captor.getValue();
        assertEquals(context.scopeHash(), run.getScopeHash());
        assertNotNull(run.getParameterHash());
        assertEquals("tenant-e1", run.getTenantId());
        assertEquals(77L, run.getLegalEntityId());
        assertEquals(LocalDateTime.ofInstant(context.asOf(), ZoneOffset.UTC), run.getAsOfAt());
        assertEquals("UTC", run.getTimezoneId());
        assertEquals("legacy-matching-v1", run.getCatalogVersion());
        assertEquals("resource-scope-v1", run.getDataVersion());
    }

    private AiRecommendationRecorder recorder() {
        return new AiRecommendationRecorderImpl(versionMapper, runMapper, itemMapper,
                bpAvailabilityMapper, engineerMapper, projectMapper, engineerSkillMapper, objectMapper);
    }

    private CopilotExecutionContext fullContext() {
        DataScopeService dataScope = org.mockito.Mockito.mock(DataScopeService.class);
        OrganizationScopeService organizationScope = org.mockito.Mockito.mock(OrganizationScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        return bind(new EffectiveScopeSnapshotFactory(dataScope, organizationScope).create(
                "tenant-e1", 77L, LocalDate.of(2026, 9, 9)));
    }

    private CopilotExecutionContext snapshotOnlyContext() {
        DataScopeService dataScope = org.mockito.Mockito.mock(DataScopeService.class);
        OrganizationScopeService organizationScope = org.mockito.Mockito.mock(OrganizationScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        return bindSnapshotOnly(new EffectiveScopeSnapshotFactory(dataScope, organizationScope).create(
                "tenant-e1", 77L, LocalDate.of(2026, 9, 9)));
    }

    private CopilotExecutionContext scopedContext(Set<Long> engineers, Set<Long> projects) {
        DataScopeService dataScope = org.mockito.Mockito.mock(DataScopeService.class);
        OrganizationScopeService organizationScope = org.mockito.Mockito.mock(OrganizationScopeService.class);
        LocalDate asOf = LocalDate.of(2026, 9, 9);
        when(dataScope.isScoped()).thenReturn(true);
        when(dataScope.allowedEngineerIds(asOf)).thenReturn(engineers);
        when(dataScope.allowedProjectIds(asOf)).thenReturn(projects);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        return bind(new EffectiveScopeSnapshotFactory(dataScope, organizationScope)
                .create("tenant-e1", 77L, asOf));
    }

    private CopilotExecutionContext bind(com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot snapshot) {
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-e1", 77L,
                snapshot.asOf().atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        context.bindSnapshot(snapshot);
        return new LegacyAiExecutionContextBinder().bind(context);
    }

    private CopilotExecutionContext bindSnapshotOnly(
            com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot snapshot) {
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-e1", 77L,
                snapshot.asOf().atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        context.bindSnapshot(snapshot);
        return context;
    }

    private static MatchResultDto projectResult(Long projectId) {
        MatchResultDto result = new MatchResultDto();
        result.setProjectId(projectId);
        result.setScore(80);
        result.setReason("ok");
        return result;
    }

    private static Engineer engineer(Long id, Long legalEntityId) {
        Engineer engineer = new Engineer();
        engineer.setId(id);
        engineer.setLegalEntityId(legalEntityId);
        engineer.setFullName("E-" + id);
        return engineer;
    }

    private static Project project(Long id, Long legalEntityId) {
        Project project = new Project();
        project.setId(id);
        project.setLegalEntityId(legalEntityId);
        project.setProjectName("P-" + id);
        return project;
    }

    private static void authenticateAs(Long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "x", List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        authentication.setDetails(Map.of("tenant_id", "tenant-e1"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
