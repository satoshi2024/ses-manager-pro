package com.ses.privacy.production;

import com.ses.featureenablement.catalog.Subgate;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.evaluation.SubgateMonitor;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * NF07 保持・保全の副入口の署名を、入口の gate が要求ごとに読む部品（review rb-fe-c P2-1。requirements §6.11）。
 *
 * <p>副入口（hold 開始 {@code HOLD_START}・hold 解除 {@code REL}・policy の登録と有効化 {@code REG}）の目録の署名（決定の類と証拠の類。
 * 例: {@code NF07_D05_OWNER_ACCEPTANCE}・{@code NF07_TRUST_KMS}・{@code NF07_TRUST_STAGING}）が確認済みで有効かを、開放状態の snapshot
 * （memory）から {@link SubgateMonitor#signOffsValid} で判定する。署名の取消し・失効は副入口だけを閉じ、保持・保全の開放単位は閉じない
 * （D-05 の失効と同じ。review P3-16）。監視の通知（{@code SUBGATE_CLOSED}）と入口の挙動が一致する。</p>
 *
 * <p>property の源（dev・test）では判定しない（常に true。決定は従来どおり {@code privacy.approval.*} から各入口が読む）。部品が揃わなければ
 * false（閉）。</p>
 */
@Component
public class PrivacySubgateSignOffs {

    public static final String HOLD_START = "HOLD_START";
    public static final String RELEASE = "REL";
    public static final String REGISTRATION = "REG";

    private final FeatureEnablementProperties properties;
    private final ObjectProvider<FeatureEnablementSnapshotHolder> holder;
    private final ObjectProvider<SubgateMonitor> monitor;
    private final ObjectProvider<Clock> clock;

    public PrivacySubgateSignOffs(FeatureEnablementProperties properties,
                                  ObjectProvider<FeatureEnablementSnapshotHolder> holder,
                                  ObjectProvider<SubgateMonitor> monitor, ObjectProvider<Clock> clock) {
        this.properties = properties;
        this.holder = holder;
        this.monitor = monitor;
        this.clock = clock;
    }

    /** 副入口の署名が有効か（DB の源でなければ true。判定できなければ false）。 */
    public boolean valid(String subgateCode) {
        if (!PrivacyApprovalSourceConfiguration.databaseManaged(properties)) {
            return true;
        }
        try {
            FeatureEnablementSnapshotHolder snapshots = holder == null ? null : holder.getIfAvailable();
            SubgateMonitor subgates = monitor == null ? null : monitor.getIfAvailable();
            Clock resolved = clock == null ? null : clock.getIfAvailable();
            if (snapshots == null || subgates == null) {
                return false;
            }
            Optional<Subgate> subgate = subgates.subgate(subgateCode);
            return subgate.isPresent() && subgates.signOffsValid(snapshots.get(), subgate.get(),
                    FeatureEvaluationContext.businessDate(resolved == null ? Clock.systemUTC() : resolved));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
