package com.ses.privacy.production;

import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.privacy.production.PrivacyDecisionBinding.Decision;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * NF07 の承認の値の源（feature-enablement-console design §8.5 の 1。FE-C01）。
 *
 * <p>決定 ID の束縛（承認 tenant・承認 capability・approval digest・各決定の ID・署名日・失効日）と、D-06 の期限規則
 * （{@code due-days}）・開示 package の最長保存期間（{@code package-max-age}）を返す。実装は2つ:</p>
 * <ul>
 *   <li>property の源（{@link PropertyPrivacyApprovalSource}）: {@code privacy.approval.*} と {@code privacy.dsar.*} の bind 結果。
 *       dev・test の既定。{@link PrivacyApprovalProperties} の getter を呼んでよいのはこの実装だけ（{@code ApprovalValueReadGuardTest}）。</li>
 *   <li>DB の源（{@link DatabasePrivacyApprovalSource}）: 確認済みの組「NF07 の決定の束縛」（D-01・D-09・D-06）と、確認済みの署名
 *       {@code NF07_D02}・{@code NF07_D03}・{@code NF07_D05}。組の決定は、現在 {@code CONFIRMED} の組の全ての member から、member の
 *       署名の行の状態（{@code EXPIRED}・{@code REVOKED} 等）に関係なく返す（design §2.6.1 の review P2-N1）。member の有効性は
 *       開放単位ごとの署名の条件（{@code SignOffConditionEvaluator}）だけが判定する。</li>
 * </ul>
 * 呼出し側は呼出しごとに読む（署名の差替えは再起動なしに次の呼出しから効く）。1回の評価の中で値が食い違わないよう、評価の初めに
 * {@link #resolve()} で固定した値を使う。
 */
public interface PrivacyApprovalSource {

    /** 承認 tenant（未設定は null）。 */
    String tenantId();

    /** D-09 で承認した capability の原文（{@code retention} / {@code dsar}。未設定は空）。 */
    List<String> capabilities();

    /** 決定書に記載された approval digest の原文（未設定は null）。 */
    String approvalDigest();

    /** 決定の参照（ID・署名日・失効日の原文）。未設定・未対応は null。 */
    DecisionValue reference(Decision decision);

    /** D-06 の期限規則の日数（未設定・解釈できなければ null）。 */
    Integer dsarDueDays();

    /** D-06 の開示 package の最長保存期間（未設定・解釈できなければ null）。 */
    Duration dsarPackageMaxAge();

    /** 失効の何日前から警告するか（承認内容ではない運用値。digest に含めない）。 */
    default int expiryWarningDays() {
        return 30;
    }

    /** 1回の評価に使う値を固定する（DB の源は現在の snapshot から値を写す。property の源は自分を返す）。 */
    default PrivacyApprovalSource resolve() {
        return this;
    }

    /**
     * 指定の snapshot の値（DB の源は書込みの transaction の中で読み直した snapshot から写す。property の源は自分を返す）。条件の
     * 評価は memory の snapshot ではなく評価に渡された snapshot を使う（feature-enablement-console design §3.2 の review P2-3）。
     */
    default PrivacyApprovalSource forSnapshot(FeatureEnablementSnapshot snapshot) {
        return this;
    }

    /** 1件の決定の参照の原文（値は決定書の公開識別子と日付で、秘密値を含まない）。 */
    record DecisionValue(String decisionId, String signedOn, String expiresOn) {

        /** 日付つきの決定（DB の源の行から作る）。 */
        public static DecisionValue of(String decisionId, LocalDate signedOn, LocalDate expiresOn) {
            return new DecisionValue(decisionId, signedOn == null ? null : signedOn.toString(),
                    expiresOn == null ? null : expiresOn.toString());
        }
    }

    /** 署名の行の値（組の member・確認済みの署名。構造化された値は key → 原文）。 */
    record SignOffValue(String reference, LocalDate signedOn, LocalDate expiresOn, Map<String, String> structured) {

        public SignOffValue {
            structured = structured == null ? Map.of() : Map.copyOf(structured);
        }
    }

    /** property の源（{@code privacy.approval.*}・{@code privacy.dsar.*}）。どちらも null なら全て未設定。 */
    static PrivacyApprovalSource properties(PrivacyApprovalProperties approval, PrivacyFeatureProperties features) {
        return new PropertyPrivacyApprovalSource(approval, features);
    }

    /**
     * 署名の行（種別 code → 値。{@code NF07_D01} 等）から作った固定の値。D-09 の構造化された値 {@code tenantId}・{@code capabilities}・
     * {@code approvalDigest} と、D-06 の {@code dueDays}・{@code packageMaxAge} を読む。行が無い決定は未設定。
     */
    static PrivacyApprovalSource fromSignOffs(Map<String, SignOffValue> byKind, int expiryWarningDays) {
        Map<String, SignOffValue> rows = byKind == null ? Map.of() : byKind;
        Map<Decision, DecisionValue> references = new EnumMap<>(Decision.class);
        for (Decision decision : Decision.values()) {
            SignOffValue row = rows.get(kindCode(decision));
            if (row != null) {
                references.put(decision, DecisionValue.of(row.reference(), row.signedOn(), row.expiresOn()));
            }
        }
        SignOffValue d09 = rows.get(kindCode(Decision.D09));
        SignOffValue d06 = rows.get(kindCode(Decision.D06));
        Map<String, String> d09Values = d09 == null ? Map.of() : d09.structured();
        Map<String, String> d06Values = d06 == null ? Map.of() : d06.structured();
        return new FixedPrivacyApprovalSource(d09Values.get("tenantId"), splitCapabilities(d09Values.get("capabilities")),
                d09Values.get("approvalDigest"), references, parseDueDays(d06Values.get("dueDays")),
                parsePackageMaxAge(d06Values.get("packageMaxAge")), expiryWarningDays);
    }

    /** 決定の署名の種別 code（{@code NF07_D01} 等。目録の種別と同じ）。 */
    static String kindCode(Decision decision) {
        return "NF07_" + decision.name();
    }

    /** capability の原文（カンマ・空白区切り）を分ける。空なら空の一覧。 */
    static List<String> splitCapabilities(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return Collections.unmodifiableList(values);
    }

    /** 期限規則の日数の原文（整数）。解釈できなければ null。 */
    static Integer parseDueDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 開示 package の最長保存期間の原文。{@code privacy.dsar.package-max-age} と同じく、単位の無い数値は日数、{@code PT12H}・
     * {@code 12h} も受け付ける。解釈できなければ null。
     */
    static Duration parsePackageMaxAge(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(raw.trim(), ChronoUnit.DAYS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 固定の値の源（DB の源の {@link #resolve()} と、組の整合の検査が作る）。 */
    record FixedPrivacyApprovalSource(String tenantId, List<String> capabilities, String approvalDigest,
                                      Map<Decision, DecisionValue> references, Integer dsarDueDays,
                                      Duration dsarPackageMaxAge, int expiryWarningDays)
            implements PrivacyApprovalSource {

        public FixedPrivacyApprovalSource {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            references = references == null || references.isEmpty() ? Map.of()
                    : Collections.unmodifiableMap(new EnumMap<>(references));
        }

        @Override
        public DecisionValue reference(Decision decision) {
            return decision == null ? null : references.get(decision);
        }
    }
}
