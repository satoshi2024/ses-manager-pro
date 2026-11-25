package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureEnablementState;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.EnablementState;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 管理者の継続性 guard が、NF07 の開放状態と未決の申請を DB から読む部品（feature-enablement-console design §14。J-17。FE-C05）。
 *
 * <p>snapshot ではなく DB から読む（guard の transaction の中で最新を見るため。lock は取らない）。申請の作成と guard の並行は、承認の時の
 * 評価し直し（design §5.3）と定期の評価で閉じる。NF07 に再起動待ちの状態は無い（{@code ck_fe_state_restart} は NF05 だけ）が、
 * 開いた状態（{@code openLike}）として数える。</p>
 */
@Component
public class PrivacyEnablementStateReader {

    /** 読んだ結果。 */
    public enum Result {
        /** NF07 保持・保全か DSAR が開いている、または未決の申請がある（privacy-admin の人数を判定する）。 */
        ACTIVE,
        /** どちらも閉じていて未決の申請も無い（判定しない）。 */
        INACTIVE,
        /** 表を読めない（guard は利用者の管理の変更を全て拒否する）。 */
        UNREADABLE,
        /** NF07 を DB の開放状態で管理していない（従来どおり常に判定する）。 */
        NOT_DATABASE_MANAGED
    }

    private final FeatureEnablementProperties properties;
    private final FeatureEnablementStateMapper stateMapper;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FeatureEnablementTenant tenant;

    public PrivacyEnablementStateReader(FeatureEnablementProperties properties,
                                        FeatureEnablementStateMapper stateMapper,
                                        FeatureEnablementRequestMapper requestMapper, FeatureEnablementTenant tenant) {
        this.properties = properties;
        this.stateMapper = Objects.requireNonNull(stateMapper, "stateMapper");
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
    }

    /** NF07 の開放状態と未決の申請を読む。 */
    public Result read() {
        if (!PrivacyApprovalSourceConfiguration.databaseManaged(properties)) {
            return Result.NOT_DATABASE_MANAGED;
        }
        String tenantId = tenant.tenantId();
        try {
            List<FeatureEnablementState> states = stateMapper.selectByTenant(tenantId);
            if (states == null) {
                return Result.UNREADABLE;
            }
            for (FeatureEnablementState state : states) {
                if (state != null && isNf07(state.getFeatureKey()) && EnablementState.of(state.getState()).openLike()) {
                    return Result.ACTIVE;
                }
            }
            // 対象ごとの 1 行の照会（selectPendingByTarget）は未決が複数行あると例外になり、利用者の管理の変更を全て拒否してしまう
            // （review rb-fe-c P2-4）。tenant の未決を全て読んで NF07 の対象だけを数える
            List<FeatureEnablementRequest> pending = requestMapper.selectPending(tenantId);
            if (pending == null) {
                return Result.UNREADABLE;
            }
            for (FeatureEnablementRequest request : pending) {
                if (request != null && isNf07(request.getTargetKey())) {
                    return Result.ACTIVE;
                }
            }
            return Result.INACTIVE;
        } catch (RuntimeException e) {
            return Result.UNREADABLE;
        }
    }

    private static boolean isNf07(String featureKey) {
        return Feature.NF07_RETENTION.name().equals(featureKey) || Feature.NF07_DSAR.name().equals(featureKey);
    }
}
