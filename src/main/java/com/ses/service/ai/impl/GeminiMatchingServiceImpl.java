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
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.entity.BpAvailability;
import com.ses.service.ai.AiAllowlistFields;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
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
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiMatchingServiceImpl implements AiMatchingService {

    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ProjectSkillMapper projectSkillMapper;
    private final SkillTagMapper skillTagMapper;
    private final AiLogMapper aiLogMapper;
    private final BpAvailabilityMapper bpAvailabilityMapper;
    private final ObjectMapper objectMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final AiExecutionGateway aiExecutionGateway;

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

            BigDecimal pMin = p.getUnitPriceMin() != null ? p.getUnitPriceMin() : null;
            BigDecimal pMax = p.getUnitPriceMax() != null ? p.getUnitPriceMax() : null;
            BigDecimal ePrice = engineer.getExpectedUnitPrice() != null ? engineer.getExpectedUnitPrice() : null;
            
            MatchScore score = MatchScoreCalculator.calculate(
                    mustIds, niceIds, engSkillIds, pMin, pMax,
                    ePrice, p.getStartDate(), engineer.getAvailableDate()
            );

            if (score.isExcluded()) continue;

            MatchResultDto dto = new MatchResultDto();
            dto.setProjectId(p.getId());
            dto.setProjectName(p.getProjectName());
            dto.setScore(score.getTotalScore());
            fillMatchExplanation(dto, AiAllowlistFields.merge(
                    AiAllowlistFields.engineer(engineer, engSkills),
                    AiAllowlistFields.project(p),
                    AiAllowlistFields.ruleScore(score)), score.getTotalScore());
            results.add(dto);
        }

        results.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        if (results.size() > 10) results = results.subList(0, 10);

        logAiMatch("マッチング(Gemini)", Map.of("engineerId", engineerId));
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
        
        // 内部要員
        for (Engineer e : candidates) {
            Set<Long> eSkills = esMap.getOrDefault(e.getId(), Collections.emptySet());
            BigDecimal pMin = project.getUnitPriceMin() != null ? project.getUnitPriceMin() : null;
            BigDecimal pMax = project.getUnitPriceMax() != null ? project.getUnitPriceMax() : null;
            BigDecimal ePrice = e.getExpectedUnitPrice() != null ? e.getExpectedUnitPrice() : null;
            
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
            fillMatchExplanation(dto, AiAllowlistFields.merge(
                    AiAllowlistFields.engineer(e, null),
                    AiAllowlistFields.project(project),
                    Map.of("engineerSkill.skillName", eSkills.stream()
                            .map(id -> tagNameMap.getOrDefault(id, ""))
                            .filter(n -> n != null && !n.isBlank())
                            .collect(Collectors.joining(","))),
                    AiAllowlistFields.ruleScore(score)), score.getTotalScore());
            results.add(dto);
        }

        // BpAvailability (BP要員)
        LambdaQueryWrapper<BpAvailability> bpWrapper = new LambdaQueryWrapper<BpAvailability>()
                .eq(BpAvailability::getStatus, "提案可能");
        List<BpAvailability> externalBps = bpAvailabilityMapper.selectList(bpWrapper);
        
        for (BpAvailability bp : externalBps) {
            Set<Long> bpSkills = new HashSet<>();
            try {
                if (bp.getSkillsJson() != null) {
                    List<String> skillNames = objectMapper.readValue(bp.getSkillsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    for (String name : skillNames) {
                        for (Map.Entry<Long, String> entry : tagNameMap.entrySet()) {
                            if (entry.getValue().equalsIgnoreCase(name)) {
                                bpSkills.add(entry.getKey());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to parse skills for BpAvailability: " + bp.getId(), ex);
            }

            BigDecimal pMin = project.getUnitPriceMin() != null ? project.getUnitPriceMin() : null;
            BigDecimal pMax = project.getUnitPriceMax() != null ? project.getUnitPriceMax() : null;
            BigDecimal bpPrice = bp.getUnitPrice() != null ? new BigDecimal(bp.getUnitPrice()) : null;
            
            MatchScore score = MatchScoreCalculator.calculate(
                    mustIds, niceIds, bpSkills, pMin, pMax,
                    bpPrice, project.getStartDate(), bp.getAvailableFrom()
            );

            if (score.isExcluded()) continue;

            MatchResultDto dto = new MatchResultDto();
            dto.setBpAvailabilityId(bp.getId());
            dto.setIsExternalBp(true);
            dto.setEngineerName("[BP] " + (bp.getInitialName() != null ? bp.getInitialName() : "不明"));
            dto.setProposedPrice(bp.getUnitPrice() != null ? bp.getUnitPrice().intValue() : null);
            dto.setScore(score.getTotalScore());
            fillMatchExplanation(dto, AiAllowlistFields.merge(
                    AiAllowlistFields.bp(bp, bpSkills.stream()
                            .map(id -> tagNameMap.getOrDefault(id, ""))
                            .filter(n -> n != null && !n.isBlank())
                            .collect(Collectors.joining(","))),
                    AiAllowlistFields.project(project),
                    AiAllowlistFields.ruleScore(score)), score.getTotalScore());
            results.add(dto);
        }

        results.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        if (results.size() > 10) results = results.subList(0, 10);

        logAiMatch("要員推薦(Gemini)", Map.of("projectId", projectId));
        return results;
    }

    private void fillMatchExplanation(MatchResultDto dto, Map<String, Object> fields, int defaultScore) {
        try {
            AiGatewayResult result = aiExecutionGateway.execute(AiGatewayRequest.builder()
                    .useCase(AiGatewayRequest.USE_MATCHING)
                    .trustedInstruction("""
                            あなたはSES営業アシスタントです。ALLOWLIST_CONTEXT のみを根拠に
                            マッチ理由とアピールポイントをJSONで返してください。HTMLは禁止です。
                            {"reason":"...","sellingPoints":"...","score":0}
                            """)
                    .allowlistedFields(fields)
                    .persistRun(false)
                    .requireJson(true)
                    .build());
            parseAiResponseIntoDto(result.getText(), dto, defaultScore);
        } catch (Exception e) {
            log.warn("AI text generation failed for match explanation", e);
            dto.setReason("AI解析に失敗しました");
            dto.setSellingPoints("アピールポイントの生成に失敗しました");
        }
    }

    private void parseAiResponseIntoDto(String aiResponse, MatchResultDto dto, int defaultScore) {
        try {
            // Remove markdown code blocks if present
            if (aiResponse.startsWith("```json")) {
                aiResponse = aiResponse.substring(7);
            } else if (aiResponse.startsWith("```")) {
                aiResponse = aiResponse.substring(3);
            }
            if (aiResponse.endsWith("```")) {
                aiResponse = aiResponse.substring(0, aiResponse.length() - 3);
            }
            aiResponse = aiResponse.trim();
            
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(aiResponse);
            if (root.has("reason")) {
                dto.setReason(root.get("reason").asText());
            }
            if (root.has("sellingPoints")) {
                dto.setSellingPoints(root.get("sellingPoints").asText());
            }
            if (root.has("score")) {
                dto.setScore(root.get("score").asInt());
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI response: {}", aiResponse, e);
            dto.setReason("AIによる理由生成結果の解析に失敗しました。");
            dto.setSellingPoints("解析失敗");
        }
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
                // Ignore
            }
            log.setCreatedBy(userId);
            aiLogMapper.insert(log);
        } catch (Exception e) {
            log.error("Failed to insert AI log", e);
        }
    }
}
