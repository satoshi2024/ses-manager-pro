package com.ses.service.ai;

import com.ses.dto.ai.MatchScore;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;
import com.ses.entity.Project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * G10 allowlist の送信fieldだけを組み立てる。内部ID（engineer/project/bp）は入れない。
 */
public final class AiAllowlistFields {

    private AiAllowlistFields() {
    }

    public static Map<String, Object> engineer(Engineer engineer, List<EngineerSkillDetailDto> skills) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (engineer == null) {
            return fields;
        }
        put(fields, "engineer.initialName", engineer.getInitialName());
        put(fields, "engineer.experienceYears", engineer.getExperienceYears());
        put(fields, "engineer.expectedUnitPrice", engineer.getExpectedUnitPrice());
        put(fields, "engineer.availableDate", engineer.getAvailableDate());
        put(fields, "engineer.status", engineer.getStatus());
        put(fields, "engineer.employmentType", engineer.getEmploymentType());
        put(fields, "engineer.prefecture", engineer.getPrefecture());
        put(fields, "engineer.nearestStation", engineer.getNearestStation());
        put(fields, "engineer.railwayCompany", engineer.getRailwayCompany());
        put(fields, "engineer.japaneseLevel", engineer.getJapaneseLevel());
        if (skills != null && !skills.isEmpty()) {
            fields.put("engineerSkill.skillName", skills.stream()
                    .map(EngineerSkillDetailDto::getSkillName)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.joining(",")));
        }
        return fields;
    }

    public static Map<String, Object> project(Project project) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (project == null) {
            return fields;
        }
        put(fields, "project.projectName", project.getProjectName());
        put(fields, "project.unitPriceMin", project.getUnitPriceMin());
        put(fields, "project.unitPriceMax", project.getUnitPriceMax());
        put(fields, "project.workLocation", project.getWorkLocation());
        put(fields, "project.remoteType", project.getRemoteType());
        put(fields, "project.startDate", project.getStartDate());
        put(fields, "project.endDate", project.getEndDate());
        put(fields, "project.requiredCount", project.getRequiredCount());
        return fields;
    }

    public static Map<String, Object> bp(BpAvailability bp, String skillNames) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (bp == null) {
            return fields;
        }
        put(fields, "bp.initialName", bp.getInitialName());
        put(fields, "bp.bpCompany", bp.getBpCompany());
        put(fields, "bp.experienceYears", bp.getExperienceYears());
        put(fields, "bp.unitPrice", bp.getUnitPrice());
        put(fields, "bp.availableFrom", bp.getAvailableFrom());
        put(fields, "bp.skillNames", skillNames);
        return fields;
    }

    public static Map<String, Object> ruleScore(MatchScore score) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (score == null) {
            return fields;
        }
        fields.put("ruleScore.total", score.getTotalScore());
        fields.put("ruleScore.mustCoverage", score.getMustCoverage());
        fields.put("ruleScore.priceScore", score.getPriceScore());
        fields.put("ruleScore.dateScore", score.getDateScore());
        return fields;
    }

    @SafeVarargs
    public static Map<String, Object> merge(Map<String, Object>... parts) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (parts == null) {
            return out;
        }
        for (Map<String, Object> part : parts) {
            if (part != null) {
                out.putAll(part);
            }
        }
        return out;
    }

    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String str && str.isBlank()) {
            return;
        }
        fields.put(key, value);
    }
}
