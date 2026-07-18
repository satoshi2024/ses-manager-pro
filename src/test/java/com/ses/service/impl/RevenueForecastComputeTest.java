package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.entity.Proposal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 売上着地予測の加重計算（純関数）の単体テスト。DB・config 不要。
 */
class RevenueForecastComputeTest {

    private static final Map<String, BigDecimal> RATES = Map.of(
            StatusConstants.PROPOSAL_DOCUMENT_SCREENING, new BigDecimal("20"),
            StatusConstants.PROPOSAL_FIRST_INTERVIEW, new BigDecimal("40"),
            StatusConstants.PROPOSAL_SECOND_INTERVIEW, new BigDecimal("60"),
            StatusConstants.PROPOSAL_WAITING_RESULT, new BigDecimal("80"));

    private Proposal proposal(String status, String price) {
        Proposal p = new Proposal();
        p.setStatus(status);
        p.setProposedUnitPrice(price == null ? null : new BigDecimal(price));
        return p;
    }

    @Test
    void 四ステージの加重合算() {
        List<Proposal> open = List.of(
                proposal(StatusConstants.PROPOSAL_DOCUMENT_SCREENING, "500000"),   // 20% = 100000
                proposal(StatusConstants.PROPOSAL_FIRST_INTERVIEW, "500000"),      // 40% = 200000
                proposal(StatusConstants.PROPOSAL_SECOND_INTERVIEW, "500000"),     // 60% = 300000
                proposal(StatusConstants.PROPOSAL_WAITING_RESULT, "500000"));      // 80% = 400000
        assertEquals(1_000_000L, DashboardServiceImpl.computePipelinePerMonth(open, RATES));
    }

    @Test
    void NULL単価は0() {
        List<Proposal> open = List.of(proposal(StatusConstants.PROPOSAL_WAITING_RESULT, null));
        assertEquals(0L, DashboardServiceImpl.computePipelinePerMonth(open, RATES));
    }

    @Test
    void 未知ステータスは0() {
        List<Proposal> open = List.of(proposal("成約", "500000"));
        assertEquals(0L, DashboardServiceImpl.computePipelinePerMonth(open, RATES));
    }

    @Test
    void 提案ごと切り捨て() {
        // 333333 * 20% = 66666.6 → 提案ごとに切り捨て 66666。2件で 133332（合算後に丸めない）。
        List<Proposal> open = List.of(
                proposal(StatusConstants.PROPOSAL_DOCUMENT_SCREENING, "333333"),
                proposal(StatusConstants.PROPOSAL_DOCUMENT_SCREENING, "333333"));
        assertEquals(133_332L, DashboardServiceImpl.computePipelinePerMonth(open, RATES));
    }
}
