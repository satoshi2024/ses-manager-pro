package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * NF-06本番admission承認設定（{@code app.import-center.admission.*}）の純粋評価器（Q-5）。
 *
 * <p>NF08外部送信承認（{@code AiExternalSendApproval}）と同じID形式・placeholder検出・有効期間・承認digestの考え方で、
 * 署名済みData Owner決定ID、承認tenant、有効期間、承認済みsource code一覧が全て有効で、かつ決定書に記載された
 * digest（{@code approval-digest}）と現行設定から再計算したdigestが一致する場合だけcompleteを返す。
 * 違反は固定コードだけで返し、設定値そのものをメッセージへ含めない。I/Oや状態を持たない。
 *
 * <p>承認の対象entity・schemaは初回リリースの{@code CUSTOMER}／{@code ses-import-v1}に固定し、digestにも含める。
 */
public final class ImportAdmissionApproval {

    /** 署名日・失効日はJST業務日で判定する。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Tokyo");
    /** 承認の最長有効期間（日）。年次の再承認を強制する。 */
    public static final int MAX_VALIDITY_DAYS = 366;
    /** 承認できるsource codeの最大件数。 */
    public static final int MAX_SOURCE_CODES = 50;
    /** 承認内容digestの正規形の版。正規形を変える場合は版を上げ、決定書のdigestも取り直す。 */
    public static final String DIGEST_FORMAT_VERSION = "nf06-import-admission-approval-v1";
    /** 承認対象entity（初回リリース）。 */
    public static final ImportEntity APPROVED_ENTITY = ImportEntity.CUSTOMER;
    /** 時間の経過だけで変わる違反（署名日前・失効）。prodでも起動を止めず取込だけを拒否する対象。 */
    public static final Set<String> TEMPORAL_VIOLATIONS = Set.of("signedOn.future", "expiresOn.expired");
    /** 承認digestの不一致（決定書に記載された内容と現行設定が食い違う）。 */
    public static final String VIOLATION_DIGEST_MISMATCH = "approvalDigest.mismatch";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{3,63}");
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    /** {@code ImportMetadataService}のsource code形式と同じ。比較は小文字化した値で行う。 */
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    /** 未署名の転記を検出するplaceholder断片（大文字比較）。 */
    private static final List<String> PLACEHOLDER_FRAGMENTS = List.of(
            "TBD", "TODO", "PENDING", "CHANGEME", "CHANGE-ME", "PLACEHOLDER", "UNSIGNED",
            "DUMMY", "SAMPLE", "EXAMPLE", "XXXX", "DRAFT", "UNKNOWN");
    private static final Set<String> PLACEHOLDER_VALUES = Set.of("NONE", "NULL", "N-A");

    private ImportAdmissionApproval() {
    }

    /** clockの現在時刻をJST業務日へ変換する。 */
    public static LocalDate businessDate(Clock clock) {
        return LocalDate.ofInstant((clock == null ? Clock.systemUTC() : clock).instant(), BUSINESS_ZONE);
    }

    /** いずれかの項目が設定されているか（部分設定の検出用）。 */
    public static boolean configured(ImportAdmissionApprovalProperties properties) {
        if (properties == null) return false;
        return !trim(properties.getDecisionId()).isEmpty() || !trim(properties.getTenantId()).isEmpty()
                || !trim(properties.getSignedOn()).isEmpty() || !trim(properties.getExpiresOn()).isEmpty()
                || !trim(properties.getApprovalDigest()).isEmpty()
                || (properties.getSourceCodes() != null
                && properties.getSourceCodes().stream().anyMatch(code -> !trim(code).isEmpty()));
    }

    /** 承認設定を評価する。todayはJST業務日（署名日・失効日の判定に使う）。 */
    public static Evaluation evaluate(ImportAdmissionApprovalProperties properties, LocalDate today) {
        if (properties == null || today == null) {
            return new Evaluation(false, List.of("approval.unavailable"), null);
        }
        List<String> violations = new ArrayList<>();
        Content content = readContent(properties, violations);
        if (content.signedOn() != null && content.signedOn().isAfter(today)) {
            violations.add("signedOn.future");
        }
        if (content.expiresOn() != null && !today.isBefore(content.expiresOn())) {
            violations.add("expiresOn.expired");
        }
        String configuredDigest = lower(properties.getApprovalDigest());
        if (configuredDigest.isEmpty()) {
            violations.add("approvalDigest.missing");
        } else if (!DIGEST.matcher(configuredDigest).matches()) {
            violations.add("approvalDigest.format");
        } else if (content.complete() && !configuredDigest.equals(digestOf(content))) {
            violations.add(VIOLATION_DIGEST_MISMATCH);
        }
        if (!violations.isEmpty()) {
            return new Evaluation(false, violations, null);
        }
        return new Evaluation(true, List.of(), new Snapshot(content.decisionId(), content.tenantId(),
                content.signedOn(), content.expiresOn(), content.sourceCodes(), configuredDigest));
    }

    /** 違反のうち、時間の経過だけが理由ではないもの（prodで起動を止める対象）。 */
    public static List<String> structuralViolations(Evaluation evaluation) {
        if (evaluation == null) return List.of("approval.unavailable");
        return evaluation.violations().stream().filter(code -> !TEMPORAL_VIOLATIONS.contains(code)).toList();
    }

    /**
     * 現行設定の承認内容から正規形digest（SHA-256、小文字hex）を算出する。内容が不完全・形式不正ならnull。
     * 署名者が決定書へ記載し、運用者が{@code app.import-center.admission.approval-digest}へ転記する値である。
     */
    public static String computeDigest(ImportAdmissionApprovalProperties properties) {
        if (properties == null) return null;
        Content content = readContent(properties, new ArrayList<>());
        return content.complete() ? digestOf(content) : null;
    }

    /**
     * digest対象の正規形。1行目は版、以降は「key=value」を固定順でLF終端する（最終行もLFで終える）。
     * IDとtenantは前後空白を除いた値、source codeは小文字化して昇順のカンマ区切りとする。
     */
    static String canonicalContent(Content content) {
        StringBuilder sb = new StringBuilder();
        line(sb, null, DIGEST_FORMAT_VERSION);
        line(sb, "decision-id", content.decisionId());
        line(sb, "tenant-id", content.tenantId());
        line(sb, "signed-on", content.signedOn().toString());
        line(sb, "expires-on", content.expiresOn().toString());
        line(sb, "entity", APPROVED_ENTITY.name());
        line(sb, "schema-version", SesImportV1Schema.VERSION);
        line(sb, "source-codes", String.join(",", new TreeSet<>(content.sourceCodes())));
        return sb.toString();
    }

    /** source codeを承認一覧と比較できる形（小文字）へ正規化する。形式外はnull。 */
    static String normalizeSourceCode(String raw) {
        String value = trim(raw);
        if (!SOURCE_CODE.matcher(value).matches()) return null;
        return value.toLowerCase(Locale.ROOT);
    }

    private static Content readContent(ImportAdmissionApprovalProperties properties, List<String> violations) {
        String decisionId = requireId(properties.getDecisionId(), "decisionId", violations);
        String tenantId = trim(properties.getTenantId());
        if (tenantId.isEmpty()) {
            violations.add("tenantId.missing");
            tenantId = null;
        } else if (!TENANT.matcher(tenantId).matches()) {
            violations.add("tenantId.format");
            tenantId = null;
        }
        LocalDate signedOn = parseDate(properties.getSignedOn(), "signedOn", violations);
        LocalDate expiresOn = parseDate(properties.getExpiresOn(), "expiresOn", violations);
        boolean datesValid = signedOn != null && expiresOn != null;
        if (datesValid) {
            if (!signedOn.isBefore(expiresOn)) {
                violations.add("expiresOn.notAfterSignedOn");
                datesValid = false;
            } else if (signedOn.plusDays(MAX_VALIDITY_DAYS).isBefore(expiresOn)) {
                violations.add("expiresOn.tooLong");
                datesValid = false;
            }
        }
        TreeSet<String> sourceCodes = new TreeSet<>();
        boolean sourceFormatValid = true;
        if (properties.getSourceCodes() != null) {
            for (String raw : properties.getSourceCodes()) {
                if (trim(raw).isEmpty()) continue;
                String normalized = normalizeSourceCode(raw);
                if (normalized == null) {
                    sourceFormatValid = false;
                    continue;
                }
                sourceCodes.add(normalized);
            }
        }
        boolean sourcesValid = false;
        if (!sourceFormatValid) {
            violations.add("sourceCodes.format");
        } else if (sourceCodes.isEmpty()) {
            violations.add("sourceCodes.missing");
        } else if (sourceCodes.size() > MAX_SOURCE_CODES) {
            violations.add("sourceCodes.tooMany");
        } else {
            sourcesValid = true;
        }
        boolean complete = decisionId != null && tenantId != null && datesValid && sourcesValid;
        return new Content(complete, decisionId, tenantId, signedOn, expiresOn, Set.copyOf(sourceCodes));
    }

    private static String digestOf(Content content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalContent(content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (key != null) {
            sb.append(key).append('=');
        }
        sb.append(value).append('\n');
    }

    private static String requireId(String raw, String field, List<String> violations) {
        String value = trim(raw);
        if (value.isEmpty()) {
            violations.add(field + ".missing");
            return null;
        }
        if (!ID.matcher(value).matches()) {
            violations.add(field + ".format");
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (PLACEHOLDER_VALUES.contains(upper) || PLACEHOLDER_FRAGMENTS.stream().anyMatch(upper::contains)) {
            violations.add(field + ".placeholder");
            return null;
        }
        return value;
    }

    private static LocalDate parseDate(String raw, String field, List<String> violations) {
        String value = trim(raw);
        if (value.isEmpty()) {
            violations.add(field + ".missing");
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            violations.add(field + ".format");
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    /** digest対象の正規化済み承認内容。completeはdigestを算出できる（全項目が有効）ことを表す。 */
    record Content(boolean complete, String decisionId, String tenantId, LocalDate signedOn, LocalDate expiresOn,
                   Set<String> sourceCodes) {
    }

    /** 評価結果。completeの場合だけsnapshotを持つ。violationsは固定コードのみ。 */
    public record Evaluation(boolean complete, List<String> violations, Snapshot snapshot) {
        public Evaluation {
            violations = violations == null ? List.of() : List.copyOf(violations);
            if (complete != (snapshot != null) || (complete && !violations.isEmpty())) {
                throw new IllegalArgumentException("取込admission承認の評価結果が不整合です");
            }
        }
    }

    /** 承認済みadmissionの束縛値。秘密値を含まない。 */
    public record Snapshot(String decisionId, String tenantId, LocalDate signedOn, LocalDate expiresOn,
                           Set<String> sourceCodes, String approvalDigest) {
        public Snapshot {
            sourceCodes = sourceCodes == null ? Set.of() : Set.copyOf(sourceCodes);
        }

        /** source code（大文字小文字を問わない）が承認一覧に含まれるか。 */
        public boolean approvesSourceCode(String sourceCode) {
            String normalized = normalizeSourceCode(sourceCode);
            return normalized != null && sourceCodes.contains(normalized);
        }
    }
}
