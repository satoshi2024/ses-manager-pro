package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A03（review P2-1）: 各評価器が出し得る code を「評価できない類」の分類と突き合わせる。照会・接続の失敗を表す code が評価できない類に
 * 入っていないと、一度の通信の失敗で自動閉鎖してしまう。逆に、条件の不成立を表す code が評価できない類に入ると、外れた条件で
 * 閉じずに3回の評価を待つことになる。段階 B〜D で評価器を足す時は、その評価器が出し得る code をこの一覧へ足す。
 */
class FeatureConditionCodeClassificationTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    /** 評価できない類であるべき code（既存の検査が照会の失敗を違反 code に変えて返すものを含む。design §4.2）。 */
    private static final List<String> UNVERIFIABLE = List.of(
            FeatureConditionRegistry.CODE_EVALUATOR_MISSING, "evaluator.unverifiable[AnyEvaluator]",
            "secret.INTEGRATION_HUB_PUBLIC_ID_KEY.unverifiable",
            // 段階 C・D の既存の検査が返す照会の失敗（design §4.2 の例）
            "privacyAdmins.unverifiable", "journal.unreachable", "journal.timeout", "state=STATE_IO_ERROR",
            // FE-A11: NF02 の暦を照会できない（開放の時だけの条件のまま）
            FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE,
            // 段階 B（FE-B01）: NF01 二者確認の証跡種別を照会できない
            "dualConfirmation.evidenceTypes.unverifiable");

    /** 条件の不成立を表す code（評価できない類であってはならない）。 */
    private static final List<String> UNSATISFIED = List.of(
            "master.closed", "parent.NF02.closed", "signoff.NF04_SRE.missing", "signoff.NF07_D01.bundle.missing",
            "signoff.NF04_SRE.signedOn.future", "signoff.NF04_SRE.expiresOn.expired",
            "secret.IMPORT_ROW_REFERENCE_HMAC_KEY.missing",
            // 段階 C・D の既存の違反 code（design §4.2 の例）
            "decisionBinding.d01.expiresOn.expired", "privacyAdmins.insufficient[current=1;required=2]",
            "dsar.dueDaysUnset", "state=MISSING", FeatureEnablementCatalog.CODE_NF02_CALENDAR,
            FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF,
            // FE-A11: NF10 の scheduler の状態（警告だけ）
            SchedulingConditionEvaluator.CODE_DISABLED, SchedulingConditionEvaluator.CODE_ENABLED,
            // 段階 B（FE-B01）: NF01 二者確認の Privacy 署名の参照と証跡種別
            "dualConfirmation.privacySignOff.missing", "dualConfirmation.privacySignOff.invalid",
            "dualConfirmation.privacySignOff.valid", "dualConfirmation.evidenceTypes.outsideAllowed",
            "dualConfirmation.evidenceTypes.allowed");

    @Test
    void 一覧のcodeは分類と一致する() {
        UNVERIFIABLE.forEach(code -> assertThat(FeatureEnablementCatalog.isUnverifiableCode(code)).as(code).isTrue());
        UNSATISFIED.forEach(code -> assertThat(FeatureEnablementCatalog.isUnverifiableCode(code)).as(code).isFalse());
    }

    @Test
    void 共通の評価器が出すcodeは不成立なら分類外で評価できない時は評価できない類() {
        Environment failing = mock(Environment.class);
        when(failing.getProperty(anyString())).thenThrow(new IllegalStateException("io"));
        Map<String, FeatureConditionCheck.Status> emitted = new LinkedHashMap<>();
        FeatureEnablementSnapshot empty = FeatureEnablementTestSnapshots.builder(NOW).master(false).build();
        FeatureEnablementSnapshot future = FeatureEnablementTestSnapshots.builder(NOW)
                .signOff("NF04_SRE", 1, 0, TODAY.plusDays(1), TODAY.plusDays(30))
                .signOff("NF04_PRIVACY", 2, 0, TODAY.minusDays(30), TODAY)
                .signOff("NF07_D01", 3, 0, TODAY.minusDays(1), TODAY.plusDays(30)).build();
        for (Environment environment : List.of(new MockEnvironment(), failing)) {
            List<FeatureConditionEvaluator> evaluators = List.of(new MasterSwitchConditionEvaluator(),
                    new ParentConditionEvaluator(), new SignOffConditionEvaluator(catalog),
                    new SecretPresenceConditionEvaluator(catalog, environment));
            for (FeatureEnablementSnapshot snapshot : List.of(empty, future)) {
                for (Feature feature : Feature.values()) {
                    FeatureEvaluationContext context = new FeatureEvaluationContext("default", TODAY,
                            Clock.fixed(NOW, ZoneOffset.UTC), snapshot, FeatureEvaluationContext.Purpose.REQUEST, Set.of(),
                            true);
                    for (FeatureConditionEvaluator evaluator : evaluators) {
                        for (FeatureConditionCheck check : evaluator.evaluate(feature, context)) {
                            emitted.put(check.code(), check.status());
                        }
                    }
                }
            }
        }
        assertThat(emitted).isNotEmpty();
        emitted.forEach((code, status) -> {
            if (status == FeatureConditionCheck.Status.UNVERIFIABLE) {
                assertThat(FeatureEnablementCatalog.isUnverifiableCode(code)).as("評価できない状態の code: " + code).isTrue();
            } else {
                assertThat(FeatureEnablementCatalog.isUnverifiableCode(code)).as("不成立・成立の code: " + code).isFalse();
            }
        });
        assertThat(emitted).containsKeys("master.closed", "parent.NF02.closed", "signoff.NF04_SRE.signedOn.future",
                "signoff.NF04_PRIVACY.expiresOn.expired", "signoff.NF07_D01.bundle.missing",
                "secret.INTEGRATION_HUB_PUBLIC_ID_KEY.unverifiable");
    }
}
