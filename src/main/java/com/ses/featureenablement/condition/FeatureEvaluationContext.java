package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * 条件を評価する時の入力（design §4.2）。
 *
 * @param tenantId            設定の tenant
 * @param businessDate        JST の業務日（今日）
 * @param clock               時計
 * @param snapshot            評価に使う状態（書込みの transaction の中では lock の後に読み直した snapshot。memory の snapshot を使わない）
 * @param purpose             評価の目的
 * @param openAfterChange     変更の後に開いている開放単位（承認しようとしている開放単位を含める）
 * @param remoteChecksAllowed 外部への検査（Journal の権限の再検査・truststore の読取り等）をしてよいか（lock を持つ transaction の中では
 *                            false。最後の結果を使う）
 */
public record FeatureEvaluationContext(String tenantId, LocalDate businessDate, Clock clock,
                                       FeatureEnablementSnapshot snapshot, Purpose purpose, Set<Feature> openAfterChange,
                                       boolean remoteChecksAllowed) {

    public FeatureEvaluationContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(purpose, "purpose");
        openAfterChange = openAfterChange == null ? Set.of() : Set.copyOf(openAfterChange);
    }

    /** JST の業務日（署名日・失効日の判定に使う。JST 0:00 で変わる。design §3.6）。 */
    public static LocalDate businessDate(Clock clock) {
        return LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Tokyo")));
    }

    /**
     * 評価の目的。BOOT_PENDING: NF05 の OPEN_PENDING_RESTART を反映する起動（OPEN_TRANSITION を判定する）。
     * DISPLAY: 画面の再評価（状態を遷移させない）。
     */
    public enum Purpose {
        STARTUP, BOOT_PENDING, PERIODIC, CHANGE, REQUEST, APPROVAL, DISPLAY;

        /** 開く遷移（申請・承認）か。全ての scope の条件を開閉の根拠にする。 */
        public boolean opening() {
            return this == REQUEST || this == APPROVAL;
        }

        /** 開放の遷移の前提（OPEN_TRANSITION）を判定する目的か。 */
        public boolean judgesOpenTransition() {
            return this == REQUEST || this == APPROVAL || this == BOOT_PENDING;
        }
    }
}
