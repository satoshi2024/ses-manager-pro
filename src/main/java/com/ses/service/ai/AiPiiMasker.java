package com.ses.service.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * allowlist 外を落とし、残値へ mask を適用する。
 */
public final class AiPiiMasker {

    public static final Set<String> ALLOWED_SEND = Set.of(
            "engineer.experienceYears", "engineer.expectedUnitPrice", "engineer.availableDate",
            "engineer.status", "engineer.employmentType", "engineer.prefecture",
            "engineer.nearestStation", "engineer.railwayCompany", "engineer.initialName",
            "engineer.japaneseLevel",
            "engineerSkill.skillId", "engineerSkill.skillName", "engineerSkill.proficiency",
            "engineerSkill.experienceYears",
            "career.techStack", "career.role", "career.clientIndustry", "career.periodMonths",
            "project.unitPriceMin", "project.unitPriceMax", "project.workLocation",
            "project.remoteType", "project.startDate", "project.endDate", "project.requiredCount",
            "project.projectName",
            "projectSkill.skillId", "projectSkill.skillName", "projectSkill.isMust",
            "bp.experienceYears", "bp.unitPrice", "bp.availableFrom", "bp.skillNames",
            "bp.initialName", "bp.bpCompany",
            "ruleScore.total", "ruleScore.mustCoverage", "ruleScore.priceScore", "ruleScore.dateScore",
            "catalog.queryId", "catalog.catalogVersion", "summary.claimKeys");

    public static final Set<String> NEVER_SEND = Set.of(
            "engineer.fullName", "engineer.fullNameKana", "engineer.gender", "engineer.birthDate",
            "engineer.nationality", "engineer.phone", "engineer.photoUrl", "engineer.resumeSummary",
            "engineer.remarks", "career.description", "career.projectName", "career.periodFrom",
            "career.periodTo", "project.description", "project.remarks", "bp.remarks");

    private static final Set<String> ROLE_WORDS = Set.of(
            "エンジニア", "リーダー", "マネージャー", "ディレクター", "コンサルタント",
            "アーキテクト", "営業", "PM", "PL", "SE", "PG", "テスター", "デザイナー");

    private static final Pattern HTML = Pattern.compile("(?i)<[^>]+>");

    private AiPiiMasker() {
    }

    public static Map<String, Object> mask(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String id = entry.getKey();
            if (id == null || NEVER_SEND.contains(id) || !ALLOWED_SEND.contains(id)) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if ("project.workLocation".equals(id)) {
                String grain = WorkLocationNormalizer.normalize(String.valueOf(value));
                if (grain != null) {
                    out.put(id, grain);
                }
                continue;
            }
            if ("career.role".equals(id)) {
                out.put(id, maskRole(String.valueOf(value)));
                continue;
            }
            if (value instanceof String str && HTML.matcher(str).find()) {
                continue;
            }
            out.put(id, value);
        }
        return out;
    }

    /** management copilot summary 用の allowlist（G10 managementCopilot 契約）。 */
    public static Map<String, Object> maskCopilot(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String id = entry.getKey();
            if (id == null || !ALLOWED_SEND.contains(id)) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String str && HTML.matcher(str).find()) {
                continue;
            }
            out.put(id, value);
        }
        return out;
    }

    public static String sanitizeUntrusted(String raw) {
        if (raw == null) {
            return "";
        }
        String text = HTML.matcher(raw).replaceAll("");
        return text.replaceAll("\\[TASK:[^\\]]*]", "[TASK:REDACTED]");
    }

    public static String stripHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return HTML.matcher(raw).replaceAll("");
    }

    public static boolean containsCanary(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(AiPiiMasker::containsCanary);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(AiPiiMasker::containsCanary);
        }
        return String.valueOf(value).contains(AiGatewayRequest.CANARY);
    }

    static String maskRole(String role) {
        StringBuilder out = new StringBuilder();
        for (String token : role.split("[\\s　・/]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            if (token.matches("[\\p{Alnum}._\\-]+")
                    || ROLE_WORDS.contains(token)
                    || ROLE_WORDS.contains(token.toUpperCase(Locale.ROOT))) {
                out.append(token);
            } else {
                out.append("***");
            }
        }
        return out.toString();
    }
}
