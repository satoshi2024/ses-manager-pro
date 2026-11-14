package com.ses.privacy.production;

import com.ses.privacy.production.PrivacyAccessGuard.Capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * NF07 公開スイッチの決定ID束縛（{@code privacy.approval.*}）の純粋評価器。production-bridge-phased-plan.md §3.1.1・§5.1・§5.2。
 *
 * <p>NF08 外部送信承認（{@code AiExternalSendApproval}）・NF06 取込admission承認（{@code ImportAdmissionApproval}）と同じ
 * 規則で判定する: 決定IDの形式、placeholder 断片（{@code TBD}・{@code PENDING}・{@code UNKNOWN} 等）の拒否、項目間の重複拒否、
 * 署名日が未来なら拒否、失効日以降は拒否、有効期間は最長366日、承認内容の正規形 digest（SHA-256）が決定書に記載された
 * digest（{@code approval-digest}）と一致すること。規則の一致は {@code PrivacyDecisionBindingTest} が NF08 との比較で固定する。
 *
 * <p>capability ごとに必要な決定は {@link #requiredDecisions} が固定する（§5.1）: {@code retention} は D-01・D-09、
 * {@code dsar} は D-01・D-06・D-09。digest の対象は、承認 tenant、承認 capability、承認 capability が必要とする決定
 * （ID・署名日・失効日）である。設定が不完全・形式不正・digest 不一致なら「未承認」（complete=false）とする。
 * 違反は固定コードだけで返し、設定値そのものを含めない。I/O や状態を持たない。
 */
public final class PrivacyDecisionBinding {

    /** 署名日・失効日はJST業務日で判定する。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Tokyo");
    /** 決定の最長有効期間（日）。年次の再承認を強制する（NF08・NF06 と同じ）。 */
    public static final int MAX_VALIDITY_DAYS = 366;
    /** 束縛内容digestの正規形の版。正規形を変える場合は版を上げ、決定書のdigestも取り直す。 */
    public static final String DIGEST_FORMAT_VERSION = "nf07-privacy-decision-binding-v1";
    /** 承認digestの不一致（決定書に記載された内容と現行設定が食い違う）。 */
    public static final String VIOLATION_DIGEST_MISMATCH = "approvalDigest.mismatch";
    /** 承認tenantが権限評価の設定tenantと一致しない。 */
    public static final String VIOLATION_TENANT_MISMATCH = "tenantId.mismatch";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{3,63}");
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    /** 非ASCIIの同形文字を小文字化で受理しないよう、正規化前にASCIIへ限定する。 */
    private static final Pattern CAPABILITY = Pattern.compile("[A-Za-z_-]{1,32}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    /** 未署名の転記を検出するplaceholder断片（大文字比較）。NF08・NF06 と同じ一覧。 */
    private static final List<String> PLACEHOLDER_FRAGMENTS = List.of(
            "TBD", "TODO", "PENDING", "CHANGEME", "CHANGE-ME", "PLACEHOLDER", "UNSIGNED",
            "DUMMY", "SAMPLE", "EXAMPLE", "XXXX", "DRAFT", "UNKNOWN");
    private static final Set<String> PLACEHOLDER_VALUES = Set.of("NONE", "NULL", "N-A");

    /** capability ごとに必要な決定（§5.1）。 */
    private static final Map<Capability, Set<Decision>> REQUIRED_DECISIONS;

    static {
        Map<Capability, Set<Decision>> required = new EnumMap<>(Capability.class);
        required.put(Capability.RETENTION, Collections.unmodifiableSet(EnumSet.of(Decision.D01, Decision.D09)));
        required.put(Capability.DSAR,
                Collections.unmodifiableSet(EnumSet.of(Decision.D01, Decision.D06, Decision.D09)));
        REQUIRED_DECISIONS = Collections.unmodifiableMap(required);
    }

    private PrivacyDecisionBinding() {
    }

    /** NF07 が束縛する署名済み決定（prod-wiring-decision-contract.md の D-xx）。 */
    public enum Decision {
        /** 正式 Privacy Owner。 */
        D01("d01"),
        /** DSAR SOP。 */
        D06("d06"),
        /** 本番 flag 開放。 */
        D09("d09");

        private final String key;

        Decision(String key) {
            this.key = key;
        }

        /** 設定キー・違反コード・正規形で使う識別子（{@code d01} 等）。 */
        public String key() {
            return key;
        }
    }

    /** clockの現在時刻をJST業務日へ変換する。 */
    public static LocalDate businessDate(Clock clock) {
        return LocalDate.ofInstant((clock == null ? Clock.systemUTC() : clock).instant(), BUSINESS_ZONE);
    }

    /** capability の設定・正規形での識別子（{@code retention} / {@code dsar}）。 */
    public static String capabilityKey(Capability capability) {
        return capability.name().toLowerCase(Locale.ROOT);
    }

    /** capability を開くのに必要な決定（変更不可）。 */
    public static Set<Decision> requiredDecisions(Capability capability) {
        return capability == null ? Set.of() : REQUIRED_DECISIONS.get(capability);
    }

    /** いずれかの項目が設定されているか（部分設定の検出用）。 */
    public static boolean configured(PrivacyApprovalProperties properties) {
        if (properties == null) {
            return false;
        }
        if (!trim(properties.getTenantId()).isEmpty() || !trim(properties.getApprovalDigest()).isEmpty()
                || (properties.getCapabilities() != null
                && properties.getCapabilities().stream().anyMatch(value -> !trim(value).isEmpty()))) {
            return true;
        }
        for (Decision decision : Decision.values()) {
            PrivacyApprovalProperties.DecisionReference reference = properties.reference(decision);
            if (reference != null && (!trim(reference.getDecisionId()).isEmpty()
                    || !trim(reference.getSignedOn()).isEmpty() || !trim(reference.getExpiresOn()).isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 束縛を評価する。
     *
     * @param properties       {@code privacy.approval.*}
     * @param enabled          ON の capability。全て承認 capability に含まれていなければ拒否する
     * @param expectedTenantId 権限評価に使う設定tenant。承認tenantと完全一致しなければ拒否する
     * @param today            JST業務日（署名日・失効日の判定に使う）
     */
    public static Evaluation evaluate(PrivacyApprovalProperties properties, Set<Capability> enabled,
                                      String expectedTenantId, LocalDate today) {
        if (properties == null || enabled == null || today == null) {
            return new Evaluation(false, List.of("approval.unavailable"), null);
        }
        List<String> violations = new ArrayList<>();
        Content content = readContent(properties, violations);

        if (content.tenantId() != null && !content.tenantId().equals(trim(expectedTenantId))) {
            violations.add(VIOLATION_TENANT_MISMATCH);
        }
        if (content.capabilitiesValid()) {
            for (Capability capability : withoutNull(enabled)) {
                if (!content.capabilities().contains(capability)) {
                    violations.add("capabilities." + capabilityKey(capability) + ".notApproved");
                }
            }
        }
        for (Map.Entry<Decision, DecisionContent> entry : content.decisions().entrySet()) {
            DecisionContent decision = entry.getValue();
            String prefix = entry.getKey().key();
            if (decision.signedOn() != null && decision.signedOn().isAfter(today)) {
                violations.add(prefix + ".signedOn.future");
            }
            if (decision.expiresOn() != null && !today.isBefore(decision.expiresOn())) {
                violations.add(prefix + ".expiresOn.expired");
            }
        }

        // 決定書に記載されたdigestと束縛内容を照合する。失効等の時間要因に関係なく内容の食い違いを検出する。
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
        Map<Decision, String> decisionIds = new EnumMap<>(Decision.class);
        content.decisions().forEach((decision, value) -> decisionIds.put(decision, value.decisionId()));
        return new Evaluation(true, List.of(), new Snapshot(content.tenantId(), content.capabilities(),
                decisionIds, configuredDigest));
    }

    /**
     * 現行設定の束縛内容から正規形digest（SHA-256、小文字hex）を算出する。内容が不完全・形式不正ならnull。
     * 署名者が決定書（D-09）へ記載し、運用者が {@code privacy.approval.approval-digest} へ転記する値である。
     * 正規形は {@link #canonicalContent} を参照。
     */
    public static String computeDigest(PrivacyApprovalProperties properties) {
        if (properties == null) {
            return null;
        }
        Content content = readContent(properties, new ArrayList<>());
        return content.complete() ? digestOf(content) : null;
    }

    /**
     * digest対象の正規形。1行目は版、以降は「key=value」を固定順でLF終端する（最終行もLFで終える）。
     * tenantと決定IDは前後空白を除いた値、capability は小文字で昇順のカンマ区切り、決定は {@link Decision} の宣言順に
     * 承認 capability が必要とするものだけを並べる（{@code d01.decision-id} / {@code d01.signed-on} / {@code d01.expires-on}）。
     */
    static String canonicalContent(Content content) {
        StringBuilder sb = new StringBuilder();
        line(sb, null, DIGEST_FORMAT_VERSION);
        line(sb, "tenant-id", content.tenantId());
        TreeSet<String> capabilityKeys = new TreeSet<>();
        content.capabilities().forEach(capability -> capabilityKeys.add(capabilityKey(capability)));
        line(sb, "capabilities", String.join(",", capabilityKeys));
        for (Map.Entry<Decision, DecisionContent> entry : content.decisions().entrySet()) {
            String prefix = entry.getKey().key();
            DecisionContent decision = entry.getValue();
            line(sb, prefix + ".decision-id", decision.decisionId());
            line(sb, prefix + ".signed-on", decision.signedOn().toString());
            line(sb, prefix + ".expires-on", decision.expiresOn().toString());
        }
        return sb.toString();
    }

    /** digest以外の束縛内容を読み、正規化値と違反を返す。 */
    private static Content readContent(PrivacyApprovalProperties properties, List<String> violations) {
        String tenantId = trim(properties.getTenantId());
        if (tenantId.isEmpty()) {
            violations.add("tenantId.missing");
            tenantId = null;
        } else if (!TENANT.matcher(tenantId).matches()) {
            violations.add("tenantId.format");
            tenantId = null;
        }

        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        boolean capabilityFormatValid = true;
        boolean capabilityKnown = true;
        if (properties.getCapabilities() != null) {
            for (String raw : properties.getCapabilities()) {
                String trimmed = trim(raw);
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!CAPABILITY.matcher(trimmed).matches()) {
                    capabilityFormatValid = false;
                    continue;
                }
                Capability capability = capabilityOf(trimmed.toLowerCase(Locale.ROOT));
                if (capability == null) {
                    capabilityKnown = false;
                    continue;
                }
                capabilities.add(capability);
            }
        }
        boolean capabilitiesValid = false;
        if (!capabilityFormatValid) {
            violations.add("capabilities.format");
        } else if (!capabilityKnown) {
            violations.add("capabilities.unsupported");
        } else if (capabilities.isEmpty()) {
            violations.add("capabilities.missing");
        } else {
            capabilitiesValid = true;
        }

        // 承認 capability が必要とする決定だけを読む（承認していない capability の決定は束縛しない）。
        Map<Decision, DecisionContent> decisions = new EnumMap<>(Decision.class);
        boolean decisionsValid = capabilitiesValid;
        if (capabilitiesValid) {
            Set<Decision> required = EnumSet.noneOf(Decision.class);
            capabilities.forEach(capability -> required.addAll(requiredDecisions(capability)));
            for (Decision decision : required) {
                DecisionContent value = readDecision(decision, properties.reference(decision), violations);
                decisions.put(decision, value);
                decisionsValid &= value.valid();
            }
            if (decisionsValid) {
                Set<String> distinct = new HashSet<>();
                for (DecisionContent value : decisions.values()) {
                    distinct.add(upper(value.decisionId()));
                }
                if (distinct.size() != decisions.size()) {
                    // 一つのIDを複数の決定へ転記した設定は、個別の署名を証明しない。
                    violations.add("decisionIds.notDistinct");
                    decisionsValid = false;
                }
            }
        }

        boolean complete = tenantId != null && capabilitiesValid && decisionsValid;
        return new Content(complete, tenantId, capabilitiesValid,
                Collections.unmodifiableSet(capabilities), Collections.unmodifiableMap(decisions));
    }

    private static DecisionContent readDecision(Decision decision, PrivacyApprovalProperties.DecisionReference reference,
                                                List<String> violations) {
        String prefix = decision.key();
        String decisionId = requireId(reference == null ? null : reference.getDecisionId(),
                prefix + ".decisionId", violations);
        LocalDate signedOn = parseDate(reference == null ? null : reference.getSignedOn(),
                prefix + ".signedOn", violations);
        LocalDate expiresOn = parseDate(reference == null ? null : reference.getExpiresOn(),
                prefix + ".expiresOn", violations);
        boolean datesValid = signedOn != null && expiresOn != null;
        if (datesValid) {
            if (!signedOn.isBefore(expiresOn)) {
                violations.add(prefix + ".expiresOn.notAfterSignedOn");
                datesValid = false;
            } else if (signedOn.plusDays(MAX_VALIDITY_DAYS).isBefore(expiresOn)) {
                violations.add(prefix + ".expiresOn.tooLong");
                datesValid = false;
            }
        }
        return new DecisionContent(decisionId != null && datesValid, decisionId, signedOn, expiresOn);
    }

    private static Capability capabilityOf(String key) {
        for (Capability capability : Capability.values()) {
            if (capabilityKey(capability).equals(key)) {
                return capability;
            }
        }
        return null;
    }

    private static Set<Capability> withoutNull(Set<Capability> enabled) {
        Set<Capability> result = EnumSet.noneOf(Capability.class);
        for (Capability capability : enabled) {
            if (capability != null) {
                result.add(capability);
            }
        }
        return result;
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
        String upper = upper(value);
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

    private static String upper(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    /** 1件の決定の正規化済み内容。validはID・日付が全て有効（時間要因は含めない）であることを表す。 */
    record DecisionContent(boolean valid, String decisionId, LocalDate signedOn, LocalDate expiresOn) {
    }

    /** digest対象の正規化済み束縛内容。completeはdigestを算出できる（全項目が有効）ことを表す。 */
    record Content(boolean complete, String tenantId, boolean capabilitiesValid, Set<Capability> capabilities,
                   Map<Decision, DecisionContent> decisions) {
    }

    /** 評価結果。completeの場合だけsnapshotを持つ。violationsは固定コードのみ。 */
    public record Evaluation(boolean complete, List<String> violations, Snapshot snapshot) {
        public Evaluation {
            violations = violations == null ? List.of() : List.copyOf(violations);
            if (complete != (snapshot != null) || (complete && !violations.isEmpty())) {
                throw new IllegalArgumentException("NF07の決定ID束縛の評価結果が不整合です");
            }
        }
    }

    /** 起動時に確認した束縛値。秘密値を含まない（決定IDは決定書の公開識別子）。 */
    public record Snapshot(String tenantId, Set<Capability> capabilities, Map<Decision, String> decisionIds,
                           String approvalDigest) {
        public Snapshot {
            capabilities = capabilities == null || capabilities.isEmpty()
                    ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
            decisionIds = decisionIds == null || decisionIds.isEmpty()
                    ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(decisionIds));
        }
    }
}
