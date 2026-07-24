package com.ses.service;

import com.ses.entity.Contract;
import com.ses.entity.Engineer;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 稼働率の共通口径サービス(全社共通口径)。
 *
 * <p>口径: 契約ベース。対象月に有効な契約を持つ要員を「稼働」、持たない要員を「Bench」とみなす。
 * <ul>
 *   <li>対象契約: {@code status} が 稼動中/準備中/終了 のいずれか({@link #targetContractStatuses()})。</li>
 *   <li>有効: {@code startDate ≤ 対象月末} かつ ({@code endDate} が null または {@code endDate ≥ 対象月初})。</li>
 *   <li>{@code autoRenew=1} の契約は {@code assumeRenew=true} かつ {@code renewalDecision != 'END'} の場合、
 *       終了日以降も更新継続とみなす(将来月の見込み判定)。</li>
 * </ul>
 * ダッシュボードKPI(当月稼働率)と将来稼働率・Bench予測(FR-07)が本ロジックを共用することで、
 * 当月値が必ず一致することを保証する(A7-09 の口径分裂の再発防止)。
 */
public interface UtilizationCalcService {

    /** {@code forecast.assume-renew} 設定キー(自動更新契約を更新継続とみなすか)。 */
    String CONFIG_KEY_ASSUME_RENEW = "forecast.assume-renew";

    /**
     * 稼働判定の対象となる契約ステータス。呼び出し元はこのリストで契約をロードする。
     * 解約は稼働に寄与しないため対象外。
     */
    static List<String> targetContractStatuses() {
        return Arrays.asList(
                com.ses.common.constant.StatusConstants.CONTRACT_ACTIVE,
                com.ses.common.constant.StatusConstants.CONTRACT_PREPARING,
                com.ses.common.constant.StatusConstants.CONTRACT_ENDED
        );
    }

    /**
     * {@code forecast.assume-renew} 設定を解決する(既定 true)。
     * ダッシュボードと予測が同じ設定を参照するための共通ヘルパー。
     */
    static boolean resolveAssumeRenew(SystemConfigService systemConfigService) {
        return "true".equalsIgnoreCase(systemConfigService.getString(CONFIG_KEY_ASSUME_RENEW, "true"));
    }

    /**
     * 指定月に要員が稼働しているか(有効契約を持つか)を判定する。
     *
     * @param engineerContracts 当該要員の契約一覧(呼び出し元が一括ロード)
     * @param month 対象月
     * @param assumeRenew 自動更新契約を更新継続とみなすか
     */
    boolean isActiveInMonth(List<Contract> engineerContracts, YearMonth month, boolean assumeRenew);

    /**
     * 対象月の稼働数・Bench数・稼働率を集計する。
     *
     * @param month 対象月
     * @param engineers 対象要員(呼び出し元でスコープ適用済み)
     * @param contractsByEngineer 要員ID -> 契約一覧
     * @param assumeRenew 自動更新契約を更新継続とみなすか
     */
    UtilizationSnapshot calc(YearMonth month, List<Engineer> engineers,
                             Map<Long, List<Contract>> contractsByEngineer, boolean assumeRenew);

    /** 月次稼働集計結果。 */
    class UtilizationSnapshot {
        private final int workingCount;
        private final int benchCount;
        private final int totalCount;
        private final double utilizationRate;

        public UtilizationSnapshot(int workingCount, int benchCount, int totalCount, double utilizationRate) {
            this.workingCount = workingCount;
            this.benchCount = benchCount;
            this.totalCount = totalCount;
            this.utilizationRate = utilizationRate;
        }

        public int getWorkingCount() { return workingCount; }
        public int getBenchCount() { return benchCount; }
        public int getTotalCount() { return totalCount; }
        /** 稼働率(%、小数第1位で四捨五入)。 */
        public double getUtilizationRate() { return utilizationRate; }
    }
}
