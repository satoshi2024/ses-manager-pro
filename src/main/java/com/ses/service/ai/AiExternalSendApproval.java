package com.ses.service.ai;

import com.ses.config.AiConfig;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * NF08 外部送信承認設定（ai.external-send-approval.*）の純粋評価器。
 * 署名済み決定ID・モデル・用途・宛先・費用上限が完全で、かつ現行のai.*設定と一致する場合だけcompleteを返す。
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

    /** provider別の宛先host。adapter・bounded transportの完全一致allow-listと同じ値に固定する。 */
    public static final Map<String, Set<String>> PROVIDER_HOSTS = Map.of(
            "gemini", Set.of("generativelanguage.googleapis.com"));

    /** 承認の最長有効期間（日）。年次の再承認を強制する。 */
    public static final int MAX_VALIDITY_DAYS = 366;

    /** bounded transportの送信body上限（64KiB）と同値。これを超えるprompt上限は承認できない。 */
    public static final int MAX_REQUEST_BYTES_CEILING = 64 * 1024;

    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(100_000);
    private static final int MAX_THINKING_TOKENS = 1_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{3,63}");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");
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

        String decisionId = requireId(approval.getDecisionId(), "decisionId", violations);
        String dg08DecisionId = requireId(approval.getDg08DecisionId(), "dg08DecisionId", violations);
        String nf07PolicyId = requireId(approval.getNf07PolicyId(), "nf07PolicyId", violations);
        String dpaReference = requireId(approval.getDpaReference(), "dpaReference", violations);
        if (decisionId != null && dg08DecisionId != null && nf07PolicyId != null && dpaReference != null
                && new HashSet<>(List.of(upper(decisionId), upper(dg08DecisionId), upper(nf07PolicyId),
                upper(dpaReference))).size() != 4) {
            // 一つのIDを全項目へ転記した設定は、個別の署名を証明しない。
            violations.add("decisionIds.notDistinct");
        }

        LocalDate signedOn = parseDate(approval.getSignedOn(), "signedOn", violations);
        LocalDate expiresOn = parseDate(approval.getExpiresOn(), "expiresOn", violations);
        if (signedOn != null && signedOn.isAfter(today)) {
            violations.add("signedOn.future");
        }
        if (expiresOn != null && !today.isBefore(expiresOn)) {
            violations.add("expiresOn.expired");
        }
        if (signedOn != null && expiresOn != null) {
            if (!signedOn.isBefore(expiresOn)) {
                violations.add("expiresOn.notAfterSignedOn");
            } else if (signedOn.plusDays(MAX_VALIDITY_DAYS).isBefore(expiresOn)) {
                violations.add("expiresOn.tooLong");
            }
        }

        String provider = lower(approval.getProvider());
        if (provider.isEmpty()) {
            violations.add("provider.missing");
        } else if (!PROVIDER_HOSTS.containsKey(provider)) {
            violations.add("provider.unsupported");
        } else if (!provider.equals(lower(config.getProvider()))) {
            violations.add("provider.mismatch");
        }

        String model = trim(approval.getModel());
        if (model.isEmpty()) {
            violations.add("model.missing");
        } else if (!MODEL.matcher(model).matches()) {
            violations.add("model.format");
        } else if (!model.equals(trim(config.getModel()))) {
            // ai.model未設定時のadapter既定modelへ暗黙に依存させない。
            violations.add("model.mismatch");
        }

        Set<String> purposes = new TreeSet<>();
        if (approval.getPurposes() != null) {
            for (String purpose : approval.getPurposes()) {
                String normalized = trim(purpose).toUpperCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    purposes.add(normalized);
                }
            }
        }
        if (purposes.isEmpty()) {
            violations.add("purposes.missing");
        } else if (!SUPPORTED_PURPOSES.containsAll(purposes)) {
            violations.add("purposes.unsupported");
        }

        String destinationHost = lower(approval.getDestinationHost());
        if (destinationHost.isEmpty()) {
            violations.add("destinationHost.missing");
        } else if (!HOST.matcher(destinationHost).matches()) {
            violations.add("destinationHost.format");
        } else {
            if (!PROVIDER_HOSTS.getOrDefault(provider, Set.of()).contains(destinationHost)) {
                violations.add("destinationHost.notAllowlisted");
            }
            String endpointHost = endpointHost(config.getApiUrl(), provider);
            if (endpointHost == null) {
                violations.add("apiUrl.notCanonical");
            } else if (!endpointHost.equals(destinationHost)) {
                violations.add("destinationHost.mismatch");
            }
        }

        if (config.getMaxTokens() <= 0) {
            violations.add("maxTokens.unbounded");
        }
        CostLimitSnapshot costLimit = costLimit(approval.getCostLimit(), violations);

        if (!violations.isEmpty()) {
            return new Evaluation(false, violations, null);
        }
        return new Evaluation(true, List.of(), new Snapshot(decisionId, dg08DecisionId, nf07PolicyId,
                dpaReference, signedOn, expiresOn, provider, model, Set.copyOf(purposes), destinationHost,
                costLimit));
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

    /** 評価結果。completeの場合だけsnapshotを持つ。violationsは固定コードのみ。 */
    public record Evaluation(boolean complete, List<String> violations, Snapshot snapshot) {
        public Evaluation {
            violations = violations == null ? List.of() : List.copyOf(violations);
            if (complete != (snapshot != null) || (complete && !violations.isEmpty())) {
                throw new IllegalArgumentException("外部送信承認の評価結果が不整合です");
            }
        }
    }

    /** 起動時に固定する承認済み外部送信の束縛値。秘密値を含まない。 */
    public record Snapshot(String decisionId, String dg08DecisionId, String nf07PolicyId, String dpaReference,
                           LocalDate signedOn, LocalDate expiresOn, String provider, String model,
                           Set<String> purposes, String destinationHost, CostLimitSnapshot costLimit) {
        public Snapshot {
            purposes = purposes == null ? Set.of() : Set.copyOf(purposes);
        }

        public boolean permitsPurpose(String purpose) {
            return purpose != null && purposes.contains(purpose);
        }
    }

    /** 承認済みの費用上限と単価（円、1,000 tokenあたり）。 */
    public record CostLimitSnapshot(int perRequestYen, int dailyYen, int monthlyYen,
                                    BigDecimal inputYenPerThousandTokens, BigDecimal outputYenPerThousandTokens,
                                    int maxRequestBytes, int maxThinkingTokens) {
    }
}
