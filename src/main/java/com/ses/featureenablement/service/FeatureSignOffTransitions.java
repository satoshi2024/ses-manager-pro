package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.condition.SignOffRequirements;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.mapper.FeatureSignOffMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 署名・組の遷移の共通部品（feature-enablement-console design §6.5・§6.6。1件の署名と組の service が同じ規則で書く）。
 *
 * <ul>
 *   <li>依存する開放単位の状態の行を、署名の行より先に lock する（lock の順 (2) → (4)。design §5.1）。</li>
 *   <li>差替えの後は、それを要する開いている開放単位を {@code Purpose.CHANGE}（{@code remoteChecksAllowed=false}）で評価し直し、
 *       継続の条件が成り立たなければ同じ transaction で自動閉鎖する。取消しは評価せずに閉じる。</li>
 *   <li>終わった署名（{@code SUPERSEDED}・{@code EXPIRED}・{@code REVOKED}・{@code REJECTED}）には消去の期日
 *       （{@code purge_after}）を書く: 失効はその失効日、他は遷移の日の3年後（J-14。延長した行は延長の後の失効日）。</li>
 * </ul>
 */
@Component
public class FeatureSignOffTransitions {

    /** 消去までの年数（J-14）。 */
    public static final int RETENTION_YEARS = 3;

    private final FeatureEnablementWriteSupport write;
    private final FeatureConditionRegistry registry;
    private final FeatureEnablementCatalog catalog;
    private final FeatureSignOffMapper signOffMapper;

    public FeatureSignOffTransitions(FeatureEnablementWriteSupport write, FeatureConditionRegistry registry,
                                     FeatureEnablementCatalog catalog, FeatureSignOffMapper signOffMapper) {
        this.write = write;
        this.registry = registry;
        this.catalog = catalog;
        this.signOffMapper = signOffMapper;
    }

    /** 種別を要する開放単位とその子（lock の対象）。 */
    public Set<Feature> dependents(Collection<String> kinds) {
        Set<Feature> features = EnumSet.noneOf(Feature.class);
        for (String kind : kinds) {
            for (Feature feature : catalog.unitsRequiring(kind)) {
                features.add(feature);
                features.addAll(catalog.children(feature));
            }
        }
        return features;
    }

    /** 依存する開放単位の状態の行を lock する（署名の行より先）。 */
    public void lockDependents(FeatureEnablementWriteSupport.WriteContext context, Collection<String> kinds) {
        write.lockStates(context, dependents(kinds));
    }

    /**
     * 読み直した状態で、種別を要する開いている開放単位を {@code CHANGE} で評価し直し、継続の条件が成り立たなければ自動閉鎖する。
     *
     * @return 閉じた開放単位（子の cascade を含まない）
     */
    public List<Feature> reevaluateDependents(FeatureEnablementWriteSupport.WriteContext context, Collection<String> kinds,
                                              String reasonCode) {
        FeatureEnablementSnapshot snapshot = context.reread();
        List<Feature> closed = new ArrayList<>();
        for (Feature feature : openRequiring(snapshot, kinds)) {
            if (!context.snapshot().unit(feature).state().openLike()) {
                continue;
            }
            FeatureEvaluation evaluation = registry.evaluate(feature, new FeatureEvaluationContext(context.tenantId(),
                    context.today(), write.clock(), context.snapshot(), FeatureEvaluationContext.Purpose.CHANGE, Set.of(),
                    false));
            if (!evaluation.closingUnsatisfied().isEmpty() && write.closeUnit(context, feature, reasonCode, null)) {
                closed.add(feature);
                context.reread();
            }
        }
        return closed;
    }

    /**
     * 種別を要する開いている開放単位を閉じる（取消し。評価しない）。
     *
     * @return 閉じた開放単位（子の cascade を含まない）
     */
    public List<Feature> closeDependents(FeatureEnablementWriteSupport.WriteContext context, Collection<String> kinds,
                                         String reasonCode) {
        FeatureEnablementSnapshot snapshot = context.reread();
        List<Feature> closed = new ArrayList<>();
        for (Feature feature : openRequiring(snapshot, kinds)) {
            if (write.closeUnit(context, feature, reasonCode, null)) {
                closed.add(feature);
            }
        }
        if (!closed.isEmpty()) {
            context.reread();
        }
        return closed;
    }

    /**
     * 署名の行を終える（CAS。{@code ended_*} と消去の期日を書く）。
     *
     * @param endedBy 却下・取下げ・取消しの管理者（差替え・失効は null）
     */
    public void endSignOff(FeatureEnablementWriteSupport.WriteContext context, FeatureSignOff row, String status,
                           Long endedBy, String reasonCode, Long supersededById) {
        UpdateWrapper<FeatureSignOff> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("status", row.getStatus()).eq("version", row.getVersion())
                .set("status", status).set("ended_by", endedBy).set("ended_at", context.now())
                .set("end_reason", reasonCode).set("purge_after", purgeAfter(status, row.getExpiresOn(), context.today()))
                .set("version", row.getVersion() + 1);
        if (supersededById != null) {
            update.set("superseded_by_id", supersededById);
        }
        if (signOffMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        write.event(context, "SIGN_OFF", row.getKindCode(), row.getId(), status, row.getStatus(), status, reasonCode, null);
        write.audit(context, "FEATURE_SIGN_OFF_" + status, row.getId(),
                "{\"signOff\":" + row.getId() + ",\"kind\":\"" + row.getKindCode() + "\",\"status\":\"" + row.getStatus()
                        + "\"}",
                "{\"signOff\":" + row.getId() + ",\"kind\":\"" + row.getKindCode() + "\",\"status\":\"" + status
                        + "\"" + (reasonCode == null ? "" : ",\"reason\":\"" + reasonCode + "\"") + "}");
        context.markChanged(null);
    }

    /** 終わった署名の消去の期日（失効はその失効日、他は遷移の日の3年後）。 */
    public static LocalDate purgeAfter(String status, LocalDate expiresOn, LocalDate today) {
        LocalDate base = "EXPIRED".equals(status) && expiresOn != null ? expiresOn : today;
        return base.plusYears(RETENTION_YEARS);
    }

    private Set<Feature> openRequiring(FeatureEnablementSnapshot snapshot, Collection<String> kinds) {
        Set<Feature> result = new LinkedHashSet<>();
        for (Feature feature : Feature.values()) {
            if (!snapshot.unit(feature).state().openLike() || catalog.units().stream()
                    .noneMatch(unit -> unit.feature() == feature)) {
                continue;
            }
            List<String> applicable = SignOffRequirements.applicableKinds(catalog, feature, snapshot.settings());
            if (kinds.stream().anyMatch(applicable::contains)) {
                result.add(feature);
            }
        }
        return result;
    }
}
