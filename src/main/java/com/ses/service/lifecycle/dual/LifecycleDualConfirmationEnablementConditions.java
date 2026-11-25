package com.ses.service.lifecycle.dual;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.service.FeatureEnablementCloseWarningSource;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * NF01 二者確認（{@link Feature#NF01_DUAL_CONFIRMATION}）の開放の条件（feature-enablement-console design §8.2、requirements §6.2。
 * FE-B01）。profile に依らない評価器で、起動を止めていた {@code LifecycleDualConfirmationReleaseGate} の判定
 * （{@link LifecycleDualConfirmationReleaseGate#acceptable}）をそのまま使う（条件の写しを持たない。design §4.4）。
 *
 * <ul>
 *   <li>(a) {@value #CODE_PRIVACY_SIGN_OFF}: Privacy 署名 {@value #KIND_PRIVACY} の参照が {@code acceptable}。DB の源で開放単位を
 *       DB 管理にしている時は、評価に使う snapshot（書込みの transaction の中では lock の後に読み直した snapshot）の確認済みの署名の
 *       参照を見る。property の源（dev・test）では従来の {@code app.features.nf01-dual-confirmation.privacy-sign-off-ref} を見る。
 *       署名の期間（署名日前・失効）は共通の署名の評価器が判定する。</li>
 *   <li>(b) {@value #CODE_EVIDENCE_TYPES}: 証跡種別が許可値一覧の外にある行（テンプレートのタスク定義と案件のタスク。FS 4.9.5 の
 *       (1)(2)）が0件。照合順序で揺れを同一視しないよう、バイト列で比べる（{@link LifecycleTemplateTaskMapper}）。照会に失敗すれば
 *       評価できない類の code（一度の失敗で閉じない。review P2-1）。継続の条件で、開いた後に揺れた行が入れば自動閉鎖する。</li>
 *   <li>閉鎖の警告: 二者確認を含む有効なテンプレートの件数（FS 4.9.5 の (3)。閉じても新しい案件へ DUAL タスクが展開され、閉じて
 *       いる間は免除だけで進むため。FS 4.9.4）。</li>
 * </ul>
 * code は固定の文字列だけで、参照の値を含めない。
 */
@Slf4j
@Component
public class LifecycleDualConfirmationEnablementConditions implements FeatureConditionEvaluator,
        FeatureEnablementCloseWarningSource {

    /** Privacy 署名の種別（目録の正本は {@code FeatureEnablementCatalog}）。 */
    public static final String KIND_PRIVACY = "NF01_DUAL_PRIVACY";
    public static final String CODE_PRIVACY_SIGN_OFF = "dualConfirmation.privacySignOff";
    public static final String CODE_EVIDENCE_TYPES = "dualConfirmation.evidenceTypes";
    /** 閉鎖の警告の code（{@code featureEnablement.closeWarning.dualConfirmation.activeDualTemplates}）。 */
    public static final String WARNING_ACTIVE_DUAL_TEMPLATES = "dualConfirmation.activeDualTemplates";

    private final LifecycleTemplateTaskMapper templateTaskMapper;
    private final LifecycleTaskMapper taskMapper;
    private final FeatureEnablementCatalog catalog;
    private final ObjectProvider<FeatureEnablementStateSource> stateSource;
    private final FeatureSwitchProperties properties;

    public LifecycleDualConfirmationEnablementConditions(LifecycleTemplateTaskMapper templateTaskMapper,
                                                         LifecycleTaskMapper taskMapper, FeatureEnablementCatalog catalog,
                                                         ObjectProvider<FeatureEnablementStateSource> stateSource,
                                                         FeatureSwitchProperties properties) {
        this.templateTaskMapper = Objects.requireNonNull(templateTaskMapper, "templateTaskMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.stateSource = stateSource;
        this.properties = properties;
    }

    @Override
    public Set<Feature> features() {
        return Set.of(Feature.NF01_DUAL_CONFIRMATION);
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        return List.of(privacySignOff(context.snapshot()), evidenceTypes());
    }

    /** (a) Privacy 署名の参照。 */
    FeatureConditionCheck privacySignOff(FeatureEnablementSnapshot snapshot) {
        String reference = databaseManaged() ? signedReference(snapshot) : propertyReference();
        if (reference == null) {
            return FeatureConditionCheck.unsatisfied(CODE_PRIVACY_SIGN_OFF + ".missing", FeatureConditionCheck.Category.AUTO,
                    false);
        }
        if (!LifecycleDualConfirmationReleaseGate.acceptable(reference)) {
            return FeatureConditionCheck.unsatisfied(CODE_PRIVACY_SIGN_OFF + ".invalid", FeatureConditionCheck.Category.AUTO,
                    false);
        }
        return FeatureConditionCheck.satisfied(CODE_PRIVACY_SIGN_OFF + ".valid", FeatureConditionCheck.Category.AUTO, null);
    }

    /** (b) 証跡種別の許可値の外の行。 */
    FeatureConditionCheck evidenceTypes() {
        long templateTasks;
        long tasks;
        try {
            templateTasks = templateTaskMapper.countEvidenceTypesOutsideAllowed();
            tasks = taskMapper.countEvidenceTypesOutsideAllowed();
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 証跡種別の検査を照会できません（評価できない状態として扱います）: exceptionClass={}",
                    e.getClass().getSimpleName());
            return FeatureConditionCheck.unverifiable(CODE_EVIDENCE_TYPES + ".unverifiable",
                    FeatureConditionCheck.Category.AUTO);
        }
        if (templateTasks == 0 && tasks == 0) {
            return FeatureConditionCheck.satisfied(CODE_EVIDENCE_TYPES + ".allowed", FeatureConditionCheck.Category.AUTO,
                    null);
        }
        return new FeatureConditionCheck(CODE_EVIDENCE_TYPES + ".outsideAllowed", FeatureConditionCheck.Category.AUTO,
                FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.UNSATISFIED, false, null,
                List.of("templateTasks[count=" + templateTasks + "]", "tasks[count=" + tasks + "]"));
    }

    @Override
    public List<CloseWarning> closeWarnings(Feature feature) {
        long templates = templateTaskMapper.countActiveTemplatesWithDualConfirmation();
        return templates > 0 ? List.of(new CloseWarning(WARNING_ACTIVE_DUAL_TEMPLATES, templates)) : List.of();
    }

    private boolean databaseManaged() {
        FeatureEnablementStateSource source = stateSource == null ? null : stateSource.getIfAvailable();
        return source != null && source.database() && catalog.databaseManaged(Feature.NF01_DUAL_CONFIRMATION);
    }

    private static String signedReference(FeatureEnablementSnapshot snapshot) {
        FeatureEnablementSnapshot.ConfirmedSignOff signOff = snapshot == null ? null : snapshot.signOffs().get(KIND_PRIVACY);
        return signOff == null ? null : signOff.reference();
    }

    private String propertyReference() {
        if (properties == null) {
            return null;
        }
        FeatureSwitchProperties.Toggle toggle = properties.toggle(Feature.NF01_DUAL_CONFIRMATION);
        return toggle instanceof FeatureSwitchProperties.DualConfirmationToggle dual ? dual.getPrivacySignOffRef() : null;
    }
}
