package com.ses.service.compliance;

import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F2: 契約chainの抵触日算定（design §5.2 期間代数）。
 * 連続更新/クーリング/組織単位変更/同日開始/未来開始/並行契約の境界を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LimitationDateCalculatorTest {

    @Mock
    private SystemConfigService systemConfigService;

    private LimitationDateCalculator calculator;

    @BeforeEach
    void setUp() {
        lenient().when(systemConfigService.getInt(anyString(), eq(30))).thenReturn(30);
        lenient().when(systemConfigService.getInt(anyString(), eq(36))).thenReturn(36);
        lenient().when(systemConfigService.getInt(anyString(), eq(12))).thenReturn(12);
        calculator = new LimitationDateCalculator(systemConfigService);
    }

    private LimitationDateCalculator.ChainContract c(long id, String start, String end, Long workplace, String org) {
        return new LimitationDateCalculator.ChainContract(id, LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end), 1L, workplace, org);
    }

    @Test
    void 連続更新はchainを通算し更新のたびにリセットしない() {
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-06-30", 100L, "開発部"),
                c(2, "2026-07-01", "2026-12-31", 100L, "開発部"));
        // 事業所単位: 36ヶ月上限 → 2026-01-01 + 36ヶ月 = 2028-12-31の翌日相当（2029-01-01）
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-07-01"), 100L, "開発部", chain);
        assertEquals(LocalDate.parse("2029-01-01"), dates.workplaceDate(),
                "連続更新は通算され、2026-01-01起点のまま算定されるはず");
        // 組織単位: 12ヶ月上限 → 2026-01-01 + 12ヶ月 = 2027-01-01
        assertEquals(LocalDate.parse("2027-01-01"), dates.organizationDate(),
                "組織単位の抵触日はchain先頭から12ヶ月後");
    }

    @Test
    void クーリング空白が規定日数以上なら通算をリセットする() {
        // A終了2026-06-30 → B開始2026-09-01: 空白61日（>= 30日）でリセット
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-06-30", 100L, "開発部"),
                c(2, "2026-09-01", "2026-12-31", 100L, "開発部"));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-09-01"), 100L, "開発部", chain);
        assertEquals(LocalDate.parse("2027-09-01"), dates.organizationDate(),
                "クーリング後はB起点で再算定されるはず（リセット）");
    }

    @Test
    void クーリング未満の空白は継続として通算する() {
        // A終了2026-06-30 → B開始2026-07-15: 空白14日（< 30日）で継続
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-06-30", 100L, "開発部"),
                c(2, "2026-07-15", "2026-12-31", 100L, "開発部"));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-07-15"), 100L, "開発部", chain);
        assertEquals(LocalDate.parse("2027-01-01"), dates.organizationDate(),
                "クーリング未満の空白は継続としてA起点で通算されるはず");
    }

    @Test
    void 組織単位の変更は組織単位カウントでは別chainになる() {
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-06-30", 100L, "開発部"),
                c(2, "2026-07-01", "2026-12-31", 100L, "別部署"));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-07-01"), 100L, "別部署", chain);
        // 組織単位は別chain → 2026-07-01起点
        assertEquals(LocalDate.parse("2027-07-01"), dates.organizationDate(),
                "組織単位変更後は別chainで算定されるはず");
        // 事業所単位は同一workplace → 通算継続
        assertEquals(LocalDate.parse("2029-01-01"), dates.workplaceDate(),
                "事業所単位は組織変更の影響を受けず通算されるはず");
    }

    @Test
    void 同日開始の新契約は前契約を前日で閉じてchainを繋ぐ() {
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-06-30", 100L, "開発部"),
                c(2, "2026-06-30", "2026-12-31", 100L, "開発部"));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-06-30"), 100L, "開発部", chain);
        assertEquals(LocalDate.parse("2027-01-01"), dates.organizationDate(),
                "同日開始は連続として通算されるはず");
    }

    @Test
    void 未来開始の契約は先読みとしてchainに含まれる() {
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-06-30", 100L, "開発部"),
                c(2, "2026-07-01", "2026-12-31", 100L, "開発部"),
                c(3, "2027-01-01", "2027-06-30", 100L, "開発部"));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-07-01"), 100L, "開発部", chain);
        assertEquals(LocalDate.parse("2027-01-01"), dates.organizationDate(),
                "未来開始契約もchainへ含まれ、A起点で通算されるはず（先読み）");
    }

    @Test
    void 並行契約は同一chainへ通算され最も早い抵触日を採る() {
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-12-31", 100L, "開発部"),
                c(2, "2026-01-01", "2026-03-31", 100L, "開発部"));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-01-01"), 100L, "開発部", chain);
        assertEquals(LocalDate.parse("2027-01-01"), dates.organizationDate(),
                "並行契約は同一chain（同日開始）となり最早期限を採るはず");
    }

    @Test
    void 組織単位が未設定の場合は組織単位抵触日は算定不能null() {
        List<LimitationDateCalculator.ChainContract> chain = List.of(
                c(1, "2026-01-01", "2026-12-31", 100L, null));
        LimitationDateCalculator.LimitationDates dates =
                calculator.compute(LocalDate.parse("2026-01-01"), 100L, null, chain);
        assertNotNull(dates.workplaceDate(), "事業所単位は算定できるはず");
        assertNull(dates.organizationDate(), "組織単位が未設定なら算定不能（null）");
    }
}
