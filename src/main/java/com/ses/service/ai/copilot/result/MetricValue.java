package com.ses.service.ai.copilot.result;

import java.math.BigDecimal;

/** typed resultの1指標。LLMへはkeyとredacted stateのみを渡す（F2ではsummary未使用）。 */
public record MetricValue(
        String key,
        BigDecimal numericValue,
        Long longValue,
        MetricUnit unit,
        MetricState state,
        String period,
        MetricBasis basis,
        Integer displayScale
) {
    public static MetricValue yen(String key, long amount, String period, MetricBasis basis) {
        MetricState state = amount == 0 ? MetricState.ZERO : MetricState.VALUE;
        return new MetricValue(key, BigDecimal.valueOf(amount), amount, MetricUnit.YEN, state, period, basis, 0);
    }

    public static MetricValue percent(String key, double value, String period, MetricBasis basis) {
        MetricState state = Double.isNaN(value) ? MetricState.UNCONFIRMED
                : value == 0d ? MetricState.ZERO : MetricState.VALUE;
        return new MetricValue(key, BigDecimal.valueOf(value), null, MetricUnit.PERCENT, state, period, basis, 1);
    }

    public static MetricValue count(String key, int value, String period, MetricBasis basis) {
        MetricState state = value == 0 ? MetricState.ZERO : MetricState.VALUE;
        return new MetricValue(key, BigDecimal.valueOf(value), (long) value, MetricUnit.COUNT, state, period, basis, 0);
    }

    public static MetricValue nullableYen(String key, BigDecimal amount, String period, MetricBasis basis) {
        if (amount == null) {
            return new MetricValue(key, null, null, MetricUnit.YEN, MetricState.NULL, period, basis, 0);
        }
        return yen(key, amount.longValue(), period, basis);
    }
}
