package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProjectSkillMapper;
import com.ses.mapper.SkillTagMapper;
import com.ses.service.ai.AiMatchingScopeGuard;
import com.ses.service.ai.LegacyAiExecutionContextBinder;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** NF08: Rule providerのBP queryがE1/E2/NULLを法人境界で分離する回帰テスト。 */
class RuleMatchingServiceIsolationTest {
    @Test
    void E1プロジェクトのBP結果はE1だけでNULL法人もfailClosedする() {
        EngineerMapper engineerMapper = mock(EngineerMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        EngineerSkillMapper engineerSkillMapper = mock(EngineerSkillMapper.class);
        ProjectSkillMapper projectSkillMapper = mock(ProjectSkillMapper.class);
        SkillTagMapper skillTagMapper = mock(SkillTagMapper.class);
        BpAvailabilityMapper bpMapper = mock(BpAvailabilityMapper.class);
        DataScopeService dataScope = mock(DataScopeService.class);
        OrganizationScopeService organizationScope = mock(OrganizationScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        AiMatchingScopeGuard guard = new AiMatchingScopeGuard(dataScope, organizationScope,
                mock(CopilotExecutionContextFactory.class), mock(LegacyAiExecutionContextBinder.class));

        Project project = new Project();
        project.setId(10L);
        project.setLegalEntityId(1001L);
        project.setStartDate(LocalDate.of(2026, 1, 1));
        project.setUnitPriceMin(java.math.BigDecimal.valueOf(100));
        project.setUnitPriceMax(java.math.BigDecimal.valueOf(200));
        Engineer internal = new Engineer();
        internal.setId(20L);
        internal.setLegalEntityId(1001L);
        internal.setStatus("Bench");
        internal.setAvailableDate(LocalDate.of(2026, 1, 1));
        internal.setExpectedUnitPrice(java.math.BigDecimal.valueOf(150));
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(engineerMapper.selectList(any())).thenReturn(List.of(internal));
        when(projectSkillMapper.selectList(any())).thenReturn(List.of());
        when(engineerSkillMapper.selectDetailByEngineerId(10L)).thenReturn(List.of());
        when(skillTagMapper.selectList(any())).thenReturn(List.of());
        when(bpMapper.selectList(any())).thenReturn(List.of(
                bp(1L, 1001L), bp(2L, 1002L), bp(3L, null)));

        RuleMatchingServiceImpl service = new RuleMatchingServiceImpl(engineerMapper, projectMapper,
                engineerSkillMapper, projectSkillMapper, skillTagMapper, bpMapper,
                new ObjectMapper(), guard);
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-e1", 1001L,
                Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot(
                "tenant-e1", 1001L, LocalDate.of(2026, 1, 1), "COMPANY_WIDE",
                true, false, false, null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "65542320e9b8f9fea5b2a2b67ce3e10b51b9173e2bcdb85a883421e10d5c4ec3");
        context.bindSnapshot(snapshot);
        context.bind("legacy.ai", com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery("legacy.ai"), snapshot.scope());

        List<MatchResultDto> results = service.findMatchingEngineers(10L, context);

        assertEquals(Set.of(1L), results.stream().filter(result -> Boolean.TRUE.equals(result.getIsExternalBp()))
                .map(MatchResultDto::getBpAvailabilityId).collect(java.util.stream.Collectors.toSet()));
    }

    private static BpAvailability bp(Long id, Long legalEntityId) {
        BpAvailability bp = new BpAvailability();
        bp.setId(id);
        bp.setLegalEntityId(legalEntityId);
        bp.setStatus("提案可能");
        bp.setUnitPrice(150L);
        bp.setAvailableFrom(LocalDate.of(2026, 1, 1));
        return bp;
    }
}
