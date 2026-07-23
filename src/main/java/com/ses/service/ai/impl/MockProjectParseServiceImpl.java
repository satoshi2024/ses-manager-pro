package com.ses.service.ai.impl;

import com.ses.dto.projectingestion.ParsedProjectDto;
import com.ses.service.ai.ProjectParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 案件メール解析のモック実装。
 * ai.provider が未設定または mock のときに使用（@ConditionalOnExpression）。
 * AI無効時でも「要確認」状態まで導ける。
 */
@Slf4j
@Service
@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")
public class MockProjectParseServiceImpl implements ProjectParseService {

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "【?案件名?】?\\s*[::\uff1a]　?\\s*([^\n\r]+)");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "([0-9]+)\\s*万円");
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "【?勤務地?】?\\s*[::\uff1a]　?\\s*([^\n\r]+)");
    private static final Pattern TECH_SKILL = Pattern.compile(
            "(?i)(Java|Python|PHP|Ruby|Go|C\\+\\+|TypeScript|JavaScript|React|Vue|Angular|AWS|Azure|GCP|Docker|Kubernetes|Spring Boot|Spring|MySQL|PostgreSQL|Oracle|MongoDB|C#|\\.NET)");

    @Override
    public ParsedProjectDto parse(String extractedText) {
        log.debug("MockProjectParseServiceImpl: モック解析を実行します（textLength={})", 
                  extractedText != null ? extractedText.length() : 0);

        ParsedProjectDto dto = new ParsedProjectDto();
        ParsedProjectDto.ProjectPart project = new ParsedProjectDto.ProjectPart();

        if (extractedText != null) {
            // 案件名抽出
            Matcher nameMatcher = NAME_PATTERN.matcher(extractedText);
            if (nameMatcher.find()) {
                project.setName(nameMatcher.group(1).trim());
            } else {
                project.setName("モック案件名");
            }

            // 単価抽出（最大値を上限に、最小値を下限に入れるような雑な処理）
            Matcher priceMatcher = PRICE_PATTERN.matcher(extractedText);
            if (priceMatcher.find()) {
                long price = Long.parseLong(priceMatcher.group(1)) * 10000L;
                project.setMaxUnitPrice(BigDecimal.valueOf(price));
                project.setMinUnitPrice(BigDecimal.valueOf(price));
            }

            // 勤務地抽出
            Matcher locMatcher = LOCATION_PATTERN.matcher(extractedText);
            if (locMatcher.find()) {
                project.setLocation(locMatcher.group(1).trim());
            }
        }

        dto.setProject(project);

        // スキル抽出
        List<ParsedProjectDto.SkillPart> skills = new ArrayList<>();
        if (extractedText != null) {
            Matcher skillMatcher = TECH_SKILL.matcher(extractedText);
            while (skillMatcher.find()) {
                String skillName = skillMatcher.group(1);
                boolean alreadyExists = skills.stream()
                        .anyMatch(s -> s.getName().equalsIgnoreCase(skillName));
                if (!alreadyExists) {
                    ParsedProjectDto.SkillPart skill = new ParsedProjectDto.SkillPart();
                    skill.setName(skillName);
                    skills.add(skill);
                }
            }
        }
        dto.setSkills(skills);

        List<String> warnings = new ArrayList<>();
        warnings.add("モック解析のため、内容を必ず確認・修正してください。");
        dto.setWarnings(warnings);

        return dto;
    }
}
