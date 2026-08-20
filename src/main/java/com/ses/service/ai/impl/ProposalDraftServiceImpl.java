package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.ProposalDraftDto;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.AiAllowlistFields;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
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
    private final AiExecutionGateway aiExecutionGateway;
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

        // 単価は円のまま渡す（MatchScoreCalculator は円で採点する）。万円へ丸めて渡すと
        // 他のマッチング経路と採点が食い違い、同じ要員×案件で別のスコアが出てしまう。
        MatchScore score = MatchScoreCalculator.calculate(
                mustIds, niceIds, engSkillIds,
                project.getUnitPriceMin(), project.getUnitPriceMax(),
                engineer.getExpectedUnitPrice(), project.getStartDate(), engineer.getAvailableDate()
        );

        try {
            AiGatewayResult result = aiExecutionGateway.execute(AiGatewayRequest.builder()
                    .useCase(AiGatewayRequest.USE_PROPOSAL_DRAFT)
                    .taskMarker("[TASK:PROPOSAL_DRAFT]")
                    .trustedInstruction("""
                            あなたは優秀なSES営業担当です。ALLOWLIST_CONTEXT のみを根拠に、
                            提案メール本文・マッチ理由・セールスポイント・スコアをJSONで作成してください。
                            実名や連絡先は出力しないでください。要員名は initialName だけを使ってください。
                            HTMLは出力しないでください。
                            {"emailText":"...","matchReason":"...","sellingPoints":"...","matchScore":85}
                            """)
                    .allowlistedFields(AiAllowlistFields.merge(
                            AiAllowlistFields.engineer(engineer, engSkills),
                            AiAllowlistFields.project(project),
                            AiAllowlistFields.ruleScore(score)))
                    .persistRun(true)
                    .requireJson(true)
                    .build());
            ProposalDraftDto dto = parseAiResponse(result.getText());
            dto.setTraceId(result.getTraceId());
            dto.setRunId(result.getRunId());
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate proposal draft", e);
            throw BusinessException.of(500, "error.ai.unexpected");
        }
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
