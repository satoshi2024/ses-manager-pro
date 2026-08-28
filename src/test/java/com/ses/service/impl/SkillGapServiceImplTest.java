package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ses.dto.skillgap.SkillGapRequest;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.entity.EngineerSkillEvent;
import com.ses.entity.ProjectPositionEvent;
import com.ses.entity.ProjectSkillEvent;
import com.ses.entity.SkillGapSnapshot;
import com.ses.mapper.EngineerSkillEventMapper;
import com.ses.mapper.ProjectPositionEventMapper;
import com.ses.mapper.ProjectSkillEventMapper;
import com.ses.mapper.SkillGapSnapshotMapper;
import com.ses.service.SkillGapService;
import com.ses.service.SkillGapTaxonomyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillGapServiceImplTest {

    private static final LocalDate FEATURE_START = LocalDate.of(2026, 8, 28);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Mock
    private EngineerSkillEventMapper engineerSkillEventMapper;
    @Mock
    private ProjectSkillEventMapper projectSkillEventMapper;
    @Mock
    private ProjectPositionEventMapper projectPositionEventMapper;
    @Mock
    private SkillGapSnapshotMapper snapshotMapper;
    @Mock
    private SkillGapTaxonomyResolver taxonomyResolver;

    private SkillGapServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new SkillGapServiceImpl(engineerSkillEventMapper, projectSkillEventMapper,
                projectPositionEventMapper, snapshotMapper, taxonomyResolver, objectMapper,
                Clock.fixed(Instant.parse("2026-09-01T01:00:00Z"), ZoneId.of("Asia/Tokyo")), FEATURE_START);
        lenient().when(projectSkillEventMapper.selectByProjectId(20L)).thenReturn(List.of());
        lenient().when(projectPositionEventMapper.selectByProjectId(20L)).thenReturn(List.of());
        lenient().doAnswer(invocation -> {
            SkillGapSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(900L);
            return 1;
        }).when(snapshotMapper).insert(any(SkillGapSnapshot.class));
    }

    @Test
    void effectiveProjectAndSupplyEventsAreComparedWithoutCurrentFallback() {
        EngineerSkillEvent supply = supply(101L, 1L, "初級", LocalDate.of(2026, 8, 28), null);
        ProjectSkillEvent demand = project(201L, 1L, "中級", LocalDate.of(2026, 8, 28), null);
        when(engineerSkillEventMapper.selectByEngineerId(10L)).thenReturn(List.of(supply));
        when(projectSkillEventMapper.selectByProjectId(20L)).thenReturn(List.of(demand));
        when(taxonomyResolver.resolveCanonicalId(1L)).thenReturn(canonical(1L, "Java"));
        when(taxonomyResolver.fingerprint(AS_OF)).thenReturn("taxonomy-v1");

        SkillGapResult result = service.calculate(new SkillGapRequest(10L, 20L, AS_OF,
                SkillGapService.DemandSource.PROJECT));

        assertEquals(SkillGapService.STATUS_OK, result.status());
        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).gap());
        assertEquals("中級", result.items().get(0).requiredLevel());
        assertEquals("初級", result.items().get(0).suppliedLevel());
        assertEquals(900L, result.snapshotId());
    }

    @Test
    void beforeFeatureStartReturnsUnavailableEvenWhenCurrentLikeEventsExist() {
        SkillGapResult result = service.calculate(new SkillGapRequest(10L, 20L,
                FEATURE_START.minusDays(1), SkillGapService.DemandSource.PROJECT));

        assertEquals(SkillGapService.STATUS_HISTORICAL_DATA_UNAVAILABLE, result.status());
        assertEquals("as_of_before_feature_start", result.unavailableReason());
        verify(snapshotMapper, never()).insert(any(SkillGapSnapshot.class));
    }

    @Test
    void combinedUsesProjectPrecedenceAndAddsPositionOnlySkillsAtPeriodBoundary() {
        EngineerSkillEvent supply = supply(101L, 2L, "上級", FEATURE_START, null);
        ProjectSkillEvent projectJava = project(201L, 1L, "上級", FEATURE_START, null);
        ProjectPositionEvent position = position(301L, 20L, "募集中", FEATURE_START,
                null, "[{\"skillId\":1,\"skillName\":\"Java\",\"requiredLevel\":\"初級\"},{\"skillName\":\"AWS\",\"requiredLevel\":\"中級\"}]");
        when(engineerSkillEventMapper.selectByEngineerId(10L)).thenReturn(List.of(supply));
        when(projectSkillEventMapper.selectByProjectId(20L)).thenReturn(List.of(projectJava));
        when(projectPositionEventMapper.selectByProjectId(20L)).thenReturn(List.of(position));
        when(taxonomyResolver.resolveCanonicalId(1L)).thenReturn(canonical(1L, "Java"));
        when(taxonomyResolver.resolveName("AWS", FEATURE_START)).thenReturn(canonical(2L, "AWS"));
        when(taxonomyResolver.fingerprint(AS_OF)).thenReturn("taxonomy-v1");

        SkillGapResult result = service.calculate(new SkillGapRequest(10L, 20L, AS_OF,
                FEATURE_START, AS_OF, SkillGapService.DemandSource.COMBINED, null));

        assertEquals(2, result.items().size());
        assertEquals("PROJECT", result.items().stream().filter(item -> item.canonicalSkillId().equals(1L))
                .findFirst().orElseThrow().precedence());
        assertEquals("POSITION", result.items().stream().filter(item -> item.canonicalSkillId().equals(2L))
                .findFirst().orElseThrow().source());
    }

    @Test
    void unknownDemandIsReportedAndDoesNotBecomeMaster() {
        when(engineerSkillEventMapper.selectByEngineerId(10L)).thenReturn(List.of(
                supply(101L, 1L, "中級", FEATURE_START, null)));
        when(projectSkillEventMapper.selectByProjectId(20L)).thenReturn(List.of(
                project(201L, 99L, "中級", FEATURE_START, null)));
        when(taxonomyResolver.resolveCanonicalId(99L)).thenReturn(
                new SkillGapTaxonomyResolver.Resolution("skill#99", "SKILL#99", null, null, null, "UNKNOWN"));
        when(taxonomyResolver.fingerprint(AS_OF)).thenReturn("taxonomy-v1");

        SkillGapResult result = service.calculate(new SkillGapRequest(10L, 20L, AS_OF,
                SkillGapService.DemandSource.PROJECT));

        assertTrue(result.items().get(0).unknown());
        assertTrue(result.items().get(0).gap());
        assertEquals(0, result.items().get(0).evidenceCount());
    }

    @Test
    void deleteDayAndAfterDoNotUseDeletedCurrentPositionAsDemand() {
        when(engineerSkillEventMapper.selectByEngineerId(10L)).thenReturn(List.of(
                supply(101L, 1L, "中級", FEATURE_START, null)));
        ProjectPositionEvent beforeDelete = position(301L, 20L, "募集中", FEATURE_START,
                LocalDate.of(2026, 8, 31), "[{\"skillName\":\"Java\"}]");
        ProjectPositionEvent deleteDay = position(302L, 20L, "募集中", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "[{\"skillName\":\"Java\"}]");
        deleteDay.setPositionId(30L);
        deleteDay.setEventType(ProjectPositionEvent.TYPE_DELETE);
        when(projectPositionEventMapper.selectByProjectId(20L)).thenReturn(List.of(beforeDelete, deleteDay));
        when(taxonomyResolver.fingerprint(any())).thenReturn("taxonomy-v1");

        SkillGapResult onDeleteDay = service.calculate(new SkillGapRequest(10L, 20L,
                LocalDate.of(2026, 9, 1), SkillGapService.DemandSource.POSITION));
        SkillGapResult afterDelete = service.calculate(new SkillGapRequest(10L, 20L,
                LocalDate.of(2026, 9, 2), SkillGapService.DemandSource.POSITION));

        assertTrue(onDeleteDay.items().isEmpty());
        assertTrue(afterDelete.items().isEmpty());
        assertEquals(SkillGapService.STATUS_OK, onDeleteDay.status());
    }

    @Test
    void replayVerifiesAndRestoresImmutableSnapshotWithoutReadingCurrentSources() throws Exception {
        EngineerSkillEvent supply = supply(101L, 1L, "初級", FEATURE_START, null);
        ProjectSkillEvent demand = project(201L, 1L, "中級", FEATURE_START, null);
        when(engineerSkillEventMapper.selectByEngineerId(10L)).thenReturn(List.of(supply));
        when(projectSkillEventMapper.selectByProjectId(20L)).thenReturn(List.of(demand));
        when(taxonomyResolver.resolveCanonicalId(1L)).thenReturn(canonical(1L, "Java"));
        when(taxonomyResolver.fingerprint(AS_OF)).thenReturn("taxonomy-v1");
        ArgumentCaptor<SkillGapSnapshot> captor = ArgumentCaptor.forClass(SkillGapSnapshot.class);
        doAnswer(invocation -> {
            SkillGapSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(901L);
            return 1;
        }).when(snapshotMapper).insert(captor.capture());

        service.calculate(new SkillGapRequest(10L, 20L, AS_OF, SkillGapService.DemandSource.PROJECT));
        SkillGapSnapshot stored = captor.getValue();
        when(snapshotMapper.selectById(901L)).thenReturn(stored);

        SkillGapResult replayed = service.replay(901L);

        assertEquals(901L, replayed.snapshotId());
        assertEquals(1, replayed.items().size());
        assertEquals("初級", replayed.items().get(0).suppliedLevel());
    }

    private SkillGapTaxonomyResolver.Resolution canonical(Long id, String name) {
        return new SkillGapTaxonomyResolver.Resolution("skill#" + id, "SKILL#" + id,
                id, name, null, "CANONICAL");
    }

    private EngineerSkillEvent supply(Long id, Long skillId, String proficiency,
                                      LocalDate from, LocalDate to) {
        EngineerSkillEvent event = new EngineerSkillEvent();
        event.setId(id);
        event.setEngineerId(10L);
        event.setSkillId(skillId);
        event.setProficiency(proficiency);
        event.setEventType(EngineerSkillEvent.TYPE_OPEN);
        event.setEffectiveFrom(from);
        event.setEffectiveTo(to);
        event.setOccurredAt(LocalDateTime.of(from, java.time.LocalTime.NOON));
        return event;
    }

    private ProjectSkillEvent project(Long id, Long skillId, String requiredLevel,
                                      LocalDate from, LocalDate to) {
        ProjectSkillEvent event = new ProjectSkillEvent();
        event.setId(id);
        event.setProjectId(20L);
        event.setSkillId(skillId);
        event.setRequiredLevel(requiredLevel);
        event.setIsMust(1);
        event.setEventType(ProjectSkillEvent.TYPE_OPEN);
        event.setEffectiveFrom(from);
        event.setEffectiveTo(to);
        event.setOccurredAt(LocalDateTime.of(from, java.time.LocalTime.NOON));
        return event;
    }

    private ProjectPositionEvent position(Long id, Long projectId, String status,
                                          LocalDate from, LocalDate to, String skillsJson) {
        ProjectPositionEvent event = new ProjectPositionEvent();
        event.setId(id);
        event.setPositionId(30L);
        event.setProjectId(projectId);
        event.setEventType(ProjectPositionEvent.TYPE_UPDATE);
        event.setStatus(status);
        event.setRequiredCount(1);
        event.setSkillsJson(skillsJson);
        event.setStartDate(from);
        event.setEndDate(to);
        event.setEffectiveFrom(from);
        event.setEffectiveTo(to);
        return event;
    }
}
