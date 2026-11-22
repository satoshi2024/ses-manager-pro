package com.ses.featureenablement.evaluation;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureEnablementState;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.service.FeatureEnablementWriteSupport;
import com.ses.featureenablement.service.FeatureSignOffTransitions;
import com.ses.featureenablement.state.EnablementState;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import com.ses.mapper.FeatureSignOffMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 継続評価の書込み（feature-enablement-console design §7.4。それぞれ1つの transaction で、世代の行を最初に lock し、失効の先行の
 * 閉鎖を含む）。全て system の行為者（定期は {@code system:feature-enablement/scheduler}、起動時は {@code …/startup}）。
 */
@Service
public class FeatureEnablementEvaluationWriter {

    private final FeatureEnablementWriteSupport write;
    private final FeatureSignOffTransitions transitions;
    private final FeatureEnablementStateMapper stateMapper;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FeatureSignOffMapper signOffMapper;

    public FeatureEnablementEvaluationWriter(FeatureEnablementWriteSupport write, FeatureSignOffTransitions transitions,
                                             FeatureEnablementStateMapper stateMapper,
                                             FeatureEnablementRequestMapper requestMapper,
                                             FeatureSignOffMapper signOffMapper) {
        this.write = write;
        this.transitions = transitions;
        this.stateMapper = stateMapper;
        this.requestMapper = requestMapper;
        this.signOffMapper = signOffMapper;
    }

    /**
     * 評価できない状態の始まりを書く（既に書いてあれば変えない）。
     *
     * @return 書いてある始まりの時刻（開いていなければ null）
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public LocalDateTime markUnverifiable(Feature feature, String reasonCode, boolean startup) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(actor(startup));
        FeatureEnablementState row = write.lockState(context, feature);
        if (row == null || !EnablementState.of(row.getState()).openLike()) {
            write.finish(context);
            return null;
        }
        if (row.getUnverifiableSince() != null) {
            write.finish(context);
            return row.getUnverifiableSince();
        }
        UpdateWrapper<FeatureEnablementState> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("version", row.getVersion())
                .set("unverifiable_since", context.now()).set("unverifiable_reason", truncate(reasonCode))
                .set("version", row.getVersion() + 1).set("updated_at", context.now());
        if (stateMapper.update(null, update) != 1) {
            throw new IllegalStateException("評価できない状態の始まりを書けません");
        }
        write.event(context, "FEATURE", feature.name(), row.getId(), "EVALUATION_UNVERIFIABLE", row.getState(),
                row.getState(), truncate(reasonCode), null);
        context.markChanged(feature);
        write.finish(context);
        return context.now();
    }

    /** 評価できない状態の始まりを消す（回復）。消したら true。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean clearUnverifiable(Feature feature, boolean startup) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(actor(startup));
        FeatureEnablementState row = write.lockState(context, feature);
        if (row == null || row.getUnverifiableSince() == null) {
            write.finish(context);
            return false;
        }
        UpdateWrapper<FeatureEnablementState> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("version", row.getVersion())
                .set("unverifiable_since", null).set("unverifiable_reason", null)
                .set("version", row.getVersion() + 1).set("updated_at", context.now());
        if (stateMapper.update(null, update) != 1) {
            throw new IllegalStateException("評価できない状態の始まりを消せません");
        }
        write.event(context, "FEATURE", feature.name(), row.getId(), "EVALUATION_RECOVERED", row.getState(),
                row.getState(), row.getUnverifiableReason(), null);
        context.markChanged(feature);
        write.finish(context);
        return true;
    }

    /** 失効日を過ぎた確認済みの署名を {@code EXPIRED} にする（それを要する開放単位は世代の lock の直後に先に閉じる）。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int markExpiredSignOffs() {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.scheduler());
        int expired = 0;
        for (FeatureSignOff found : signOffMapper.selectExpiredConfirmed(context.tenantId(), context.today())) {
            FeatureSignOff row = signOffMapper.selectByIdForUpdate(found.getId());
            if (row != null && "CONFIRMED".equals(row.getStatus()) && !row.getExpiresOn().isAfter(context.today())) {
                transitions.endSignOff(context, row, "EXPIRED", null, "EXPIRES_ON_REACHED", null);
                expired++;
            }
        }
        write.finish(context);
        return expired;
    }

    /** 未決の申請を全て {@code CANCELLED} にする（総スイッチが閉じた時）。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int cancelAllPending(String reasonCode, boolean startup) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(actor(startup));
        int cancelled = 0;
        for (FeatureEnablementRequest pending : requestMapper.selectPending(context.tenantId())) {
            cancelled += write.cancelPending(context, pending.getTargetKey(), reasonCode);
        }
        write.finish(context);
        return cancelled;
    }

    /**
     * 開放の時だけの条件の期限の接近・喪失を監査に残す（閉鎖の根拠にしない。design §7.4）。
     *
     * @param auditCode {@code FEATURE_OPEN_CONDITION_EXPIRING} か {@code FEATURE_OPEN_CONDITION_LOST}
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void recordOpenConditionWarning(Feature feature, String checkCode, String auditCode) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.scheduler());
        write.audit(context, auditCode, null, null,
                "{\"feature\":\"" + feature.name() + "\",\"check\":\"" + truncate(checkCode) + "\"}");
        write.finish(context);
    }

    /** 副入口の成否の変化を履歴に残す（副入口は DB の状態を持たない。design §7.4）。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void recordSubgateChange(String subgateCode, boolean open) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.scheduler());
        String event = open ? "SUBGATE_OPENED" : "SUBGATE_CLOSED";
        write.event(context, "SUBGATE", subgateCode, null, event, open ? "CLOSED" : "OPEN", open ? "OPEN" : "CLOSED",
                null, null);
        write.audit(context, "FEATURE_" + event, null, null, "{\"subgate\":\"" + subgateCode + "\"}");
        write.finish(context);
    }

    /** 書いた始まりの時刻を Instant にする（JVM の時刻帯で書いた行の時刻）。 */
    static Instant toInstant(LocalDateTime rowTimestamp) {
        return rowTimestamp == null ? null : rowTimestamp.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private static FeatureEnablementWriteSupport.Actor actor(boolean startup) {
        return startup ? FeatureEnablementWriteSupport.Actor.startupSystem()
                : FeatureEnablementWriteSupport.Actor.scheduler();
    }

    private static String truncate(String code) {
        return code == null || code.length() <= 64 ? code : code.substring(0, 64);
    }
}
