package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.SecurityUtils;
import com.ses.common.util.PriceFormatter;
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
import com.ses.service.ai.AiMatchingService;
import com.ses.service.ai.AiTextService;
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
    private final AiTextService aiTextService;

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
            
            // Generate prompt and call AI
            String prompt = buildPromptForProjectMatch(engineer, p, score, mustIds, niceIds, tagNameMap, engSkills);
            try {
                String aiResponse = aiTextService.generate(prompt);
                parseAiResponseIntoDto(aiResponse, dto, score.getTotalScore());
            } catch (Exception e) {
                log.warn("AI text generation failed for project match, using fallback", e);
                dto.setReason("AI解析に失敗しました");
                dto.setSellingPoints("アピールポイントの生成に失敗しました");
            }
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
            
            String prompt = buildPromptForEngineerMatch(project, e, score, mustIds, niceIds, tagNameMap, eSkills);
            try {
                String aiResponse = aiTextService.generate(prompt);
                parseAiResponseIntoDto(aiResponse, dto, score.getTotalScore());
            } catch (Exception ex) {
                log.warn("AI text generation failed for engineer match", ex);
                dto.setReason("AI解析に失敗しました");
                dto.setSellingPoints("アピールポイントの生成に失敗しました");
            }
            
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
            
            String prompt = buildPromptForBpMatch(project, bp, score, mustIds, niceIds, tagNameMap, bpSkills);
            try {
                String aiResponse = aiTextService.generate(prompt);
                parseAiResponseIntoDto(aiResponse, dto, score.getTotalScore());
            } catch (Exception ex) {
                log.warn("AI text generation failed for BP match", ex);
                dto.setReason("AIマッチングに失敗しました");
                dto.setSellingPoints(bp.getRemarks() != null ? bp.getRemarks() : "外部要員（BP）");
            }
            results.add(dto);
        }

        results.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        if (results.size() > 10) results = results.subList(0, 10);

        logAiMatch("要員推薦(Gemini)", Map.of("projectId", projectId));
        return results;
    }

    private String buildPromptForProjectMatch(Engineer engineer, Project project, MatchScore score, 
                                              Set<Long> mustIds, Set<Long> niceIds, Map<Long, String> tagNameMap, 
                                              List<EngineerSkillDetailDto> engSkills) {
        StringBuilder sb = new StringBuilder();
        sb.append("あなたはSES営業アシスタントです。以下の案件に対して、提案する要員のマッチ理由とアピールポイントを作成してください。\n\n");
        sb.append("【案件情報】\n");
        sb.append("- 案件名: ").append(project.getProjectName()).append("\n");
        sb.append("- 単価幅: ").append(PriceFormatter.format(project.getUnitPriceMin())).append("〜").append(PriceFormatter.format(project.getUnitPriceMax())).append("\n");
        sb.append("- 必須スキル: ").append(mustIds.stream().map(id -> tagNameMap.getOrDefault(id, "")).collect(Collectors.joining(", "))).append("\n");
        sb.append("- 案件詳細: ").append(project.getDescription() != null ? project.getDescription() : "").append("\n\n");

        sb.append("【要員情報】\n");
        sb.append("- イニシャル: ").append(engineer.getInitialName() != null ? engineer.getInitialName() : "").append("\n");
        sb.append("- 希望単価: ").append(PriceFormatter.format(engineer.getExpectedUnitPrice())).append("\n");
        sb.append("- スキル概要: ").append(engSkills.stream().map(s -> s.getSkillName() + "(" + s.getExperienceYears() + "年)").collect(Collectors.joining(", "))).append("\n\n");

        sb.append("【ルールベーススコア計算結果】\n");
        sb.append("- トータルスコア: ").append(score.getTotalScore()).append("\n");
        sb.append("- 必須スキル充足率: ").append(score.getMustCoverage()).append("\n");
        sb.append("- 単価適合スコア: ").append(score.getPriceScore()).append("/20\n\n");

        sb.append("出力は以下のJSONフォーマットのみを返してください。不要なテキストやマークダウンブロックは含めないでください。\n");
        sb.append("{\n");
        sb.append("  \"reason\": \"マッチしている具体的な理由(1〜2文)\",\n");
        sb.append("  \"sellingPoints\": \"要員のアピールポイント(1〜2文)\",\n");
        sb.append("  \"score\": ").append(score.getTotalScore()).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String buildPromptForEngineerMatch(Project project, Engineer engineer, MatchScore score, 
                                               Set<Long> mustIds, Set<Long> niceIds, Map<Long, String> tagNameMap, 
                                               Set<Long> eSkills) {
        StringBuilder sb = new StringBuilder();
        sb.append("あなたはSES営業アシスタントです。以下の案件に推薦する要員のマッチ理由とアピールポイントを作成してください。\n\n");
        sb.append("【案件情報】\n");
        sb.append("- 案件名: ").append(project.getProjectName()).append("\n");
        sb.append("- 単価幅: ").append(PriceFormatter.format(project.getUnitPriceMin())).append("〜").append(PriceFormatter.format(project.getUnitPriceMax())).append("\n");
        sb.append("- 必須スキル: ").append(mustIds.stream().map(id -> tagNameMap.getOrDefault(id, "")).collect(Collectors.joining(", "))).append("\n\n");

        sb.append("【要員情報】\n");
        sb.append("- イニシャル: ").append(engineer.getInitialName() != null ? engineer.getInitialName() : "").append("\n");
        sb.append("- 希望単価: ").append(PriceFormatter.format(engineer.getExpectedUnitPrice())).append("\n");
        sb.append("- 保持スキル: ").append(eSkills.stream().map(id -> tagNameMap.getOrDefault(id, "")).collect(Collectors.joining(", "))).append("\n\n");

        sb.append("【ルールベーススコア計算結果】\n");
        sb.append("- トータルスコア: ").append(score.getTotalScore()).append("\n\n");

        sb.append("出力は以下のJSONフォーマットのみを返してください。不要なテキストやマークダウンブロックは含めないでください。\n");
        sb.append("{\n");
        sb.append("  \"reason\": \"マッチしている具体的な理由(1〜2文)\",\n");
        sb.append("  \"sellingPoints\": \"要員のアピールポイント(1〜2文)\",\n");
        sb.append("  \"score\": ").append(score.getTotalScore()).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String buildPromptForBpMatch(Project project, BpAvailability bp, MatchScore score, 
                                         Set<Long> mustIds, Set<Long> niceIds, Map<Long, String> tagNameMap, 
                                         Set<Long> bpSkills) {
        StringBuilder sb = new StringBuilder();
        sb.append("あなたはSES営業アシスタントです。以下の案件に推薦する外部要員(BP)のマッチ理由とアピールポイントを作成してください。\n\n");
        sb.append("【案件情報】\n");
        sb.append("- 案件名: ").append(project.getProjectName()).append("\n");
        sb.append("- 単価幅: ").append(PriceFormatter.format(project.getUnitPriceMin())).append("〜").append(PriceFormatter.format(project.getUnitPriceMax())).append("\n");
        sb.append("- 必須スキル: ").append(mustIds.stream().map(id -> tagNameMap.getOrDefault(id, "")).collect(Collectors.joining(", "))).append("\n\n");

        sb.append("【要員情報】\n");
        sb.append("- イニシャル: ").append(bp.getInitialName() != null ? bp.getInitialName() : "").append("\n");
        sb.append("- 単価: ").append(PriceFormatter.format(bp.getUnitPrice())).append("\n");
        sb.append("- 保持スキル: ").append(bpSkills.stream().map(id -> tagNameMap.getOrDefault(id, "")).collect(Collectors.joining(", "))).append("\n\n");

        sb.append("【ルールベーススコア計算結果】\n");
        sb.append("- トータルスコア: ").append(score.getTotalScore()).append("\n\n");

        sb.append("出力は以下のJSONフォーマットのみを返してください。不要なテキストやマークダウンブロックは含めないでください。\n");
        sb.append("{\n");
        sb.append("  \"reason\": \"マッチしている具体的な理由(1〜2文)\",\n");
        sb.append("  \"sellingPoints\": \"要員のアピールポイント(1〜2文)\",\n");
        sb.append("  \"score\": ").append(score.getTotalScore()).append("\n");
        sb.append("}");
        return sb.toString();
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
