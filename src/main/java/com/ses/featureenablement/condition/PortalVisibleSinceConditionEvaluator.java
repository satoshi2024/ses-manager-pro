package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureSignOffRegistrar;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NF02 顧客ポータルの問い合わせ（{@link Feature#NF02_CUSTOMER_PORTAL}）の継続の条件: 今の公開範囲の設定
 * （{@value FeatureEnablementCatalog#SETTING_NF02_VISIBLE_SINCE}）が、確認済みの公開範囲の決定（署名 {@value #KIND_DECISION} の
 * 構造化された値 {@code visibleSince}）の範囲の内にある（feature-enablement-console design §5.6・§8.4、FE-RQ-010。段階 B の復審
 * rb-fe-b-656d71b8 の P3-1、D-4）。
 *
 * <ul>
 *   <li>範囲の内: 設定が未設定（portal 起票だけ。最も狭い）、または決定にも値があり、設定の日時が決定の日時と同じか後（公開する範囲が
 *       決定より広くない）。</li>
 *   <li>範囲の外（不成立）: 設定に値があるのに、決定が未設定・決定の値が設定より後（より狭い決定へ差し替えた）・決定の値を読めない・
 *       確認済みの決定が無い・設定の値を解釈できない。継続の条件のため、開いている顧客ポータルは自動閉鎖（警告・監査・管理者への通知）
 *       され、顧客へ見せる範囲は決定を超えない。開き直す申請・承認でも同じに判定する。</li>
 * </ul>
 * 決定の署名の期間（署名日前・失効）は共通の署名の評価器が判定する。code は固定の文字列で、日時の値を含めない。
 */
@Component
public class PortalVisibleSinceConditionEvaluator implements FeatureConditionEvaluator {

    public static final String KIND_DECISION = "NF02_PORTAL_VISIBLE_SINCE_DECISION";
    public static final String CODE_WITHIN = "setting.visibleSince.withinDecision";
    public static final String CODE_OUTSIDE = "setting.visibleSince.outsideDecision";

    @Override
    public Set<Feature> features() {
        return Set.of(Feature.NF02_CUSTOMER_PORTAL);
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        FeatureEnablementSnapshot snapshot = context.snapshot();
        String setting = snapshot.setting(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE).orElse(null);
        if (setting == null || setting.isBlank()) {
            return List.of(within("setting.unset"));
        }
        OffsetDateTime configured = parse(setting);
        if (configured == null) {
            return List.of(outside("setting.unreadable"));
        }
        FeatureEnablementSnapshot.ConfirmedSignOff decision = snapshot.signOffs().get(KIND_DECISION);
        if (decision == null) {
            return List.of(outside("decision.missing"));
        }
        String decided;
        try {
            Map<String, String> values = FeatureSignOffRegistrar.structuredMap(decision.structuredValues());
            decided = values.get("visibleSince");
        } catch (RuntimeException unreadable) {
            return List.of(outside("decision.unreadable"));
        }
        if (decided == null || decided.isBlank()) {
            return List.of(outside("decision.unset"));
        }
        OffsetDateTime decidedAt = parse(decided);
        if (decidedAt == null) {
            return List.of(outside("decision.unreadable"));
        }
        return List.of(configured.isBefore(decidedAt) ? outside("decision.narrower") : within("decision.covers"));
    }

    private static FeatureConditionCheck within(String detail) {
        return new FeatureConditionCheck(CODE_WITHIN, FeatureConditionCheck.Category.SETTING,
                FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.SATISFIED, false, null, List.of(detail));
    }

    private static FeatureConditionCheck outside(String detail) {
        return new FeatureConditionCheck(CODE_OUTSIDE, FeatureConditionCheck.Category.SETTING,
                FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.UNSATISFIED, false, null,
                List.of(detail));
    }

    private static OffsetDateTime parse(String value) {
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
