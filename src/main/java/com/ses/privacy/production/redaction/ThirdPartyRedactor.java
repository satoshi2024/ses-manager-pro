package com.ses.privacy.production.redaction;

import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyApprovalSource;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * DSAR 草案の第三者情報 redaction の枠（production-bridge-phased-plan.md §3.2、W-7。規則の値は D-06 で UNKNOWN）。
 *
 * <p>規則は D-06（DSAR SOP）の決定IDを持つ {@link Rules} からだけ読む。次のどれかなら「規則未設定」とし、
 * 全 field を要手動確認に回して自動では1件も出さない（§5.2 の A2b「自動出力 0」）。
 * <ul>
 *   <li>{@link Rules} の Bean が無い・一意でない（第1期は Bean を定義しない）</li>
 *   <li>規則の決定IDが空、または D-06 の決定ID（承認の値の源 {@link PrivacyApprovalSource}。property の源は
 *       {@code privacy.approval.d06.decision-id}、DB の源は確認済みの組の D-06。呼出しごとに読む）と一致しない</li>
 *   <li>規則・決定IDの取得で例外</li>
 * </ul>
 * 規則がある場合も、第三者の field は自動で出さず（{@code withheld}）、帰属が不明な field と規則で自動交付を
 * 許していない field は要手動確認に回す。自動で出すのは「交付区分が {@link DsarDeliveryClass#SELF} の data element の、
 * 本人の field で、規則が自動交付を許すもの」だけである。交付区分が {@link DsarDeliveryClass#MIXED_THIRD_PARTY}
 * （または不明）の data element は、規則があっても1件も自動で出さない（{@link #MIXED_MANUAL_ONLY}。契約書等の file は
 * 項目単位で redact できないため。区分は provider の宣言から {@code ProductionPrivacyProviderRegistry#deliveryClassOf} で得る）。
 * 本 class は値を書き換えない（伏字化の方式は D-06 の決定後に追加する）。
 */
@Slf4j
@Component
public class ThirdPartyRedactor {

    /** 規則未設定（全 field を要手動確認）。 */
    public static final String RULES_UNSET = "REDACTION_RULES_UNSET";
    /** D-06 の規則を適用した。 */
    public static final String RULES_APPLIED = "REDACTION_RULES_APPLIED";
    /** 第三者混在の data element のため、規則があっても自動では出さない。 */
    public static final String MIXED_MANUAL_ONLY = "REDACTION_MIXED_MANUAL_ONLY";

    private final Supplier<Rules> rulesSource;
    private final Supplier<String> boundDecisionId;

    /** 承認の値の源から D-06 の決定ID を呼出しごとに読む（feature-enablement-console design §8.5 の 1。FE-C01）。 */
    @Autowired
    public ThirdPartyRedactor(ObjectProvider<Rules> rules, PrivacyApprovalSource approvalSource) {
        this(rules::getIfUnique, () -> d06DecisionId(approvalSource));
    }

    /** property の源で包む（既存の試験のため）。 */
    public ThirdPartyRedactor(ObjectProvider<PrivacyApprovalProperties> approvalProperties,
                              ObjectProvider<Rules> rules) {
        this(rules::getIfUnique,
                () -> d06DecisionId(PrivacyApprovalSource.properties(approvalProperties.getIfAvailable(), null)));
    }

    private static String d06DecisionId(PrivacyApprovalSource source) {
        PrivacyApprovalSource.DecisionValue d06 = source == null ? null
                : source.reference(PrivacyDecisionBinding.Decision.D06);
        return d06 == null ? null : d06.decisionId();
    }

    ThirdPartyRedactor(Supplier<Rules> rulesSource, Supplier<String> boundDecisionId) {
        this.rulesSource = Objects.requireNonNull(rulesSource, "規則の取得元が必要です");
        this.boundDecisionId = Objects.requireNonNull(boundDecisionId, "D-06 決定IDの取得元が必要です");
    }

    /**
     * 1つの data element の field を自動交付・要手動確認・第三者として保留の3つに分ける。入力の順序を保つ。
     *
     * @param deliveryClass data element の DSAR 交付区分（null は第三者混在として扱う）
     * @param fields        判定対象の field
     */
    public Outcome redact(DsarDeliveryClass deliveryClass, List<Field> fields) {
        List<Field> input = fields == null ? List.of() : fields;
        Rules rules = effectiveRules();
        boolean selfOnly = deliveryClass == DsarDeliveryClass.SELF;
        List<Field> autoReleased = new ArrayList<>();
        List<Field> manualReview = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        for (Field field : input) {
            if (field == null) {
                continue;
            }
            if (rules == null) {
                manualReview.add(field);
            } else if (field.ownership() == Ownership.THIRD_PARTY) {
                withheld.add(field.fieldId());
            } else if (selfOnly && field.ownership() == Ownership.SUBJECT
                    && rules.autoReleasableFieldIds().contains(field.fieldId())) {
                autoReleased.add(field);
            } else {
                manualReview.add(field);
            }
        }
        String reason = rules == null ? RULES_UNSET : selfOnly ? RULES_APPLIED : MIXED_MANUAL_ONLY;
        return new Outcome(reason, autoReleased, manualReview, withheld);
    }

    /** D-06 の決定IDに束縛された規則。束縛できなければ null（規則未設定）。 */
    private Rules effectiveRules() {
        try {
            Rules rules = rulesSource.get();
            String bound = boundDecisionId.get();
            if (rules == null || rules.decisionId() == null || rules.decisionId().isBlank()
                    || bound == null || bound.isBlank() || !bound.trim().equals(rules.decisionId().trim())) {
                return null;
            }
            return rules;
        } catch (RuntimeException e) {
            log.warn("[privacy] 第三者redactionの規則を確認できないため、全fieldを要手動確認に回します", e);
            return null;
        }
    }

    /** field の帰属。 */
    public enum Ownership {
        /** 請求者本人の情報。 */
        SUBJECT,
        /** 第三者の情報（自動では出さない）。 */
        THIRD_PARTY,
        /** 帰属を判定できない（要手動確認）。 */
        UNKNOWN
    }

    /**
     * 判定対象の field。
     *
     * @param fieldId   field の識別子（例: {@code t_engineer.phone}）
     * @param value     値
     * @param ownership 帰属（null は UNKNOWN として扱う）
     */
    public record Field(String fieldId, Object value, Ownership ownership) {
        public Field {
            if (fieldId == null || fieldId.isBlank()) {
                throw new IllegalArgumentException("field ID が必要です");
            }
            ownership = ownership == null ? Ownership.UNKNOWN : ownership;
        }
    }

    /**
     * D-06 の決定で定める redaction 規則（第1期は Bean を定義しない）。
     *
     * @param decisionId             規則の根拠となる D-06 の決定ID
     * @param autoReleasableFieldIds 本人の field のうち自動交付を許す field ID
     */
    public record Rules(String decisionId, Set<String> autoReleasableFieldIds) {
        public Rules {
            autoReleasableFieldIds = Set.copyOf(autoReleasableFieldIds == null ? Set.of() : autoReleasableFieldIds);
        }
    }

    /**
     * 判定結果。第三者として保留した field は値を持たない（ID だけ）。
     *
     * @param reason       {@link #RULES_UNSET} / {@link #RULES_APPLIED}
     * @param autoReleased 自動で出してよい field
     * @param manualReview 要手動確認の field
     * @param withheldFieldIds 第三者として保留した field の ID
     */
    public record Outcome(String reason, List<Field> autoReleased, List<Field> manualReview,
                          List<String> withheldFieldIds) {
        public Outcome {
            autoReleased = List.copyOf(autoReleased);
            manualReview = List.copyOf(manualReview);
            withheldFieldIds = List.copyOf(withheldFieldIds);
        }

        public int autoReleasedCount() {
            return autoReleased.size();
        }
    }
}
