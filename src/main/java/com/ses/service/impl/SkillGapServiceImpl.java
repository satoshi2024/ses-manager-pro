package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.skillgap.SkillGapItem;
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
import com.ses.service.SkillGapHistoryPolicy;
import com.ses.service.SkillGapService;
import com.ses.service.SkillGapTaxonomyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * effective eventを唯一の入力とするskill-gap calculator。
 * t_engineer_skill / t_project_skill / t_project_positionのcurrent projectionは参照しない。
 */
@Slf4j
@Service
public class SkillGapServiceImpl implements SkillGapService {

    private static final String DEFAULT_TENANT = "default";
    private static final Map<String, Integer> LEVELS = Map.of(
            "初級", 1,
            "中級", 2,
            "上級", 3);

    private final EngineerSkillEventMapper engineerSkillEventMapper;
    private final ProjectSkillEventMapper projectSkillEventMapper;
    private final ProjectPositionEventMapper projectPositionEventMapper;
    private final SkillGapSnapshotMapper snapshotMapper;
    private final SkillGapTaxonomyResolver taxonomyResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LocalDate featureStartDate;

    @Autowired
    public SkillGapServiceImpl(EngineerSkillEventMapper engineerSkillEventMapper,
                               ProjectSkillEventMapper projectSkillEventMapper,
                               ProjectPositionEventMapper projectPositionEventMapper,
                               SkillGapSnapshotMapper snapshotMapper,
                               SkillGapTaxonomyResolver taxonomyResolver,
                               ObjectMapper objectMapper,
                               Clock clock,
                               SkillGapHistoryPolicy historyPolicy) {
        this(engineerSkillEventMapper, projectSkillEventMapper, projectPositionEventMapper, snapshotMapper,
                taxonomyResolver, objectMapper, clock, historyPolicy.featureStartDate());
    }

    /** 固定日を直接渡せるため、history開始日境界をJVM timezoneから独立して検証できる。 */
    public SkillGapServiceImpl(EngineerSkillEventMapper engineerSkillEventMapper,
                               ProjectSkillEventMapper projectSkillEventMapper,
                               ProjectPositionEventMapper projectPositionEventMapper,
                               SkillGapSnapshotMapper snapshotMapper,
                               SkillGapTaxonomyResolver taxonomyResolver,
                               ObjectMapper objectMapper,
                               Clock clock,
                               LocalDate featureStartDate) {
        this.engineerSkillEventMapper = engineerSkillEventMapper;
        this.projectSkillEventMapper = projectSkillEventMapper;
        this.projectPositionEventMapper = projectPositionEventMapper;
        this.snapshotMapper = snapshotMapper;
        this.taxonomyResolver = taxonomyResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.featureStartDate = featureStartDate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillGapResult calculate(SkillGapRequest rawRequest) {
        SkillGapRequest request = normalize(rawRequest);
        if (request.asOf().isBefore(featureStartDate)
                || request.periodFrom().isBefore(featureStartDate)) {
            return SkillGapResult.unavailable(request, "as_of_before_feature_start");
        }

        List<EngineerSkillEvent> allSupplyEvents = engineerSkillEventMapper
                .selectByEngineerId(request.engineerId());
        List<EngineerSkillEvent> supplyEvents = effectiveSupply(allSupplyEvents, request.asOf());

        DemandData demand = loadDemand(request);
        if (allSupplyEvents.isEmpty() || !demand.hasHistory()) {
            return SkillGapResult.unavailable(request,
                    allSupplyEvents.isEmpty() ? "supply_history_unavailable" : "demand_history_unavailable");
        }

        Map<Long, EngineerSkillEvent> supplyBySkill = latestSupplyBySkill(supplyEvents);
        List<SkillGapItem> items = mergeRequirements(demand.requirements(), supplyBySkill);
        List<String> warnings = new ArrayList<>(demand.warnings());
        if (supplyEvents.isEmpty()) {
            warnings.add("supply_empty_at_as_of");
        }

        SkillGapResult result = new SkillGapResult(
                STATUS_OK,
                null,
                request.asOf(),
                request.periodFrom(),
                request.periodTo(),
                request.engineerId(),
                request.projectId(),
                request.demandSource(),
                items,
                List.copyOf(new LinkedHashSet<>(warnings)),
                null);
        return saveSnapshot(result, request, allSupplyEvents, demand.allEventIds());
    }

    @Override
    @Transactional(readOnly = true)
    public SkillGapResult replay(Long snapshotId) {
        SkillGapSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || snapshot.getResultJson() == null || snapshot.getResultHash() == null) {
            throw BusinessException.of(404, "error.skillGap.snapshotNotFound");
        }
        if (!hash(snapshot.getResultJson()).equals(snapshot.getResultHash())) {
            throw BusinessException.of(409, "error.skillGap.snapshotCorrupt");
        }
        try {
            return objectMapper.readValue(snapshot.getResultJson(), SkillGapResult.class)
                    .withSnapshotId(snapshot.getId());
        } catch (Exception e) {
            log.warn("skill gap snapshotの復元に失敗しました: snapshotId={}", snapshotId, e);
            throw BusinessException.of(409, "error.skillGap.snapshotCorrupt");
        }
    }

    private SkillGapRequest normalize(SkillGapRequest request) {
        if (request == null || request.engineerId() == null || request.projectId() == null) {
            throw BusinessException.of(400, "error.skillGap.subjectRequired");
        }
        LocalDate asOf = request.asOf() == null ? LocalDate.now(clock) : request.asOf();
        LocalDate from = request.periodFrom() == null ? asOf : request.periodFrom();
        LocalDate to = request.periodTo() == null ? asOf : request.periodTo();
        if (from.isAfter(to)) {
            throw BusinessException.of(400, "error.skillGap.invalidPeriod");
        }
        SkillGapService.DemandSource source = request.demandSource() == null
                ? SkillGapService.DemandSource.COMBINED : request.demandSource();
        return new SkillGapRequest(request.engineerId(), request.projectId(), asOf, from, to, source,
                request.createdBy());
    }

    private List<EngineerSkillEvent> effectiveSupply(List<EngineerSkillEvent> events, LocalDate asOf) {
        return events.stream()
                .filter(event -> EngineerSkillEvent.TYPE_OPEN.equals(event.getEventType()))
                .filter(event -> activeAt(event.getEffectiveFrom(), event.getEffectiveTo(), asOf))
                .sorted(eventComparator())
                .toList();
    }

    private Map<Long, EngineerSkillEvent> latestSupplyBySkill(List<EngineerSkillEvent> events) {
        Map<Long, EngineerSkillEvent> result = new LinkedHashMap<>();
        for (EngineerSkillEvent event : events) {
            result.merge(event.getSkillId(), event, (left, right) -> eventComparator().compare(left, right) <= 0 ? right : left);
        }
        return result;
    }

    private DemandData loadDemand(SkillGapRequest request) {
        List<ProjectSkillEvent> projectEvents = projectSkillEventMapper.selectByProjectId(request.projectId());
        List<ProjectPositionEvent> positionEvents = projectPositionEventMapper.selectByProjectId(request.projectId());
        DemandData project = new DemandData(new ArrayList<>(), eventIds(projectEvents), new ArrayList<>(),
                !projectEvents.isEmpty());
        DemandData position = new DemandData(new ArrayList<>(), eventIds(positionEvents), new ArrayList<>(),
                !positionEvents.isEmpty());

        if (request.demandSource() == DemandSource.PROJECT || request.demandSource() == DemandSource.COMBINED) {
            project.requirements().addAll(projectRequirements(projectEvents, request.asOf()));
        }
        if (request.demandSource() == DemandSource.POSITION || request.demandSource() == DemandSource.COMBINED) {
            position.requirements().addAll(positionRequirements(positionEvents, request));
        }

        List<Requirement> merged = switch (request.demandSource()) {
            case PROJECT -> project.requirements();
            case POSITION -> position.requirements();
            case COMBINED -> combine(project.requirements(), position.requirements());
        };
        List<Long> eventIds = new ArrayList<>();
        if (request.demandSource() == DemandSource.PROJECT || request.demandSource() == DemandSource.COMBINED) {
            eventIds.addAll(project.allEventIds());
        }
        if (request.demandSource() == DemandSource.POSITION || request.demandSource() == DemandSource.COMBINED) {
            eventIds.addAll(position.allEventIds());
        }
        List<String> warnings = new ArrayList<>();
        warnings.addAll(position.warnings());
        boolean hasHistory = switch (request.demandSource()) {
            case PROJECT -> project.hasHistory();
            case POSITION -> position.hasHistory();
            case COMBINED -> project.hasHistory() || position.hasHistory();
        };
        return new DemandData(merged, eventIds, warnings, hasHistory);
    }

    private List<Requirement> projectRequirements(List<ProjectSkillEvent> events, LocalDate asOf) {
        Map<Long, ProjectSkillEvent> latest = new LinkedHashMap<>();
        events.stream()
                .filter(event -> ProjectSkillEvent.TYPE_OPEN.equals(event.getEventType()))
                .filter(event -> activeAt(event.getEffectiveFrom(), event.getEffectiveTo(), asOf))
                .sorted(eventComparatorProject())
                .forEach(event -> latest.merge(event.getSkillId(), event,
                        (left, right) -> eventComparatorProject().compare(left, right) <= 0 ? right : left));
        List<Requirement> result = new ArrayList<>();
        for (ProjectSkillEvent event : latest.values()) {
            SkillGapTaxonomyResolver.Resolution resolution = taxonomyResolver.resolveCanonicalId(event.getSkillId());
            result.add(new Requirement(resolution, event.getRequiredLevel(), 1,
                    event.getIsMust() != null && event.getIsMust() == 1,
                    "PROJECT", "PROJECT", List.of(event.getId())));
        }
        return result;
    }

    private List<Requirement> positionRequirements(List<ProjectPositionEvent> events, SkillGapRequest request) {
        Map<Long, ProjectPositionEvent> latestByPosition = new LinkedHashMap<>();
        events.stream()
                .filter(event -> activeAt(event.getEffectiveFrom(), event.getEffectiveTo(), request.asOf()))
                .sorted(Comparator.comparing(ProjectPositionEvent::getPositionId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(positionEventComparator()))
                .forEach(event -> latestByPosition.merge(event.getPositionId(), event,
                        (left, right) -> positionEventComparator().compare(left, right) <= 0 ? right : left));

        List<Requirement> result = new ArrayList<>();
        for (ProjectPositionEvent event : latestByPosition.values()) {
            if (ProjectPositionEvent.TYPE_DELETE.equals(event.getEventType())) {
                continue;
            }
            if (!overlaps(event.getStartDate(), event.getEndDate(), request.periodFrom(), request.periodTo())) {
                continue;
            }
            if (!isDemandStatus(event.getStatus())) {
                continue;
            }
            result.addAll(parsePositionSkills(event));
        }
        return result;
    }

    private List<Requirement> parsePositionSkills(ProjectPositionEvent event) {
        if (event.getSkillsJson() == null || event.getSkillsJson().isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(event.getSkillsJson());
            JsonNode skills = root.isArray() ? root : root.path("skills");
            if (!skills.isArray()) {
                return List.of();
            }
            List<Requirement> result = new ArrayList<>();
            for (JsonNode node : skills) {
                String name = text(node, "skillName", "name", "skill");
                Long skillId = node.isObject() && node.has("skillId") && node.get("skillId").canConvertToLong()
                        ? node.get("skillId").longValue() : null;
                SkillGapTaxonomyResolver.Resolution resolution = skillId != null
                        ? taxonomyResolver.resolveCanonicalId(skillId)
                        : taxonomyResolver.resolveName(name, event.getEffectiveFrom());
                if (resolution.unknown() && name == null && skillId == null) {
                    continue;
                }
                String requiredLevel = text(node, "requiredLevel", "level", "targetLevel");
                boolean mandatory = booleanValue(node, "isMust", "mandatory", "required");
                result.add(new Requirement(resolution,
                        requiredLevel,
                        event.getRequiredCount() == null ? 1 : event.getRequiredCount(),
                        mandatory,
                        "POSITION",
                        "POSITION",
                        List.of(event.getId())));
            }
            return result;
        } catch (Exception e) {
            log.warn("position skills_jsonを解釈できません: positionId={}", event.getPositionId());
            return List.of();
        }
    }

    private List<Requirement> combine(List<Requirement> project, List<Requirement> position) {
        Map<String, Requirement> result = new LinkedHashMap<>();
        for (Requirement requirement : project) {
            result.put(requirement.key(), requirement);
        }
        for (Requirement requirement : position) {
            result.merge(requirement.key(), requirement, (projectRequirement, positionRequirement) -> projectRequirement);
        }
        return new ArrayList<>(result.values());
    }

    private List<SkillGapItem> mergeRequirements(List<Requirement> requirements,
                                                  Map<Long, EngineerSkillEvent> supplyBySkill) {
        Map<String, Requirement> merged = new LinkedHashMap<>();
        for (Requirement requirement : requirements) {
            merged.merge(requirement.key(), requirement, Requirement::merge);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(Requirement::key))
                .map(requirement -> toItem(requirement, supplyBySkill))
                .toList();
    }

    private SkillGapItem toItem(Requirement requirement, Map<Long, EngineerSkillEvent> supplyBySkill) {
        EngineerSkillEvent supply = requirement.resolution().canonicalSkillId() == null
                ? null : supplyBySkill.get(requirement.resolution().canonicalSkillId());
        String suppliedLevel = supply == null ? null : supply.getProficiency();
        boolean gap = requirement.resolution().unknown()
                || supply == null
                || level(suppliedLevel) < level(requirement.requiredLevel());
        List<Long> supplyIds = supply == null || supply.getId() == null ? List.of() : List.of(supply.getId());
        return new SkillGapItem(
                requirement.key(),
                requirement.resolution().canonicalSkillId(),
                requirement.resolution().inputName(),
                requirement.resolution().canonicalSkillName(),
                requirement.resolution().resolution(),
                requirement.requiredLevel(),
                suppliedLevel,
                requirement.requiredCount(),
                supply == null ? 0 : 1,
                requirement.mandatory(),
                gap,
                requirement.resolution().unknown(),
                requirement.source(),
                requirement.precedence(),
                List.copyOf(requirement.demandEventIds()),
                supplyIds);
    }

    private SkillGapResult saveSnapshot(SkillGapResult result, SkillGapRequest request,
                                        List<EngineerSkillEvent> supplyEvents, List<Long> demandEventIds) {
        try {
            String json = objectMapper.writeValueAsString(result);
            SkillGapSnapshot snapshot = new SkillGapSnapshot();
            snapshot.setTenantId(DEFAULT_TENANT);
            snapshot.setAsOfDate(request.asOf());
            snapshot.setEngineerId(request.engineerId());
            snapshot.setProjectId(request.projectId());
            snapshot.setDemandSource(request.demandSource().name());
            snapshot.setDemandVersion(hash(demandEventIds.stream().map(String::valueOf).collect(Collectors.joining(","))));
            snapshot.setSupplyVersion(hash(supplyEvents.stream().map(event -> String.valueOf(event.getId()) + ":"
                    + event.getEffectiveFrom() + ":" + event.getEffectiveTo()).collect(Collectors.joining("|"))));
            snapshot.setTaxonomyVersion(taxonomyResolver.fingerprint(request.asOf()));
            snapshot.setResultHash(hash(json));
            snapshot.setResultJson(json);
            snapshot.setCreatedAt(LocalDateTime.now(clock));
            snapshot.setCreatedBy(request.createdBy() == null ? SecurityUtils.currentUserId() : request.createdBy());
            snapshotMapper.insert(snapshot);
            return result.withSnapshotId(snapshot.getId());
        } catch (Exception e) {
            throw BusinessException.of(500, "error.skillGap.snapshotPersistFailed");
        }
    }

    private boolean isDemandStatus(String status) {
        return !Objects.equals(status, "取消")
                && !Objects.equals(status, "充足")
                && !Objects.equals(status, "保留");
    }

    private boolean activeAt(LocalDate from, LocalDate to, LocalDate asOf) {
        return from != null && !from.isAfter(asOf) && (to == null || !to.isBefore(asOf));
    }

    private boolean overlaps(LocalDate from, LocalDate to, LocalDate periodFrom, LocalDate periodTo) {
        return (from == null || !from.isAfter(periodTo))
                && (to == null || !to.isBefore(periodFrom));
    }

    private Comparator<EngineerSkillEvent> eventComparator() {
        return Comparator.comparing(EngineerSkillEvent::getEffectiveFrom,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(EngineerSkillEvent::getOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(EngineerSkillEvent::getId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<ProjectSkillEvent> eventComparatorProject() {
        return Comparator.comparing(ProjectSkillEvent::getEffectiveFrom,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProjectSkillEvent::getOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProjectSkillEvent::getId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<ProjectPositionEvent> positionEventComparator() {
        return Comparator.comparing(ProjectPositionEvent::getEffectiveFrom,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProjectPositionEvent::getOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProjectPositionEvent::getId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int level(String value) {
        return LEVELS.getOrDefault(value, value == null ? 0 : 1);
    }

    private String text(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return node != null && node.isTextual() ? node.asText() : null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && value.isValueNode()) {
                return value.asText();
            }
        }
        return null;
    }

    private boolean booleanValue(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null) {
                return value.isBoolean() ? value.booleanValue() : value.asInt(0) == 1;
            }
        }
        return false;
    }

    private List<Long> eventIds(Collection<?> events) {
        return events.stream()
                .map(event -> event instanceof EngineerSkillEvent engineer ? engineer.getId()
                        : event instanceof ProjectSkillEvent project ? project.getId()
                        : event instanceof ProjectPositionEvent position ? position.getId() : null)
                .filter(Objects::nonNull)
                .toList();
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private record DemandData(List<Requirement> requirements, List<Long> allEventIds,
                              List<String> warnings, boolean hasHistory) {
    }

    private record Requirement(SkillGapTaxonomyResolver.Resolution resolution,
                               String requiredLevel,
                               Integer requiredCount,
                               boolean mandatory,
                               String source,
                               String precedence,
                               List<Long> demandEventIds) {
        String key() {
            return resolution.unknown()
                    ? "UNKNOWN:" + resolution.normalizedInput()
                    : "SKILL:" + resolution.canonicalSkillId();
        }

        Requirement merge(Requirement other) {
            String level = levelRank(other.requiredLevel) > levelRank(requiredLevel)
                    ? other.requiredLevel : requiredLevel;
            List<Long> ids = new ArrayList<>(demandEventIds);
            ids.addAll(other.demandEventIds);
            return new Requirement(resolution, level,
                    safeCount(requiredCount) + safeCount(other.requiredCount),
                    mandatory || other.mandatory, source, precedence, List.copyOf(new LinkedHashSet<>(ids)));
        }

        private static int safeCount(Integer count) {
            return count == null ? 0 : count;
        }

        private static int levelRank(String value) {
            return LEVELS.getOrDefault(value, value == null ? 0 : 1);
        }
    }
}
