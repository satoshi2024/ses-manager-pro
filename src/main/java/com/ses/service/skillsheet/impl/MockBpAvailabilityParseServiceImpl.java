package com.ses.service.skillsheet.impl;

import com.ses.dto.bpavailability.ParsedBpAvailabilityDto;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.service.skillsheet.BpAvailabilityParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")
public class MockBpAvailabilityParseServiceImpl implements BpAvailabilityParseService {

    private static final Pattern TECH_SKILL = Pattern.compile(
            "(?i)(Java|Python|PHP|Ruby|Go|C\\+\\+|TypeScript|JavaScript|React|Vue|Angular|AWS|Azure|GCP|Docker|Kubernetes|Spring Boot|Spring|MySQL|PostgreSQL|Oracle|MongoDB|C#|\\.NET)");

    @Override
    public ParsedBpAvailabilityDto parse(String extractedText) {
        log.debug("MockBpAvailabilityParseServiceImpl: モック解析を実行します（textLength={})", 
                  extractedText != null ? extractedText.length() : 0);

        ParsedBpAvailabilityDto dto = new ParsedBpAvailabilityDto();
        ReviewedBpAvailabilityDto availability = new ReviewedBpAvailabilityDto();

        availability.setInitialName("M.M");
        availability.setBpCompany("モックBP株式会社");
        availability.setUnitPrice(700000L);
        availability.setExperienceYears(5);
        availability.setRemarks("モック解析結果");

        List<String> skills = new ArrayList<>();
        if (extractedText != null) {
            Matcher skillMatcher = TECH_SKILL.matcher(extractedText);
            while (skillMatcher.find()) {
                String skillName = skillMatcher.group(1);
                boolean alreadyExists = skills.stream()
                        .anyMatch(s -> s.equalsIgnoreCase(skillName));
                if (!alreadyExists) {
                    skills.add(skillName);
                }
            }
        }
        availability.setSkills(skills);
        dto.setAvailability(availability);

        List<String> warnings = new ArrayList<>();
        warnings.add("モック解析のため、内容を必ず確認・修正してください。");
        dto.setWarnings(warnings);

        return dto;
    }
}
