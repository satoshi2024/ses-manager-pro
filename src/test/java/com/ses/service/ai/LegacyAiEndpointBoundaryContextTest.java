package com.ses.service.ai;

import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** NF08: legacy入口的認可日付が固定ClockのLocalDateではなくcontext.asOf/zoneに束縛される。 */
@ExtendWith(MockitoExtension.class)
class LegacyAiEndpointBoundaryContextTest {
    @Mock EngineerService engineerService;
    @Mock ProjectService projectService;
    @Mock DataScopeService dataScopeService;
    @Mock OrganizationScopeService organizationScopeService;
    @Mock CopilotExecutionContextFactory contextFactory;
    @Mock LegacyAiExecutionContextBinder contextBinder;

    @Test
    void UTCとLosAngelesの日付境界を同じcanonical判定へ渡す() {
        Engineer engineer = engineer(1L, 77L);
        when(engineerService.getById(1L)).thenReturn(engineer);
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedEngineerIds(LocalDate.of(2026, 3, 1))).thenReturn(Set.of(1L));
        when(dataScopeService.allowedEngineerIds(LocalDate.of(2026, 2, 28))).thenReturn(Set.of());
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(contextBinder.bind(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LegacyAiEndpointBoundary boundary = boundary();

        CopilotExecutionContext utc = context("2026-03-01T00:00:00Z", "UTC");
        when(contextFactory.create()).thenReturn(utc);
        assertEquals(engineer, boundary.assertEngineer(1L));

        CopilotExecutionContext losAngeles = context("2026-03-01T07:59:59Z", "America/Los_Angeles");
        when(contextFactory.create()).thenReturn(losAngeles);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> boundary.assertEngineer(1L),
                "同じInstantでもtenant zoneの業務日付を使い、境界前のscopeを再利用しない");
    }

    @Test
    void legacyとcanonicalは同じcontextで法人とscopeを判定しcontext無しは拒否する() {
        Project project = new Project();
        project.setId(10L);
        project.setLegalEntityId(77L);
        when(projectService.getById(10L)).thenReturn(project);
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedProjectIds(any())).thenReturn(Set.of(10L));
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        LegacyAiEndpointBoundary boundary = boundary();
        CopilotExecutionContext context = context("2026-03-01T08:00:00Z", "America/Los_Angeles");
        AiMatchingScopeGuard canonical = new AiMatchingScopeGuard(dataScopeService, organizationScopeService,
                contextFactory, contextBinder);

        assertTrue(canonical.allowsProject(10L, context));
        assertEquals(project, boundary.assertProject(10L, context));
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> boundary.assertProject(10L, null));
    }

    @Test
    void 法人がcanonicalcontextと違えばlegacyも拒否する() {
        Engineer engineer = engineer(1L, 88L);
        when(engineerService.getById(1L)).thenReturn(engineer);
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        LegacyAiEndpointBoundary boundary = boundary();
        CopilotExecutionContext context = context("2026-03-01T00:00:00Z", "UTC");

        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> boundary.assertEngineer(1L, context));
    }

    @Test
    void legacy境界は固定ClockのnowではなくExecutionContext日付を使う() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/ses/service/ai/LegacyAiEndpointBoundary.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("LocalDate.now"));
        assertFalse(source.contains("Clock clock"));
        assertTrue(source.contains("context.asOfDate()"));
        assertTrue(source.contains("CopilotExecutionContextFactory"));
    }

    private LegacyAiEndpointBoundary boundary() {
        return new LegacyAiEndpointBoundary(engineerService, projectService, dataScopeService,
                organizationScopeService, contextFactory, contextBinder);
    }

    private static CopilotExecutionContext context(String instant, String zone) {
        return new CopilotExecutionContext("tenant-e1", 77L, Instant.parse(instant), ZoneId.of(zone));
    }

    private static Engineer engineer(Long id, Long legalEntityId) {
        Engineer engineer = new Engineer();
        engineer.setId(id);
        engineer.setLegalEntityId(legalEntityId);
        return engineer;
    }
}
