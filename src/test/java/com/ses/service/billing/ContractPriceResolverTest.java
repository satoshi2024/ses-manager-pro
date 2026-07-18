package com.ses.service.billing;

import com.ses.entity.Contract;
import com.ses.entity.ContractPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 単価リゾルバの純ロジック（resolveFrom）テスト。
 */
class ContractPriceResolverTest {

    private Contract contract(String selling, String cost) {
        Contract c = new Contract();
        c.setId(1L);
        c.setSellingPrice(new BigDecimal(selling));
        c.setCostPrice(new BigDecimal(cost));
        return c;
    }

    private ContractPriceHistory hist(String month, String selling, String cost) {
        ContractPriceHistory h = new ContractPriceHistory();
        h.setContractId(1L);
        h.setApplyFromMonth(month);
        h.setSellingPrice(new BigDecimal(selling));
        h.setCostPrice(new BigDecimal(cost));
        return h;
    }

    @Test
    void 履歴なしは現在単価() {
        ContractPriceResolver.ResolvedPrice r =
                ContractPriceResolver.resolveFrom(contract("800000", "600000"), YearMonth.of(2026, 7), List.of());
        assertEquals(0, new BigDecimal("800000").compareTo(r.getSellingPrice()));
        assertFalse(r.isFromHistory());
    }

    @Test
    void 適用開始月ちょうどは新単価_前月は旧単価() {
        List<ContractPriceHistory> hs = List.of(
                hist("2026-04", "800000", "600000"),
                hist("2026-08", "850000", "620000"));
        Contract c = contract("850000", "620000");

        ContractPriceResolver.ResolvedPrice july =
                ContractPriceResolver.resolveFrom(c, YearMonth.of(2026, 7), hs);
        assertEquals(0, new BigDecimal("800000").compareTo(july.getSellingPrice()));

        ContractPriceResolver.ResolvedPrice aug =
                ContractPriceResolver.resolveFrom(c, YearMonth.of(2026, 8), hs);
        assertEquals(0, new BigDecimal("850000").compareTo(aug.getSellingPrice()));
        assertTrue(aug.isFromHistory());
    }

    @Test
    void 複数履歴の最新選択() {
        List<ContractPriceHistory> hs = List.of(
                hist("2026-04", "800000", "600000"),
                hist("2026-06", "820000", "610000"),
                hist("2026-08", "850000", "620000"));
        ContractPriceResolver.ResolvedPrice r =
                ContractPriceResolver.resolveFrom(contract("850000", "620000"), YearMonth.of(2026, 7), hs);
        assertEquals(0, new BigDecimal("820000").compareTo(r.getSellingPrice()));
    }

    @Test
    void 将来予約は対象月前なら不適用() {
        List<ContractPriceHistory> hs = List.of(
                hist("2026-04", "800000", "600000"),
                hist("2026-12", "900000", "650000"));
        ContractPriceResolver.ResolvedPrice r =
                ContractPriceResolver.resolveFrom(contract("800000", "600000"), YearMonth.of(2026, 7), hs);
        assertEquals(0, new BigDecimal("800000").compareTo(r.getSellingPrice()));
    }
}
