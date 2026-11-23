package com.ses.importcenter.metadata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.config.feature.Feature;
import com.ses.entity.PermissionGroup;
import com.ses.entity.PermissionGroupAction;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.security.impl.AuthorizationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NF-06（{@link Feature#NF06}）の開放の条件（feature-enablement-console design §8.3、requirements §6.10。FE-B02）。profile に依らない
 * 評価器で、起動を止めていた2つの起動時検査の判定（{@link ImportAdmissionApproval#evaluate}・{@link ImportSourceRowReference#state()}）を
 * そのまま使う（条件の写しを持たない。design §4.4）。
 *
 * <ul>
 *   <li>(a) {@value #CODE_ADMISSION}: 評価に使う snapshot の確認済みの署名 {@code NF06_ADMISSION} の構造化された値（property の源は
 *       {@code app.import-center.admission.*}）を {@link ImportAdmissionApprovalProperties} に写して評価し、構造的な不足が無く期間の内で、
 *       承認 tenant が設定の tenant と一致する。署名日前・失効は {@code temporal=true}（D-8: 期間の外は NF06 全体を閉じる）。</li>
 *   <li>(b) {@value #CODE_ROW_REFERENCE_KEY}: 行参照の HMAC 鍵が使える（{@code state=AVAILABLE}）。</li>
 *   <li>(c) {@value #CODE_PERMISSION}: {@code import.metadata.manage} を管理者専用の group（{@code role-admin}・{@code privacy-admin}）
 *       が明示の許可行で許可し、それ以外の有効な group には group の許可・拒否を評価した結果として実効の許可が無い
 *       （{@link AuthorizationServiceImpl#groupAllows}。拒否行の有無で判定しないため、今後の migration で拒否行の無い group が増えても、
 *       許可が無ければ NF06 を閉じない。review P3-19）。{@code role-admin} は必須、{@code privacy-admin} は有効な group があれば許可を要する。</li>
 *   <li>(d) {@value #CODE_LEGAL_ENTITY}: 単一法人（{@link LegalEntityContextService#administratorLegalEntityCount()} がちょうど1）。</li>
 * </ul>
 * 照会の失敗は評価できない類の code（{@code *.unverifiable}。一度の失敗で閉じない。review P2-1）。code は固定の文字列と件数だけで、
 * 決定 ID・digest・source code を含めない。
 */
@Slf4j
@Component
public class ImportCenterEnablementConditions implements FeatureConditionEvaluator {

    public static final String CODE_ADMISSION = "importCenter.admission";
    public static final String CODE_ROW_REFERENCE_KEY = "importCenter.rowReferenceKey";
    public static final String CODE_PERMISSION = "importCenter.permission";
    public static final String CODE_LEGAL_ENTITY = "importCenter.legalEntity";
    /** 管理者専用の group（V193 で明示の許可行を持つ）。 */
    static final String ADMIN_GROUP = "role-admin";
    static final String PRIVACY_ADMIN_GROUP = "privacy-admin";

    private final ImportAdmissionApprovalSource approvalSource;
    private final ImportSourceRowReference rowReference;
    private final PermissionGroupMapper groupMapper;
    private final PermissionGroupActionMapper actionMapper;
    private final LegalEntityContextService legalEntityContextService;

    public ImportCenterEnablementConditions(ImportAdmissionApprovalSource approvalSource,
                                            ImportSourceRowReference rowReference, PermissionGroupMapper groupMapper,
                                            PermissionGroupActionMapper actionMapper,
                                            LegalEntityContextService legalEntityContextService) {
        this.approvalSource = Objects.requireNonNull(approvalSource, "approvalSource");
        this.rowReference = Objects.requireNonNull(rowReference, "rowReference");
        this.groupMapper = Objects.requireNonNull(groupMapper, "groupMapper");
        this.actionMapper = Objects.requireNonNull(actionMapper, "actionMapper");
        this.legalEntityContextService = Objects.requireNonNull(legalEntityContextService, "legalEntityContextService");
    }

    @Override
    public Set<Feature> features() {
        return Set.of(Feature.NF06);
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        return List.of(admission(context), rowReferenceKey(), permission(context.tenantId()), legalEntity());
    }

    /** (a) admission の決定。 */
    FeatureConditionCheck admission(FeatureEvaluationContext context) {
        ImportAdmissionApproval.Evaluation evaluation = ImportAdmissionApproval.evaluate(
                approvalSource.fromSnapshot(context.snapshot()), context.businessDate());
        if (!evaluation.complete()) {
            List<String> violations = evaluation.violations();
            List<String> structural = ImportAdmissionApproval.structuralViolations(evaluation);
            String first = structural.isEmpty() ? violations.get(0) : structural.get(0);
            return new FeatureConditionCheck(CODE_ADMISSION + "." + first, FeatureConditionCheck.Category.AUTO,
                    FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.UNSATISFIED, structural.isEmpty(),
                    null, violations);
        }
        if (!evaluation.snapshot().tenantId().equals(context.tenantId())) {
            return FeatureConditionCheck.unsatisfied(CODE_ADMISSION + ".tenant.notApproved",
                    FeatureConditionCheck.Category.AUTO, false);
        }
        return FeatureConditionCheck.satisfied(CODE_ADMISSION + ".complete", FeatureConditionCheck.Category.AUTO,
                evaluation.snapshot().expiresOn());
    }

    /** (b) 行参照の鍵。 */
    FeatureConditionCheck rowReferenceKey() {
        ImportSourceRowReference.KeyState state = rowReference.state();
        if (state == ImportSourceRowReference.KeyState.AVAILABLE) {
            return FeatureConditionCheck.satisfied(CODE_ROW_REFERENCE_KEY + ".available",
                    FeatureConditionCheck.Category.AUTO, null);
        }
        return new FeatureConditionCheck(CODE_ROW_REFERENCE_KEY + ".notUsable", FeatureConditionCheck.Category.AUTO,
                FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.UNSATISFIED, false, null,
                List.of("state=" + state));
    }

    /** (c) {@code import.metadata.manage} の実効の許可。 */
    FeatureConditionCheck permission(String tenantId) {
        List<PermissionGroup> groups;
        Map<Long, List<PermissionGroupAction>> rowsByGroup;
        try {
            groups = groupMapper.selectList(new LambdaQueryWrapper<PermissionGroup>()
                    .eq(PermissionGroup::getTenantId, tenantId)
                    .eq(PermissionGroup::getEnabled, 1));
            rowsByGroup = actionMapper.selectList(new LambdaQueryWrapper<PermissionGroupAction>()
                            .eq(PermissionGroupAction::getTenantId, tenantId)
                            .in(PermissionGroupAction::getActionKey,
                                    AuthorizationServiceImpl.candidateKeys(ImportMetadataAuthorizationService.ACTION)))
                    .stream().filter(row -> row.getGroupId() != null)
                    .collect(Collectors.groupingBy(PermissionGroupAction::getGroupId));
        } catch (RuntimeException e) {
            log.warn("[機能の開放] NF06 の権限の条件を照会できません（評価できない状態として扱います）: exceptionClass={}",
                    e.getClass().getSimpleName());
            return FeatureConditionCheck.unverifiable(CODE_PERMISSION + ".unverifiable", FeatureConditionCheck.Category.AUTO);
        }
        List<String> violations = new ArrayList<>();
        boolean adminGroupFound = false;
        int otherAllowed = 0;
        for (PermissionGroup group : groups == null ? List.<PermissionGroup>of() : groups) {
            List<PermissionGroupAction> rows = rowsByGroup.getOrDefault(group.getId(), List.of());
            boolean effective = AuthorizationServiceImpl.groupAllows(rows, ImportMetadataAuthorizationService.ACTION);
            if (ADMIN_GROUP.equals(group.getGroupKey()) || PRIVACY_ADMIN_GROUP.equals(group.getGroupKey())) {
                adminGroupFound |= ADMIN_GROUP.equals(group.getGroupKey());
                // service 層（ImportMetadataAuthorizationService）は全体baseline '*' を許可根拠に数えないため、明示の許可行も要る
                boolean explicit = rows.stream().anyMatch(row -> Integer.valueOf(0).equals(row.getDenyFlag())
                        && ImportMetadataAuthorizationService.ACTION.equals(row.getActionKey()));
                if (!effective || !explicit) {
                    violations.add(CODE_PERMISSION + ".adminGroup.notAllowed[group=" + group.getGroupKey() + "]");
                }
            } else if (effective) {
                otherAllowed++;
            }
        }
        if (!adminGroupFound) {
            violations.add(0, CODE_PERMISSION + ".adminGroup.missing[group=" + ADMIN_GROUP + "]");
        }
        if (otherAllowed > 0) {
            violations.add(CODE_PERMISSION + ".otherGroupAllowed[count=" + otherAllowed + "]");
        }
        if (violations.isEmpty()) {
            return FeatureConditionCheck.satisfied(CODE_PERMISSION + ".adminOnly", FeatureConditionCheck.Category.AUTO,
                    null);
        }
        return new FeatureConditionCheck(violations.get(0), FeatureConditionCheck.Category.AUTO,
                FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.UNSATISFIED, false, null, violations);
    }

    /** (d) 単一法人。 */
    FeatureConditionCheck legalEntity() {
        int count;
        try {
            count = legalEntityContextService.administratorLegalEntityCount();
        } catch (RuntimeException e) {
            log.warn("[機能の開放] NF06 の単一法人の条件を照会できません（評価できない状態として扱います）: exceptionClass={}",
                    e.getClass().getSimpleName());
            return FeatureConditionCheck.unverifiable(CODE_LEGAL_ENTITY + ".unverifiable",
                    FeatureConditionCheck.Category.AUTO);
        }
        if (count == 1) {
            return FeatureConditionCheck.satisfied(CODE_LEGAL_ENTITY + ".single", FeatureConditionCheck.Category.AUTO, null);
        }
        return new FeatureConditionCheck(CODE_LEGAL_ENTITY + ".notSingle", FeatureConditionCheck.Category.AUTO,
                FeatureConditionCheck.Scope.CONTINUOUS, FeatureConditionCheck.Status.UNSATISFIED, false, null,
                List.of("count=" + count));
    }
}
