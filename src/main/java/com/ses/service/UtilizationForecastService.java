package com.ses.service;

import com.ses.dto.dashboard.UtilizationForecastDto;
import java.time.YearMonth;

/**
 * 将来稼働率・Bench予測サービス（FR-07）
 */
public interface UtilizationForecastService {

    /**
     * 指定された月数（既定3ヶ月）の将来稼働率・Bench予測およびロールオフ予定要員一覧を取得する。
     *
     * @param months 予測対象月数（1〜12）
     * @return 予測結果DTO
     */
    UtilizationForecastDto getForecast(int months);

    /** 指定月を起点にする既存予測口径。reportは現在月へ暗黙依存しない。 */
    default UtilizationForecastDto getForecast(YearMonth from, int months) {
        return getForecast(months);
    }
}
