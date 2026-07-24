package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PriceFormatter;
import com.ses.dto.ai.ProposalDraftDto;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.AiTextService;
import com.ses.service.ai.ProposalDraftService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ProjectSkill;
import com.ses.mapper.ProjectSkillMapper;
import com.ses.dto.ai.MatchScore;
import com.ses.service.ai.MatchScoreCalculator;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalDraftServiceImpl implements ProposalDraftService {

    private final EngineerMapper engineerMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ProjectMapper projectMapper;
    private final ProjectSkillMapper projectSkillMapper;
    private final DataScopeService dataScopeService;
    private final AiTextService aiTextService;
    private final ObjectMapper objectMapper;

    @Override
    public ProposalDraftDto generateDraft(Long engineerId, Long projectId) {
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedEngineer(engineerId);
            dataScopeService.assertAllowedProject(projectId);
        }

        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            throw BusinessException.of(404, "error.engineer.notFound");
        }

        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(404, "error.project.notFound");
        }

        List<EngineerSkillDetailDto> engSkills = engineerSkillMapper.selectDetailByEngineerId(engineerId);

        Set<Long> engSkillIds = engSkills.stream()
                .map(EngineerSkillDetailDto::getSkillId)
                .collect(Collectors.toSet());

        List<ProjectSkill> pSkills = projectSkillMapper.selectList(
                new LambdaQueryWrapper<ProjectSkill>().eq(ProjectSkill::getProjectId, projectId)
        );
        Set<Long> mustIds = pSkills.stream()
                .filter(s -> Integer.valueOf(1).equals(s.getIsMust()))
                .map(ProjectSkill::getSkillId)
                .collect(Collectors.toSet());
        Set<Long> niceIds = pSkills.stream()
                .filter(s -> Integer.valueOf(0).equals(s.getIsMust()))
                .map(ProjectSkill::getSkillId)
                .collect(Collectors.toSet());

        BigDecimal pMin = project.getUnitPriceMin() != null ? project.getUnitPriceMin().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;
        BigDecimal pMax = project.getUnitPriceMax() != null ? project.getUnitPriceMax().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;
        BigDecimal ePrice = engineer.getExpectedUnitPrice() != null ? engineer.getExpectedUnitPrice().divide(new BigDecimal("10000"), 0, java.math.RoundingMode.HALF_UP) : null;

        MatchScore score = MatchScoreCalculator.calculate(
                mustIds, niceIds, engSkillIds, pMin, pMax,
                ePrice, project.getStartDate(), engineer.getAvailableDate()
        );

        String prompt = buildPrompt(engineer, project, engSkills, score);

        try {
            String aiResponse = aiTextService.generate(prompt);
            return parseAiResponse(aiResponse);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate proposal draft", e);
            throw BusinessException.of(500, "error.ai.unexpected");
        }
    }

    private String buildPrompt(Engineer engineer, Project project, List<EngineerSkillDetailDto> engSkills, MatchScore score) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TASK:PROPOSAL_DRAFT]\n");
        sb.append("あなたは優秀なSES営業担当です。以下の情報を元に、お客様へ送付する提案メール本文、マッチ理由、セールスポイント、スコアをJSON形式で作成してください。\n\n");
        
        sb.append("【案件情報】\n");
        sb.append("- 案件名: ").append(project.getProjectName()).append("\n");
        sb.append("- 単価幅: ").append(PriceFormatter.format(project.getUnitPriceMin())).append("〜").append(PriceFormatter.format(project.getUnitPriceMax())).append("\n");
        sb.append("- リモート: ").append(project.getRemoteType() != null ? project.getRemoteType() : "未設定").append("\n\n");

        sb.append("【要員情報】\n");
        sb.append("- 氏名(イニシャル): ").append(engineer.getInitialName() != null ? engineer.getInitialName() : "").append("\n");
        sb.append("- 経験年数: ").append(engineer.getExperienceYears()).append("年\n");
        sb.append("- 希望単価: ").append(PriceFormatter.format(engineer.getExpectedUnitPrice())).append("\n");
        sb.append("- スキルサマリ: ").append(engSkills.stream().map(s -> s.getSkillName() + "(" + s.getExperienceYears() + "年)").collect(Collectors.joining(", "))).append("\n\n");
        
        sb.append("【ルールベーススコア計算結果】\n");
        sb.append("- トータルスコア: ").append(score.getTotalScore()).append("\n");
        sb.append("- 必須スキル充足率: ").append(score.getMustCoverage()).append("\n");
        sb.append("- 単価適合スコア: ").append(score.getPriceScore()).append("/20\n\n");

        sb.append("※注意: 個人情報保護のため、出力するメール本文や理由に実名や連絡先等を含めないでください。要員名は必ず上記のイニシャルを使用してください。\n\n");

        sb.append("出力は以下のJSONフォーマットのみを返してください。不要なテキストやマークダウンブロックは含めないでください。\n");
        sb.append("{\n");
        sb.append("  \"emailText\": \"お客様への提案メール本文(挨拶文や署名も含む)\",\n");
        sb.append("  \"matchReason\": \"案件と要員がマッチしている具体的な理由\",\n");
        sb.append("  \"sellingPoints\": \"要員のアピールポイント(長所)\",\n");
        sb.append("  \"matchScore\": 85\n");
        sb.append("}");
        return sb.toString();
    }

    private ProposalDraftDto parseAiResponse(String aiResponse) {
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
            
            JsonNode root = objectMapper.readTree(aiResponse);
            ProposalDraftDto dto = new ProposalDraftDto();
            
            if (root.has("emailText")) {
                dto.setEmailText(root.get("emailText").asText());
            }
            if (root.has("matchReason")) {
                dto.setMatchReason(root.get("matchReason").asText());
            }
            if (root.has("sellingPoints")) {
                dto.setSellingPoints(root.get("sellingPoints").asText());
            }
            if (root.has("matchScore")) {
                dto.setMatchScore(new BigDecimal(root.get("matchScore").asText()));
            }
            return dto;
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", aiResponse, e);
            throw BusinessException.of(500, "error.ai.parseError");
        }
    }
}
