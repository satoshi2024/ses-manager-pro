package com.ses.service.impl;

import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.service.billing.MonthlyRevenueCalcService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共通口径サービスの純粋ロジック検証(モック不要)。
 */
class MonthlyRevenueCalcServiceTest {

    private final MonthlyRevenueCalcServiceImpl service = new MonthlyRevenueCalcServiceImpl();

    private Contract contract(Long id, String status, LocalDate start, LocalDate end, String selling, String cost) {
        Contract c = new Contract();
        c.setId(id);
        c.setStatus(status);
        c.setStartDate(start);
        c.setEndDate(end);
        c.setSellingPrice(selling != null ? new BigDecimal(selling) : null);
        c.setCostPrice(cost != null ? new BigDecimal(cost) : null);
        return c;
    }

    private WorkRecord record(Long contractId, String billing, String payment) {
        WorkRecord w = new WorkRecord();
        w.setContractId(contractId);
        w.setBillingAmount(billing != null ? new BigDecimal(billing) : null);
        w.setPaymentAmount(payment != null ? new BigDecimal(payment) : null);
        return w;
    }

    @Test
    void resolveContractAmount_確定実績を優先する() {
        Contract c = contract(1L, "稼動中", LocalDate.of(2026, 7, 1), null, "800000", "500000");
        WorkRecord w = record(1L, "900000", "550000");

        MonthlyRevenueCalcService.ContractAmount a = service.resolveContractAmount(c, w);

        assertEquals(0, new BigDecimal("900000").compareTo(a.getSales()));
        assertEquals(0, new BigDecimal("550000").compareTo(a.getCost()));
        assertEquals(0, new BigDecimal("350000").compareTo(a.getProfit()));
        assertTrue(a.isActual());
    }

    @Test
    void resolveContractAmount_payment欠損時は粗利が売上と一致する() {
        Contract c = contract(1L, "稼動中", LocalDate.of(2026, 7, 1), null, "800000", "500000");
        WorkRecord w = record(1L, "900000", null);

        MonthlyRevenueCalcService.ContractAmount a = service.resolveContractAmount(c, w);

        assertEquals(0, new BigDecimal("900000").compareTo(a.getSales()));
        assertEquals(0, BigDecimal.ZERO.compareTo(a.getCost()));
        assertEquals(0, new BigDecimal("900000").compareTo(a.getProfit()));
        assertTrue(a.isActual());
    }

    @Test
    void resolveContractAmount_実績なしは契約単価にフォールバックする() {
        Contract c = contract(1L, "稼動中", LocalDate.of(2026, 7, 1), null, "800000", "500000");

        MonthlyRevenueCalcService.ContractAmount a = service.resolveContractAmount(c, null);

        assertEquals(0, new BigDecimal("800000").compareTo(a.getSales()));
        assertEquals(0, new BigDecimal("500000").compareTo(a.getCost()));
        assertFalse(a.isActual());
    }

    @Test
    void isTargetInMonth_準備中は除外する() {
        Contract c = contract(1L, "準備中", LocalDate.of(2026, 7, 1), null, "800000", "500000");
        assertFalse(service.isTargetInMonth(c, YearMonth.of(2026, 7)));
    }

    @Test
    void isTargetInMonth_期間境界() {
        YearMonth m = YearMonth.of(2026, 7);
        // 月末に開始 → 対象
        assertTrue(service.isTargetInMonth(contract(1L, "稼動中", LocalDate.of(2026, 7, 31), null, "1", "0"), m));
        // 翌月開始 → 対象外
        assertFalse(service.isTargetInMonth(contract(2L, "稼動中", LocalDate.of(2026, 8, 1), null, "1", "0"), m));
        // 終了日が月初 → 対象(重なり)
        assertTrue(service.isTargetInMonth(contract(3L, "終了", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), "1", "0"), m));
        // 終了日が前月末 → 対象外
        assertFalse(service.isTargetInMonth(contract(4L, "終了", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "1", "0"), m));
        // end_date null → 対象
        assertTrue(service.isTargetInMonth(contract(5L, "稼動中", LocalDate.of(2026, 6, 1), null, "1", "0"), m));
        // start_date null → 対象外
        assertFalse(service.isTargetInMonth(contract(6L, "稼動中", null, null, "1", "0"), m));
    }

    @Test
    void calc_実績あり月でも実績のない稼動契約を契約単価で計上する() {
        YearMonth m = YearMonth.of(2026, 7);
        Contract withActual = contract(1L, "稼動中", LocalDate.of(2026, 7, 1), null, "800000", "500000");
        Contract withoutActual = contract(2L, "稼動中", LocalDate.of(2026, 7, 1), null, "600000", "400000");
        Contract preparing = contract(3L, "準備中", LocalDate.of(2026, 7, 1), null, "1000000", "0");

        Map<Long, WorkRecord> confirmed = new HashMap<>();
        confirmed.put(1L, record(1L, "900000", "550000")); // 実績 sales 900k profit 350k

        MonthlyRevenueCalcService.MonthlyAmount amount = service.calc(
                m, List.of(withActual, withoutActual, preparing), confirmed);

        // 実績(900k) + 見込み(600k) = 1,500,000。準備中(1,000,000)は除外。
        assertEquals(1500000L, amount.getSales());
        // 粗利: 350k(実績) + 200k(見込み) = 550,000
        assertEquals(550000L, amount.getProfit());
        assertTrue(amount.isHasActual());
    }

    @Test
    void calc_実績が一切ない月はhasActualがfalse() {
        YearMonth m = YearMonth.of(2026, 7);
        Contract c = contract(1L, "稼動中", LocalDate.of(2026, 7, 1), null, "600000", "400000");

        MonthlyRevenueCalcService.MonthlyAmount amount = service.calc(m, List.of(c), new HashMap<>());

        assertEquals(600000L, amount.getSales());
        assertEquals(200000L, amount.getProfit());
        assertFalse(amount.isHasActual());
    }
}
