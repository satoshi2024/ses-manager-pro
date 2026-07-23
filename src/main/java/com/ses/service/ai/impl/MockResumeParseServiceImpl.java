package com.ses.service.ai.impl;

import com.ses.dto.resume.ParsedResumeDto;
import com.ses.service.ai.ResumeParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * スキルシート解析のモック実装。
 * ai.provider が未設定または mock のときに使用（matchIfMissing=true）。
 * AI無効時でも「要確認」状態まで導ける。
 */
@Slf4j
@Service
@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")
public class MockResumeParseServiceImpl implements ResumeParseService {

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "[氏名者姓名]\\s*[::\uff1a]　?\\s*([\u4e00-\u9fff\u30a0-\u30ff\u3040-\u309f\uff00-\uffef]+)");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "([0-9]+)\\s*万円");
    private static final Pattern JAVA_SKILL = Pattern.compile(
            "\\b(Java|Python|PHP|Ruby|Go|C\\+\\+|TypeScript|JavaScript|React|Vue|Angular|AWS|Azure|GCP|Docker|Kubernetes|Spring|Spring Boot|MySQL|PostgreSQL|Oracle|MongoDB)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEARS_PATTERN = Pattern.compile(
            "([0-9]+)\\s*年");

    @Override
    public ParsedResumeDto parse(String extractedText) {
        log.debug("MockResumeParseServiceImpl: モック解析を実行します（textLength={})", 
                  extractedText != null ? extractedText.length() : 0);

        ParsedResumeDto dto = new ParsedResumeDto();
        ParsedResumeDto.EngineerPart engineer = new ParsedResumeDto.EngineerPart();

        // 氏名抽出
        if (extractedText != null) {
            Matcher nameMatcher = NAME_PATTERN.matcher(extractedText);
            if (nameMatcher.find()) {
                engineer.setFullName(nameMatcher.group(1));
            } else {
                engineer.setFullName("モック氏名");
            }

            // 希望単価抽出
            Matcher priceMatcher = PRICE_PATTERN.matcher(extractedText);
            if (priceMatcher.find()) {
                long price = Long.parseLong(priceMatcher.group(1)) * 10000L;
                engineer.setExpectedUnitPrice(BigDecimal.valueOf(price));
            }

            // 経験年数抽出
            Matcher yearsMatcher = YEARS_PATTERN.matcher(extractedText);
            if (yearsMatcher.find()) {
                try {
                    engineer.setExperienceYears(Integer.parseInt(yearsMatcher.group(1)));
                } catch (NumberFormatException ignored) {}
            }
        }

        dto.setEngineer(engineer);

        // スキル抽出
        List<ParsedResumeDto.SkillPart> skills = new ArrayList<>();
        if (extractedText != null) {
            Matcher skillMatcher = JAVA_SKILL.matcher(extractedText);
            while (skillMatcher.find()) {
                String skillName = skillMatcher.group(1);
                boolean alreadyExists = skills.stream()
                        .anyMatch(s -> s.getName().equalsIgnoreCase(skillName));
                if (!alreadyExists) {
                    ParsedResumeDto.SkillPart skill = new ParsedResumeDto.SkillPart();
                    skill.setName(skillName);
                    skill.setProficiency("中級");
                    skills.add(skill);
                }
            }
        }
        dto.setSkills(skills);
        dto.setCareers(new ArrayList<>());

        List<String> warnings = new ArrayList<>();
        warnings.add("モック解析のため、内容を必ず確認・修正してください。");
        dto.setWarnings(warnings);

        return dto;
    }
}
