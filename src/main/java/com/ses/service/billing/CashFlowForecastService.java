package com.ses.service.billing;

import com.ses.dto.billing.CashFlowForecastDto;
import java.math.BigDecimal;
import java.time.YearMonth;

public interface CashFlowForecastService {
    /**
     * 資金繰り予測を計算する
     *
     * @param from 開始月
     * @param months 期間（月数）
     * @param openingBalance 期首残高（nullの場合はシステム設定から取得）
     * @return 資金繰り予測結果
     */
    CashFlowForecastDto forecast(YearMonth from, int months, BigDecimal openingBalance);
}
