package com.ses.service.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SettlementCalculator {

    private SettlementCalculator() {
        // utility class
    }

    /**
     * 精算計算を行う
     * @param unitPriceYen 単価(円, 売上 or 原価)
     * @param hoursMin 精算下限(h, null=固定)
     * @param hoursMax 精算上限(h, null=固定)
     * @param actualHours 実績時間
     * @return 精算後金額(円, 1円未満切り捨て)
     */
    public static BigDecimal calc(BigDecimal unitPriceYen, BigDecimal hoursMin,
                                  BigDecimal hoursMax, BigDecimal actualHours) {
        if (unitPriceYen == null) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal base = unitPriceYen;
        
        if (hoursMin == null || hoursMax == null || actualHours == null) {
            return base; // 固定
        }

        // 精算上下限に0以下が入っているとゼロ除算になるため固定扱いにする
        if (hoursMin.signum() <= 0 || hoursMax.signum() <= 0) {
            return base;
        }

        if (actualHours.compareTo(hoursMax) > 0) { // 超過
            BigDecimal over = base.divide(hoursMax, 10, RoundingMode.HALF_UP); // 超過単価 = 売上/上限
            return base.add(actualHours.subtract(hoursMax).multiply(over)).setScale(0, RoundingMode.DOWN);
        }
        
        if (actualHours.compareTo(hoursMin) < 0) { // 控除
            BigDecimal under = base.divide(hoursMin, 10, RoundingMode.HALF_UP); // 控除単価 = 売上/下限
            return base.subtract(hoursMin.subtract(actualHours).multiply(under)).setScale(0, RoundingMode.DOWN);
        }
        
        return base;
    }
}
