package com.ses.service.ai;

import com.ses.config.AiConfig;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * NF08 外部送信承認設定（ai.external-send-approval.*）の純粋評価器。
 * 署名済み決定ID・モデル・用途・data category・宛先・費用上限が完全で、かつ現行のai.*設定と一致し、
 * 承認内容のdigestが決定書に記載されたdigest（approval-digest）と一致する場合だけcompleteを返す。
 * 違反は固定コードだけで返し、設定値そのものをメッセージへ含めない。I/Oや状態を持たない。
 */
public final class AiExternalSendApproval {

    /** 承認日・失効日・費用累計の暦はJST業務日で判定する。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Tokyo");

    /**
     * 外部送信を承認できる用途。gatewayが送信前に費用上限・監査を束縛できるのはCopilotだけであり、
     * legacy/取込用途はproviderがlocal以外なら常に拒否される。
     */
    public static final Set<String> SUPPORTED_PURPOSES = Set.of(AiGatewayRequest.USE_COPILOT);

    /** 承認できるdata category（送信field）。管理Copilotの最小field契約の部分集合に限る。 */
    public static final Set<String> SUPPORTED_DATA_CATEGORIES = AiPiiMasker.COPILOT_SEND_FIELDS;

    /** provider別の宛先host。adapter・bounded transportの完全一致allow-listと同じ値に固定する。 */
    public static final Map<String, Set<String>> PROVIDER_HOSTS = Map.of(
            "gemini", Set.of("generativelanguage.googleapis.com"));

    /** 承認の最長有効期間（日）。年次の再承認を強制する。 */
    public static final int MAX_VALIDITY_DAYS = 366;

    /** bounded transportの送信body上限（64KiB）と同値。これを超えるprompt上限は承認できない。 */
    public static final int MAX_REQUEST_BYTES_CEILING = 64 * 1024;

    /** 承認内容digestの正規形の版。正規形を変える場合は版を上げ、決定書のdigestも取り直す。 */
    public static final String DIGEST_FORMAT_VERSION = "nf08-external-send-approval-v1";

    /** 時間の経過だけで変わる違反（失効・署名日前）。起動を止めず外部送信だけを止める対象。 */
    public static final Set<String> TEMPORAL_VIOLATIONS = Set.of("signedOn.future", "expiresOn.expired");

    /** 承認digestの不一致（決定書に記載された内容と現行設定が食い違う）。 */
    public static final String VIOLATION_DIGEST_MISMATCH = "approvalDigest.mismatch";

    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(100_000);
    private static final int MAX_THINKING_TOKENS = 1_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{3,63}");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern PURPOSE = Pattern.compile("[A-Za-z_]{1,64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern HOST = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    /** 未署名の転記を検出するplaceholder断片（大文字比較）。 */
    private static final List<String> PLACEHOLDER_FRAGMENTS = List.of(
            "TBD", "TODO", "PENDING", "CHANGEME", "CHANGE-ME", "PLACEHOLDER", "UNSIGNED",
            "DUMMY", "SAMPLE", "EXAMPLE", "XXXX", "DRAFT", "UNKNOWN");
    private static final Set<String> PLACEHOLDER_VALUES = Set.of("NONE", "NULL", "N-A");

    private AiExternalSendApproval() {
    }

    /** clockの現在時刻をJST業務日へ変換する。 */
    public static LocalDate businessDate(Clock clock) {
        return LocalDate.ofInstant((clock == null ? Clock.systemUTC() : clock).instant(), BUSINESS_ZONE);
    }

    /** 承認設定を評価する。todayはJST業務日（失効・署名日の判定に使う）。 */
    public static Evaluation evaluate(AiConfig config, LocalDate today) {
        if (config == null || today == null) {
            return new Evaluation(false, List.of("approval.unavailable"), null);
        }
        AiConfig.ExternalSendApproval approval = config.getExternalSendApproval();
        if (approval == null) {
            return new Evaluation(false, List.of("approval.missing"), null);
        }
        List<String> violations = new ArrayList<>();
        Content content = readContent(config, approval, violations);

        LocalDate signedOn = content.signedOn();
        LocalDate expiresOn = content.expiresOn();
        if (signedOn != null && signedOn.isAfter(today)) {
            violations.add("signedOn.future");
        }
        if (expiresOn != null && !today.isBefore(expiresOn)) {
            violations.add("expiresOn.expired");
        }

        // 決定書に記載されたdigestと承認内容を照合する。失効等の時間要因に関係なく内容の食い違いを検出する。
        String configuredDigest = lower(approval.getApprovalDigest());
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
        return new Evaluation(true, List.of(), new Snapshot(content.decisionId(), content.dg08DecisionId(),
                content.nf07PolicyId(), content.dpaReference(), signedOn, expiresOn, content.provider(),
                content.model(), content.purposes(), content.dataCategories(), content.destinationHost(),
                content.costLimit(), configuredDigest));
    }

    /**
     * 現行設定の承認内容から正規形digest（SHA-256、小文字hex）を算出する。内容が不完全・形式不正ならnull。
     * 署名者が決定書へ記載し、運用者が {@code ai.external-send-approval.approval-digest} へ転記する値である。
     * 正規形は {@link #canonicalContent} を参照。
     */
    public static String computeDigest(AiConfig config) {
        if (config == null || config.getExternalSendApproval() == null) {
            return null;
        }
        Content content = readContent(config, config.getExternalSendApproval(), new ArrayList<>());
        return content.complete() ? digestOf(content) : null;
    }

    /**
     * digest対象の正規形。1行目は版、以降は「key=value」を固定順でLF終端する（最終行もLFで終える）。
     * ID・modelは前後空白を除いた値、provider・hostは小文字、用途は大文字、用途とdata categoryは昇順のカンマ区切り、
     * 単価は末尾の0を除いた10進表記（例: 1.50→1.5、100→100）とする。
     */
    static String canonicalContent(Content content) {
        StringBuilder sb = new StringBuilder();
        line(sb, null, DIGEST_FORMAT_VERSION);
        line(sb, "decision-id", content.decisionId());
        line(sb, "dg08-decision-id", content.dg08DecisionId());
        line(sb, "nf07-policy-id", content.nf07PolicyId());
        line(sb, "dpa-reference", content.dpaReference());
        line(sb, "signed-on", content.signedOn().toString());
        line(sb, "expires-on", content.expiresOn().toString());
        line(sb, "provider", content.provider());
        line(sb, "model", content.model());
        line(sb, "purposes", String.join(",", new TreeSet<>(content.purposes())));
        line(sb, "data-categories", String.join(",", new TreeSet<>(content.dataCategories())));
        line(sb, "destination-host", content.destinationHost());
        CostLimitSnapshot cost = content.costLimit();
        line(sb, "cost-limit.per-request-yen", Integer.toString(cost.perRequestYen()));
        line(sb, "cost-limit.daily-yen", Integer.toString(cost.dailyYen()));
        line(sb, "cost-limit.monthly-yen", Integer.toString(cost.monthlyYen()));
        line(sb, "cost-limit.input-yen-per-thousand-tokens", plain(cost.inputYenPerThousandTokens()));
        line(sb, "cost-limit.output-yen-per-thousand-tokens", plain(cost.outputYenPerThousandTokens()));
        line(sb, "cost-limit.max-request-bytes", Integer.toString(cost.maxRequestBytes()));
        line(sb, "cost-limit.max-thinking-tokens", Integer.toString(cost.maxThinkingTokens()));
        return sb.toString();
    }

    /** gateの不足コード（{@code approval.} 接頭辞付き）が時間要因だけの違反か。 */
    public static boolean isTemporalGateCode(String gateCode) {
        return gateCode != null && gateCode.startsWith("approval.")
                && TEMPORAL_VIOLATIONS.contains(gateCode.substring("approval.".length()));
    }

    /**
     * 有効期間の監視用状態。署名日・失効日だけを見て、他の項目の完全性は判定しない（完全性はevaluateが正）。
     * warningDaysは失効の何日前から {@link WindowState#EXPIRING} とするか（1以上）。
     */
    public static Window window(AiConfig config, LocalDate today, int warningDays) {
        AiConfig.ExternalSendApproval approval = config == null ? null : config.getExternalSendApproval();
        if (approval == null || today == null) {
            return new Window(WindowState.NOT_CONFIGURED, null);
        }
        LocalDate signedOn = parseDate(approval.getSignedOn(), "signedOn", new ArrayList<>());
        LocalDate expiresOn = parseDate(approval.getExpiresOn(), "expiresOn", new ArrayList<>());
        if (expiresOn == null) {
            return new Window(WindowState.NOT_CONFIGURED, null);
        }
        long daysRemaining = ChronoUnit.DAYS.between(today, expiresOn);
        if (daysRemaining <= 0) {
            return new Window(WindowState.EXPIRED, daysRemaining);
        }
        if (signedOn != null && signedOn.isAfter(today)) {
            return new Window(WindowState.NOT_YET_VALID, daysRemaining);
        }
        if (daysRemaining <= Math.max(1, warningDays)) {
            return new Window(WindowState.EXPIRING, daysRemaining);
        }
        return new Window(WindowState.VALID, daysRemaining);
    }

    /**
     * ai.api-urlの宛先host。Copilot adapterが受理するcanonical base（https、443、pathなし）以外はnull。
     * 未設定時はproviderの唯一の既定hostを返す。
     */
    public static String endpointHost(String apiUrl, String provider) {
        String value = trim(apiUrl);
        if (value.isEmpty()) {
            Set<String> hosts = PROVIDER_HOSTS.get(lower(provider));
            return hosts == null || hosts.size() != 1 ? null : hosts.iterator().next();
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || (!path.isEmpty() && !"/".equals(path))) {
                return null;
            }
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** digest以外の承認内容を読み、正規化値と違反を返す。 */
    private static Content readContent(AiConfig config, AiConfig.ExternalSendApproval approval,
                                       List<String> violations) {
        String decisionId = requireId(approval.getDecisionId(), "decisionId", violations);
        String dg08DecisionId = requireId(approval.getDg08DecisionId(), "dg08DecisionId", violations);
        String nf07PolicyId = requireId(approval.getNf07PolicyId(), "nf07PolicyId", violations);
        String dpaReference = requireId(approval.getDpaReference(), "dpaReference", violations);
        boolean idsDistinct = true;
        if (decisionId != null && dg08DecisionId != null && nf07PolicyId != null && dpaReference != null
                && new HashSet<>(List.of(upper(decisionId), upper(dg08DecisionId), upper(nf07PolicyId),
                upper(dpaReference))).size() != 4) {
            // 一つのIDを全項目へ転記した設定は、個別の署名を証明しない。
            violations.add("decisionIds.notDistinct");
            idsDistinct = false;
        }

        LocalDate signedOn = parseDate(approval.getSignedOn(), "signedOn", violations);
        LocalDate expiresOn = parseDate(approval.getExpiresOn(), "expiresOn", violations);
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

        String provider = lower(approval.getProvider());
        boolean providerValid = false;
        if (provider.isEmpty()) {
            violations.add("provider.missing");
        } else if (!PROVIDER_HOSTS.containsKey(provider)) {
            violations.add("provider.unsupported");
        } else if (!provider.equals(lower(config.getProvider()))) {
            violations.add("provider.mismatch");
        } else {
            providerValid = true;
        }

        String model = trim(approval.getModel());
        boolean modelValid = false;
        if (model.isEmpty()) {
            violations.add("model.missing");
        } else if (!MODEL.matcher(model).matches()) {
            violations.add("model.format");
        } else if (!model.equals(trim(config.getModel()))) {
            // ai.model未設定時のadapter既定modelへ暗黙に依存させない。
            violations.add("model.mismatch");
        } else {
            modelValid = true;
        }

        Set<String> purposes = new TreeSet<>();
        boolean purposeFormatValid = true;
        if (approval.getPurposes() != null) {
            for (String purpose : approval.getPurposes()) {
                String trimmed = trim(purpose);
                if (trimmed.isEmpty()) {
                    continue;
                }
                // 非ASCIIの同形文字（例: U+0131）を大文字化で受理しないよう、正規化前にASCIIへ限定する。
                if (!PURPOSE.matcher(trimmed).matches()) {
                    purposeFormatValid = false;
                    continue;
                }
                purposes.add(trimmed.toUpperCase(Locale.ROOT));
            }
        }
        boolean purposesValid = false;
        if (!purposeFormatValid) {
            violations.add("purposes.format");
        } else if (purposes.isEmpty()) {
            violations.add("purposes.missing");
        } else if (!SUPPORTED_PURPOSES.containsAll(purposes)) {
            violations.add("purposes.unsupported");
        } else {
            purposesValid = true;
        }

        Set<String> dataCategories = new TreeSet<>();
        if (approval.getDataCategories() != null) {
            for (String category : approval.getDataCategories()) {
                String trimmed = trim(category);
                if (!trimmed.isEmpty()) {
                    dataCategories.add(trimmed);
                }
            }
        }
        boolean dataCategoriesValid = false;
        if (dataCategories.isEmpty()) {
            violations.add("dataCategories.missing");
        } else if (!SUPPORTED_DATA_CATEGORIES.containsAll(dataCategories)) {
            // 氏名・会社名等を含むlegacy allow-list項目は外部送信の承認範囲にできない。
            violations.add("dataCategories.unsupported");
        } else {
            dataCategoriesValid = true;
        }

        String destinationHost = lower(approval.getDestinationHost());
        boolean hostValid = false;
        if (destinationHost.isEmpty()) {
            violations.add("destinationHost.missing");
        } else if (!HOST.matcher(destinationHost).matches()) {
            violations.add("destinationHost.format");
        } else {
            hostValid = true;
            if (!PROVIDER_HOSTS.getOrDefault(provider, Set.of()).contains(destinationHost)) {
                violations.add("destinationHost.notAllowlisted");
                hostValid = false;
            }
            String endpointHost = endpointHost(config.getApiUrl(), provider);
            if (endpointHost == null) {
                violations.add("apiUrl.notCanonical");
                hostValid = false;
            } else if (!endpointHost.equals(destinationHost)) {
                violations.add("destinationHost.mismatch");
                hostValid = false;
            }
        }

        if (config.getMaxTokens() <= 0) {
            violations.add("maxTokens.unbounded");
        }
        CostLimitSnapshot costLimit = costLimit(approval.getCostLimit(), violations);

        boolean complete = decisionId != null && dg08DecisionId != null && nf07PolicyId != null
                && dpaReference != null && idsDistinct && datesValid && providerValid && modelValid
                && purposesValid && dataCategoriesValid && hostValid && costLimit != null;
        return new Content(complete, decisionId, dg08DecisionId, nf07PolicyId, dpaReference, signedOn, expiresOn,
                provider, model, Set.copyOf(purposes), Set.copyOf(dataCategories), destinationHost, costLimit);
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

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static CostLimitSnapshot costLimit(AiConfig.CostLimit limit, List<String> violations) {
        if (limit == null) {
            violations.add("costLimit.missing");
            return null;
        }
        int before = violations.size();
        Integer perRequest = limit.getPerRequestYen();
        Integer daily = limit.getDailyYen();
        Integer monthly = limit.getMonthlyYen();
        boolean perRequestValid = perRequest != null && perRequest >= 1;
        boolean dailyValid = daily != null && daily >= 1;
        boolean monthlyValid = monthly != null && monthly >= 1;
        if (!perRequestValid) {
            violations.add("costLimit.perRequestYen");
        }
        if (!dailyValid) {
            violations.add("costLimit.dailyYen");
        }
        if (!monthlyValid) {
            violations.add("costLimit.monthlyYen");
        }
        if (perRequestValid && dailyValid && daily < perRequest) {
            violations.add("costLimit.dailyBelowPerRequest");
        }
        if (dailyValid && monthlyValid && monthly < daily) {
            violations.add("costLimit.monthlyBelowDaily");
        }
        if (!validRate(limit.getInputYenPerThousandTokens())) {
            violations.add("costLimit.inputRate");
        }
        if (!validRate(limit.getOutputYenPerThousandTokens())) {
            violations.add("costLimit.outputRate");
        }
        Integer maxRequestBytes = limit.getMaxRequestBytes();
        if (maxRequestBytes == null || maxRequestBytes < 1 || maxRequestBytes > MAX_REQUEST_BYTES_CEILING) {
            violations.add("costLimit.maxRequestBytes");
        }
        Integer maxThinking = limit.getMaxThinkingTokens();
        if (maxThinking == null || maxThinking < 0 || maxThinking > MAX_THINKING_TOKENS) {
            violations.add("costLimit.maxThinkingTokens");
        }
        if (violations.size() != before) {
            return null;
        }
        return new CostLimitSnapshot(perRequest, daily, monthly, limit.getInputYenPerThousandTokens(),
                limit.getOutputYenPerThousandTokens(), maxRequestBytes, maxThinking);
    }

    /** 0円単価（free tier等）は費用上限の証明にならないため承認しない。 */
    private static boolean validRate(BigDecimal rate) {
        return rate != null && rate.signum() > 0 && rate.scale() <= 6 && rate.compareTo(MAX_RATE) <= 0;
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

    /** digest対象の正規化済み承認内容。completeはdigestを算出できる（全項目が有効）ことを表す。 */
    record Content(boolean complete, String decisionId, String dg08DecisionId, String nf07PolicyId,
                   String dpaReference, LocalDate signedOn, LocalDate expiresOn, String provider, String model,
                   Set<String> purposes, Set<String> dataCategories, String destinationHost,
                   CostLimitSnapshot costLimit) {
    }

    /** 評価結果。completeの場合だけsnapshotを持つ。violationsは固定コードのみ。 */
    public record Evaluation(boolean complete, List<String> violations, Snapshot snapshot) {
        public Evaluation {
            violations = violations == null ? List.of() : List.copyOf(violations);
            if (complete != (snapshot != null) || (complete && !violations.isEmpty())) {
                throw new IllegalArgumentException("外部送信承認の評価結果が不整合です");
            }
        }
    }

    /**
     * 起動時に固定する承認済み外部送信の束縛値。秘密値を含まない。
     * approvalDigestは決定書に記載され、現行設定から再計算した値と一致を確認済みのdigestである。
     */
    public record Snapshot(String decisionId, String dg08DecisionId, String nf07PolicyId, String dpaReference,
                           LocalDate signedOn, LocalDate expiresOn, String provider, String model,
                           Set<String> purposes, Set<String> dataCategories, String destinationHost,
                           CostLimitSnapshot costLimit, String approvalDigest) {
        public Snapshot {
            purposes = purposes == null ? Set.of() : Set.copyOf(purposes);
            dataCategories = dataCategories == null ? Set.of() : Set.copyOf(dataCategories);
        }

        public boolean permitsPurpose(String purpose) {
            return purpose != null && purposes.contains(purpose);
        }

        /** 送信するfield idが全て承認済みdata categoryに含まれるか（空集合は許可）。 */
        public boolean permitsDataCategories(Collection<String> fieldIds) {
            return fieldIds != null && dataCategories.containsAll(fieldIds);
        }
    }

    /** 承認済みの費用上限と単価（円、1,000 tokenあたり）。 */
    public record CostLimitSnapshot(int perRequestYen, int dailyYen, int monthlyYen,
                                    BigDecimal inputYenPerThousandTokens, BigDecimal outputYenPerThousandTokens,
                                    int maxRequestBytes, int maxThinkingTokens) {
    }

    /** 有効期間の監視状態。 */
    public enum WindowState {
        /** 失効日が未設定・形式不正。 */
        NOT_CONFIGURED,
        /** 署名日が未来（まだ有効でない）。 */
        NOT_YET_VALID,
        /** 有効期間内で、失効までの日数が警告日数より多い。 */
        VALID,
        /** 有効期間内だが、失効まで警告日数以内。 */
        EXPIRING,
        /** 失効日以降。 */
        EXPIRED
    }

    /** 有効期間の監視結果。daysRemainingは失効日までのJST暦日数（失効後は0以下、未設定はnull）。 */
    public record Window(WindowState state, Long daysRemaining) {
    }
}
