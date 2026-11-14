package com.ses.service.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
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

    /**
     * 管理Copilotの外部送信で送ってよい最小field（G10 managementCopilot契約）。
     * {@link #maskCopilot} の呼出元（CopilotSummaryService）が渡す3項目に限り、氏名・会社名・案件名等を含めない。
     * 承認設定の data-categories はこの部分集合でなければ承認しない。
     */
    public static final Set<String> COPILOT_SEND_FIELDS = Set.of(
            "catalog.queryId", "catalog.catalogVersion", "summary.claimKeys");

    /** 未maskのPII種別（固定コード）。検出結果は種別だけを返し、値そのものは返さない。 */
    public static final String PII_EMAIL = "EMAIL";
    public static final String PII_PHONE = "PHONE";
    public static final String PII_POSTAL_CODE = "POSTAL_CODE";
    public static final String PII_PERSON_HONORIFIC = "PERSON_HONORIFIC";
    public static final String PII_CORPORATE_NAME = "CORPORATE_NAME";
    public static final String PII_WITHHELD_FIELD_VALUE = "WITHHELD_FIELD_VALUE";

    private static final Set<String> ROLE_WORDS = Set.of(
            "エンジニア", "リーダー", "マネージャー", "ディレクター", "コンサルタント",
            "アーキテクト", "営業", "PM", "PL", "SE", "PG", "テスター", "デザイナー");

    private static final Pattern HTML = Pattern.compile("(?i)<[^>]+>");

    /** NFKC正規化後の連絡先・氏名・会社名の検出pattern（外部送信の最終gate用。誤検出は拒否側に倒す）。 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final String PHONE_SEPARATOR = "[-\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212\\u30FC ()]?";
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+81" + PHONE_SEPARATOR + "|0)[0-9]{1,4}"
            + PHONE_SEPARATOR + "[0-9]{1,4}" + PHONE_SEPARATOR + "[0-9]{3,4}(?![0-9])");
    private static final Pattern POSTAL_CODE = Pattern.compile("\\u3012 ?[0-9]{3}-?[0-9]{4}(?![0-9])");
    private static final Pattern HONORIFIC_JA = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\u3005\\u3006\\u30F6\\u30FC]{1,12}"
                    + "(?:\\u69D8|\\u3055\\u3093|\\u6C0F(?!\\u540D)|\\u6BBF|\\u304F\\u3093|\\u3061\\u3083\\u3093)");
    private static final Pattern HONORIFIC_EN = Pattern.compile("\\b(?:Mr|Mrs|Ms|Miss|Dr)\\.?\\s+[A-Z][a-z]+");
    private static final Pattern CORPORATE = Pattern.compile(
            "\\u682A\\u5F0F\\u4F1A\\u793E|\\u6709\\u9650\\u4F1A\\u793E|\\u5408\\u540C\\u4F1A\\u793E"
                    + "|\\u5408\\u8CC7\\u4F1A\\u793E|\\u5408\\u540D\\u4F1A\\u793E|\\u793E\\u56E3\\u6CD5\\u4EBA"
                    + "|\\u8CA1\\u56E3\\u6CD5\\u4EBA|\\(\\u682A\\)|\\(\\u6709\\)|\\(\\u5408\\)"
                    + "|\\b(?:Inc\\.?|Ltd\\.?|LLC|Corp\\.|Co\\.,? ?Ltd\\.?)(?![A-Za-z])");
    /** 個人を指さない一般的な呼称（お客様・皆様・皆さん）。これで終わる敬称一致は人名とみなさない。 */
    private static final List<String> GENERIC_HONORIFIC_SUFFIXES = List.of(
            "\u304A\u5BA2\u69D8", "\u7686\u69D8", "\u7686\u3055\u3093");
    /** 送信対象から外したfield値の照合に使う最小長（1文字の値は誤検出が多いため照合しない）。 */
    private static final int MIN_WITHHELD_VALUE_LENGTH = 2;

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

    /**
     * 管理Copilotの送信契約外として送信しなかったfield（NEVER_SEND・allow-list外・legacy allow-listの氏名/会社名等）を返す。
     * 外部送信の最終gateで、これらの値がuntrusted textや送信field値へ紛れ込んでいないかの照合に使う。
     * 契約内のfield（{@link #COPILOT_SEND_FIELDS}。HTMLを含むため送らなかった値を含む）はPIIの分類ではないため含めない。
     */
    public static Map<String, Object> withheldFields(Map<String, Object> input, Collection<String> sentKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key != null && entry.getValue() != null && !COPILOT_SEND_FIELDS.contains(key)
                    && (sentKeys == null || !sentKeys.contains(key))) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    /**
     * 未maskのPIIを検出し、種別の固定コード（{@code PII_*}）だけを昇順で返す。値そのものは返さない。
     * <p>対象はNFKC正規化後の文字列（Map/Listは要素を再帰的に検査）。検出するのは、連絡先（mail・電話・〒付き郵便番号）、
     * 敬称付きの人名、法人格付きの会社名、および送信対象から外したfield値（氏名・会社名・電話等）の再出現である。
     * 伏せ字（例: {@code ***}、イニシャル）は検出しない。外部送信の最終gate用であり、誤検出は拒否側に倒す。</p>
     */
    public static List<String> findUnmaskedPii(Object value, Map<String, Object> withheld) {
        Set<String> found = new TreeSet<>();
        List<String> withheldValues = normalizedWithheldValues(withheld);
        collectPii(value, withheldValues, found, 0);
        return List.copyOf(found);
    }

    private static void collectPii(Object value, List<String> withheldValues, Set<String> found, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectPii(v, withheldValues, found, depth + 1));
            return;
        }
        if (value instanceof Collection<?> list) {
            list.forEach(v -> collectPii(v, withheldValues, found, depth + 1));
            return;
        }
        if (!(value instanceof CharSequence)) {
            return;
        }
        String text = Normalizer.normalize(value.toString(), Normalizer.Form.NFKC);
        if (text.isBlank()) {
            return;
        }
        if (EMAIL.matcher(text).find()) {
            found.add(PII_EMAIL);
        }
        if (PHONE.matcher(text).find()) {
            found.add(PII_PHONE);
        }
        if (POSTAL_CODE.matcher(text).find()) {
            found.add(PII_POSTAL_CODE);
        }
        if (CORPORATE.matcher(text).find()) {
            found.add(PII_CORPORATE_NAME);
        }
        if (HONORIFIC_EN.matcher(text).find() || containsPersonalHonorific(text)) {
            found.add(PII_PERSON_HONORIFIC);
        }
        String compact = compact(text);
        for (String withheldValue : withheldValues) {
            if (compact.contains(withheldValue)) {
                found.add(PII_WITHHELD_FIELD_VALUE);
                break;
            }
        }
    }

    private static boolean containsPersonalHonorific(String text) {
        Matcher matcher = HONORIFIC_JA.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (GENERIC_HONORIFIC_SUFFIXES.stream().noneMatch(token::endsWith)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizedWithheldValues(Map<String, Object> withheld) {
        List<String> values = new ArrayList<>();
        if (withheld == null) {
            return values;
        }
        for (Object value : withheld.values()) {
            collectWithheld(value, values, 0);
        }
        return values;
    }

    private static void collectWithheld(Object value, List<String> values, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectWithheld(v, values, depth + 1));
            return;
        }
        if (value instanceof Collection<?> list) {
            list.forEach(v -> collectWithheld(v, values, depth + 1));
            return;
        }
        if (value instanceof CharSequence) {
            // HTMLを含むため送信しなかった値も、HTML除去後の本文で照合する。
            String compact = compact(Normalizer.normalize(stripHtml(value.toString()), Normalizer.Form.NFKC));
            if (compact.codePointCount(0, compact.length()) >= MIN_WITHHELD_VALUE_LENGTH) {
                values.add(compact);
            }
        }
    }

    /** 照合用の正規化（空白除去・小文字化）。NFKC後の文字列を渡す。 */
    private static String compact(String normalized) {
        return normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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
