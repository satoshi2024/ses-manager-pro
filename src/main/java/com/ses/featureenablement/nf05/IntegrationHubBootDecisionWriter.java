package com.ses.featureenablement.nf05;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementState;
import com.ses.featureenablement.service.FeatureEnablementWriteSupport;
import com.ses.featureenablement.state.EnablementState;
import com.ses.mapper.FeatureEnablementStateMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 起動の後の反映の書込み（feature-enablement-console design §9.2。FE-D02）。全て世代の行を最初に lock し（{@link
 * FeatureEnablementWriteSupport#begin}）、起動の前に読んだ状態と版が今も同じ時だけ遷移させる（読んだ後に別の instance が変えた行を
 * 上書きしない）。履歴・監査・INFO log は共通の部品が書く。
 */
@Component
public class IntegrationHubBootDecisionWriter {

    /** 起動の後の反映の結果。 */
    public enum Outcome { APPLIED, CLOSED, UNCHANGED }

    private final FeatureEnablementWriteSupport write;
    private final FeatureEnablementStateMapper stateMapper;

    public IntegrationHubBootDecisionWriter(FeatureEnablementWriteSupport write, FeatureEnablementStateMapper stateMapper) {
        this.write = write;
        this.stateMapper = stateMapper;
    }

    /** {@code OPEN_PENDING_RESTART} を {@code OPEN} にする（{@code restart_applied_at}・履歴 {@code RESTART_APPLIED}・監査）。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Outcome applyRestart(Feature feature, int readVersion) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.startupSystem());
        FeatureEnablementState row = write.lockState(context, feature);
        if (row == null || !EnablementState.OPEN_PENDING_RESTART.name().equals(row.getState())
                || row.getVersion() == null || row.getVersion() != readVersion) {
            write.finish(context);
            return Outcome.UNCHANGED;
        }
        UpdateWrapper<FeatureEnablementState> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("version", row.getVersion()).eq("state", EnablementState.OPEN_PENDING_RESTART.name())
                .set("state", EnablementState.OPEN.name()).set("restart_applied_at", context.now())
                .set("version", row.getVersion() + 1).set("updated_at", context.now());
        if (stateMapper.update(null, update) != 1) {
            write.finish(context);
            return Outcome.UNCHANGED;
        }
        write.event(context, "FEATURE", feature.name(), row.getId(), "RESTART_APPLIED",
                EnablementState.OPEN_PENDING_RESTART.name(), EnablementState.OPEN.name(), null, null);
        write.audit(context, "FEATURE_RESTART_APPLIED", row.getId(),
                "{\"feature\":\"" + feature.name() + "\",\"state\":\"OPEN_PENDING_RESTART\"}",
                "{\"feature\":\"" + feature.name() + "\",\"state\":\"OPEN\"}");
        context.markChanged(feature);
        write.finish(context);
        return Outcome.APPLIED;
    }

    /**
     * この起動で開かなかった開いている行を {@code AUTO_CLOSED(<code>)} にする（起動の前に読んだ状態と版が同じ時だけ。{@code readVersion}
     * が負なら版を問わない（起動の後に分かった理由: 法人の未解決・安全網））。outbound は閉鎖の hook が barrier を OFF に書き直す。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Outcome closeAtBoot(Feature feature, String readState, int readVersion, String reasonCode) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.startupSystem());
        FeatureEnablementState row = write.lockState(context, feature);
        boolean matches = row != null && EnablementState.of(row.getState()).openLike()
                && (readVersion < 0 || (row.getVersion() != null && row.getVersion() == readVersion
                && row.getState().equals(readState)));
        boolean closed = matches && write.closeUnit(context, feature, reasonCode, null);
        write.finish(context);
        return closed ? Outcome.CLOSED : Outcome.UNCHANGED;
    }

    /**
     * 起動の前に DB を読めなかった（{@code READ_FAILED}）。状態は変えず、開いている行に履歴 {@code BOOT_READ_FAILED} を残す（画面は
     * 「この起動では閉」）。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Outcome recordReadFailed(Feature feature) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.startupSystem());
        FeatureEnablementState row = write.lockState(context, feature);
        if (row == null || !EnablementState.of(row.getState()).openLike()) {
            write.finish(context);
            return Outcome.UNCHANGED;
        }
        write.event(context, "FEATURE", feature.name(), row.getId(), "BOOT_READ_FAILED", row.getState(), row.getState(),
                "NF05_BOOT_READ_FAILED", null);
        context.markChanged(feature);
        write.finish(context);
        return Outcome.APPLIED;
    }
}
