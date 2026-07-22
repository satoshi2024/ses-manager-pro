package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.ai.MatchResultDto;
import com.ses.dto.ai.MatchScore;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.AiLog;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ProjectSkill;
import com.ses.entity.EngineerSkill;
import com.ses.entity.SkillTag;
import com.ses.mapper.AiLogMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProjectSkillMapper;
import com.ses.mapper.SkillTagMapper;
import com.ses.service.ai.AiMatchingService;
import com.ses.service.ai.MatchScoreCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "rule")
@RequiredArgsConstructor
public class RuleMatchingServiceImpl implements AiMatchingService {

    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ProjectSkillMapper projectSkillMapper;
    private final SkillTagMapper skillTagMapper;
    private final AiLogMapper aiLogMapper;
    private final ObjectMapper objectMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @Override
    public List<MatchResultDto> findMatchingProjects(Long engineerId) {
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedEngineer(engineerId);
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            return Collections.emptyList();
        }

        List<EngineerSkillDetailDto> engSkills = engineerSkillMapper.selectDetailByEngineerId(engineerId);
        Set<Long> engSkillIds = engSkills.stream()
                .map(EngineerSkillDetailDto::getSkillId)
                .collect(Collectors.toSet());

        LambdaQueryWrapper<Project> pWrapper = new LambdaQueryWrapper<Project>().eq(Project::getStatus, "募集中");
        if (dataScopeService.isScoped()) {
            Set<Long> allowedProjectIds = dataScopeService.allowedProjectIds();
            if (allowedProjectIds == null || allowedProjectIds.isEmpty()) {
                return Collections.emptyList();
            }
            pWrapper.in(Project::getId, allowedProjectIds);
        }
        List<Project> activeProjects = projectMapper.selectList(pWrapper);
        if (activeProjects.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> projectIds = activeProjects.stream().map(Project::getId).collect(Collectors.toList());
        List<ProjectSkill> allProjectSkills = projectSkillMapper.selectList(
                new LambdaQueryWrapper<ProjectSkill>().in(ProjectSkill::getProjectId, projectIds)
        );
        Map<Long, List<ProjectSkill>> psMap = allProjectSkills.stream()
                .collect(Collectors.groupingBy(ProjectSkill::getProjectId));

        List<SkillTag> tags = skillTagMapper.selectList(null);
        Map<Long, String> tagNameMap = tags.stream()
                .collect(Collectors.toMap(SkillTag::getId, SkillTag::getSkillName));

        List<MatchResultDto> results = new ArrayList<>();
        for (Project p : activeProjects) {
            List<ProjectSkill> pSkills = psMap.getOrDefault(p.getId(), Collections.emptyList());
            Set<Long> mustIds = pSkills.stream().filter(s -> Integer.valueOf(1).equals(s.getIsMust())).map(ProjectSkill::getSkillId).collect(Collectors.toSet());
            Set<Long> niceIds = pSkills.stream().filter(s -> Integer.valueOf(0).equals(s.getIsMust())).map(ProjectSkill::getSkillId).collect(Collectors.toSet());

            BigDecimal pMin = p.getUnitPriceMin() != null ? p.getUnitPriceMin().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;
            BigDecimal pMax = p.getUnitPriceMax() != null ? p.getUnitPriceMax().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;

            BigDecimal ePrice = engineer.getExpectedUnitPrice() != null ? engineer.getExpectedUnitPrice().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;
            MatchScore score = MatchScoreCalculator.calculate(
                    mustIds, niceIds, engSkillIds, pMin, pMax,
                    ePrice, p.getStartDate(), engineer.getAvailableDate()
            );

            if (score.isExcluded()) continue;

            MatchResultDto dto = new MatchResultDto();
            dto.setProjectId(p.getId());
            dto.setProjectName(p.getProjectName());
            dto.setScore(score.getTotalScore());
            dto.setReason(buildProjectReason(score, mustIds, tagNameMap));
            dto.setSellingPoints(buildEngineerSellingPoints(engSkills));
            results.add(dto);
        }

        results.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        if (results.size() > 10) results = results.subList(0, 10);

        logAiMatch("マッチング", Map.of("engineerId", engineerId));

        return results;
    }

    @Override
    public List<MatchResultDto> findMatchingEngineers(Long projectId) {
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedProject(projectId);
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return Collections.emptyList();
        }

        List<ProjectSkill> pSkills = projectSkillMapper.selectList(
                new LambdaQueryWrapper<ProjectSkill>().eq(ProjectSkill::getProjectId, projectId)
        );
        Set<Long> mustIds = pSkills.stream().filter(s -> Integer.valueOf(1).equals(s.getIsMust())).map(ProjectSkill::getSkillId).collect(Collectors.toSet());
        Set<Long> niceIds = pSkills.stream().filter(s -> Integer.valueOf(0).equals(s.getIsMust())).map(ProjectSkill::getSkillId).collect(Collectors.toSet());

        LambdaQueryWrapper<Engineer> eWrapper = new LambdaQueryWrapper<Engineer>().in(Engineer::getStatus, Arrays.asList("Bench", "提案中"));
        if (dataScopeService.isScoped()) {
            Set<Long> allowedEngineerIds = dataScopeService.allowedEngineerIds();
            if (allowedEngineerIds == null || allowedEngineerIds.isEmpty()) {
                return Collections.emptyList();
            }
            eWrapper.in(Engineer::getId, allowedEngineerIds);
        }
        List<Engineer> candidates = engineerMapper.selectList(eWrapper);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> engineerIds = candidates.stream().map(Engineer::getId).collect(Collectors.toList());
        List<EngineerSkill> allEngSkills = engineerSkillMapper.selectList(
                new LambdaQueryWrapper<EngineerSkill>().in(EngineerSkill::getEngineerId, engineerIds)
        );
        Map<Long, Set<Long>> esMap = allEngSkills.stream()
                .collect(Collectors.groupingBy(EngineerSkill::getEngineerId, Collectors.mapping(EngineerSkill::getSkillId, Collectors.toSet())));

        List<SkillTag> tags = skillTagMapper.selectList(null);
        Map<Long, String> tagNameMap = tags.stream()
                .collect(Collectors.toMap(SkillTag::getId, SkillTag::getSkillName));

        List<MatchResultDto> results = new ArrayList<>();
        for (Engineer e : candidates) {
            Set<Long> eSkills = esMap.getOrDefault(e.getId(), Collections.emptySet());

            BigDecimal pMin = project.getUnitPriceMin() != null ? project.getUnitPriceMin().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;
            BigDecimal pMax = project.getUnitPriceMax() != null ? project.getUnitPriceMax().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;

            BigDecimal ePrice = e.getExpectedUnitPrice() != null ? e.getExpectedUnitPrice().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;
            MatchScore score = MatchScoreCalculator.calculate(
                    mustIds, niceIds, eSkills, pMin, pMax,
                    ePrice, project.getStartDate(), e.getAvailableDate()
            );

            if (score.isExcluded()) continue;

            MatchResultDto dto = new MatchResultDto();
            dto.setEngineerId(e.getId());
            dto.setEngineerName(e.getFullName());
            dto.setProposedPrice(e.getExpectedUnitPrice() != null ? e.getExpectedUnitPrice().intValue() : null);
            dto.setScore(score.getTotalScore());
            dto.setReason(buildEngineerReason(score, mustIds, tagNameMap));
            dto.setSellingPoints(buildEngineerSellingPointsDirect(allEngSkills.stream().filter(s -> s.getEngineerId().equals(e.getId())).collect(Collectors.toList()), tagNameMap));
            results.add(dto);
        }

        results.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        if (results.size() > 10) results = results.subList(0, 10);

        logAiMatch("要員推薦", Map.of("projectId", projectId));

        return results;
    }

    private String buildProjectReason(MatchScore score, Set<Long> mustIds, Map<Long, String> tagNameMap) {
        StringBuilder sb = new StringBuilder();
        int matched = score.getMatchedMustIds().size();
        int totalMust = mustIds.size();
        if (totalMust > 0) {
            sb.append(String.format("必須スキル %d/%d 充足", matched, totalMust));
            if (!score.getMissingMustIds().isEmpty()) {
                String missingNames = score.getMissingMustIds().stream().map(id -> tagNameMap.getOrDefault(id, "不明")).collect(Collectors.joining(", "));
                sb.append(String.format(" (不足: %s)", missingNames));
            }
            sb.append("。");
        } else {
            sb.append("必須スキル指定なし。");
        }

        if (score.getPriceScore() == 20) {
            sb.append("単価レンジ内。");
        } else {
            sb.append("単価レンジ外。");
        }

        if (score.getDateScore() == 10) {
            sb.append("稼動可能日OK。");
        }

        return sb.toString();
    }

    private String buildEngineerReason(MatchScore score, Set<Long> mustIds, Map<Long, String> tagNameMap) {
        return buildProjectReason(score, mustIds, tagNameMap);
    }

    private String buildEngineerSellingPoints(List<EngineerSkillDetailDto> engSkills) {
        return engSkills.stream()
                .filter(s -> s.getExperienceYears() != null && s.getExperienceYears() >= 3)
                .map(s -> s.getSkillName() + "(" + s.getExperienceYears() + "年)")
                .collect(Collectors.joining(", "));
    }

    private String buildEngineerSellingPointsDirect(List<EngineerSkill> engSkills, Map<Long, String> tagNameMap) {
        return engSkills.stream()
                .filter(s -> s.getExperienceYears() != null && s.getExperienceYears() >= 3)
                .map(s -> tagNameMap.getOrDefault(s.getSkillId(), "不明") + "(" + s.getExperienceYears() + "年)")
                .collect(Collectors.joining(", "));
    }

    private void logAiMatch(String type, Map<String, Object> params) {
        try {
            AiLog log = new AiLog();
            log.setRequestType(type);
            log.setRequestParams(objectMapper.writeValueAsString(params));
            log.setTokensUsed(0);
            log.setCostJpy(BigDecimal.ZERO);
            
            Long userId = null;
            try {
                userId = SecurityUtils.currentUserId();
            } catch (Exception e) {
                // Ignore in testing or no-auth context
            }
            log.setCreatedBy(userId);
            
            aiLogMapper.insert(log);
        } catch (Exception e) {
            log.error("Failed to insert AI log", e);
        }
    }
}
