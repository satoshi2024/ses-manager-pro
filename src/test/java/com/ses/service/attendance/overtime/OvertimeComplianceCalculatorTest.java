package com.ses.service.attendance.overtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.attendance.overtime.OvertimeAgreementSnapshot;
import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;
import com.ses.dto.attendance.overtime.OvertimeComplianceInput;
import com.ses.dto.attendance.overtime.OvertimeComplianceSeverity;
import com.ses.dto.attendance.overtime.OvertimeMonthMinutes;
import com.ses.dto.attendance.overtime.OvertimeRule;
import com.ses.entity.SystemConfig;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * overtime-rules.md §5 の境界fixtureをsrc/test/resources/fixtures/overtime/から読み込み、
 * OvertimeComplianceCalculatorの判定を固定する。Spring contextは使わない（純粋な計算のみ）。
 *
 * <p>境界fixtureはconfig値（{@link OvertimeLimitDefaults}、第2/3段が実際に解決する値）からの
 * deltaで表現し、リテラルの分数をJSONへ直書きしない（overtime-rules.md §5）。</p>
 */
class OvertimeComplianceCalculatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long LEGAL_ENTITY_ID = 1L;
    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 4);
    private static final OvertimeAgreementSnapshot AGREEMENT_NO_OVERRIDE =
            new OvertimeAgreementSnapshot(true, null, null, null, null, null, null);

    private final OvertimeComplianceCalculator calculator = new OvertimeComplianceCalculator(new NoOverrideSystemConfigService());

    // ---- ルール1: 月45時間（休日労働を含まない）。超過のみ違反。 ----

    @ParameterizedTest(name = "delta={0}")
    @MethodSource("rule1Cases")
    void rule1MonthNormalBoundary(DeltaCase c) {
        int value = OvertimeLimitDefaults.MONTH_NORMAL_MINUTES + c.delta;
        OvertimeMonthMinutes month = new OvertimeMonthMinutes(TARGET_MONTH, value, value);
        OvertimeComplianceInput input = baseInput(month, List.of(month), List.of(month));
        assertOnlyRule(input, OvertimeRule.RULE1_MONTH_NORMAL, c.expectedViolation);
    }

    static List<DeltaCase> rule1Cases() throws IOException {
        return loadDeltaCases("rule1-month-normal-boundary.json");
    }

    // ---- ルール2: 年360時間（協定年度累計、休日労働を含まない）。超過のみ違反。 ----

    @ParameterizedTest(name = "delta={0}")
    @MethodSource("rule2Cases")
    void rule2YearNormalBoundary(DeltaCase c) {
        int value = OvertimeLimitDefaults.YEAR_NORMAL_MINUTES + c.delta;
        OvertimeMonthMinutes safeCurrent = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeMonthMinutes yearMonth = new OvertimeMonthMinutes(TARGET_MONTH, value, 0);
        OvertimeComplianceInput input = baseInput(safeCurrent, List.of(yearMonth), List.of(safeCurrent));
        assertOnlyRule(input, OvertimeRule.RULE2_YEAR_NORMAL, c.expectedViolation);
    }

    static List<DeltaCase> rule2Cases() throws IOException {
        return loadDeltaCases("rule2-year-normal-boundary.json");
    }

    // ---- ルール3: 年720時間・特別条項（協定年度累計、休日労働を含まない）。超過のみ違反。 ----

    @ParameterizedTest(name = "delta={0}")
    @MethodSource("rule3Cases")
    void rule3YearSpecialBoundary(DeltaCase c) {
        int value = OvertimeLimitDefaults.YEAR_SPECIAL_MINUTES + c.delta;
        OvertimeMonthMinutes safeCurrent = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeMonthMinutes yearMonth = new OvertimeMonthMinutes(TARGET_MONTH, value, 0);
        OvertimeComplianceInput input = baseInput(safeCurrent, List.of(yearMonth), List.of(safeCurrent));

        Set<OvertimeRule> rules = evaluateRules(input);
        assertThat(rules.contains(OvertimeRule.RULE3_YEAR_SPECIAL)).isEqualTo(c.expectedViolation);
        // 720h付近の値は必然的に360h(ルール2)も超えるため、ルール2の同時違反は許容し、
        // それ以外の不要なfindingが無いことだけ確認する。
        assertThat(rules).contains(OvertimeRule.RULE2_YEAR_NORMAL);
        Set<OvertimeRule> unexpected = new HashSet<>(rules);
        unexpected.remove(OvertimeRule.RULE2_YEAR_NORMAL);
        unexpected.remove(OvertimeRule.RULE3_YEAR_SPECIAL);
        assertThat(unexpected).isEmpty();
    }

    static List<DeltaCase> rule3Cases() throws IOException {
        return loadDeltaCases("rule3-year-special-boundary.json");
    }

    // ---- ルール4: 月100時間（休日労働を含む）。最重要境界：ここだけ>=判定。 ----

    @ParameterizedTest(name = "delta={0}")
    @MethodSource("rule4Cases")
    void rule4MonthTotalBoundary(DeltaCase c) {
        int value = OvertimeLimitDefaults.MONTH_TOTAL_MINUTES + c.delta;
        OvertimeMonthMinutes current = new OvertimeMonthMinutes(TARGET_MONTH, 0, value);
        OvertimeMonthMinutes safe = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeComplianceInput input = baseInput(current, List.of(safe), List.of(current));
        assertOnlyRule(input, OvertimeRule.RULE4_MONTH_TOTAL, c.expectedViolation);
    }

    static List<DeltaCase> rule4Cases() throws IOException {
        return loadDeltaCases("rule4-month-total-boundary.json");
    }

    // ---- ルール5: 複数月平均80時間（休日労働を含む）。n=2の窓で境界を固定。 ----

    @ParameterizedTest(name = "delta={0}")
    @MethodSource("rule5Cases")
    void rule5MultiMonthAverageBoundary(DeltaCase c) {
        int value = OvertimeLimitDefaults.MULTI_MONTH_AVERAGE_MINUTES + c.delta;
        OvertimeMonthMinutes window = new OvertimeMonthMinutes(TARGET_MONTH, 0, value);
        OvertimeMonthMinutes safeCurrent = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeComplianceInput input = baseInput(safeCurrent, List.of(safeCurrent), List.of(window, window));
        assertOnlyRule(input, OvertimeRule.RULE5_MULTI_MONTH_AVERAGE, c.expectedViolation);
    }

    static List<DeltaCase> rule5Cases() throws IOException {
        return loadDeltaCases("rule5-multi-month-average-boundary.json");
    }

    // ---- ルール6: 45時間超の月数、年6回まで。7回目が違反。 ----

    @ParameterizedTest(name = "delta={0}")
    @MethodSource("rule6Cases")
    void rule6ExceedMonthCountBoundary(DeltaCase c) {
        int count = OvertimeLimitDefaults.EXCEED_MONTH_COUNT + c.delta;
        List<OvertimeMonthMinutes> months = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            months.add(new OvertimeMonthMinutes(
                    TARGET_MONTH.minusMonths(count - 1L - i), OvertimeLimitDefaults.MONTH_NORMAL_MINUTES + 1, 0));
        }
        OvertimeMonthMinutes safeCurrent = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeComplianceInput input = baseInput(safeCurrent, months, List.of(safeCurrent));
        assertOnlyRule(input, OvertimeRule.RULE6_EXCEED_MONTH_COUNT, c.expectedViolation);
    }

    static List<DeltaCase> rule6Cases() throws IOException {
        return loadDeltaCases("rule6-exceed-month-count-boundary.json");
    }

    // ---- overtime-rules.md §5 推奨fixture最小セット（構造的な振る舞い） ----

    @Test
    void holidayWorkOnly_rule123Compliant_rule45Violate() throws IOException {
        runScenario("holiday-work-only-rule123-compliant-rule45-violate.json");
    }

    @Test
    void scheduledHolidayWork_countsIntoRule1() throws IOException {
        runScenario("scheduled-holiday-work-counts-into-rule1.json");
    }

    @Test
    void insufficientHistory_skipsRule5Window() throws IOException {
        runScenario("insufficient-history-skips-rule5-window.json");
    }

    @Test
    void agreementYearCrossing_resetsRule236_notRule5() throws IOException {
        runScenario("agreement-year-crossing-resets-rule236-not-rule5.json");
    }

    @Test
    void exemptEmployee_noRulesJudged() throws IOException {
        runScenario("exempt-employee-no-rules-judged.json");
    }

    @Test
    void missingAgreement_indeterminateFinding() throws IOException {
        runScenario("missing-agreement-indeterminate-finding.json");
    }

    @Test
    void specialClauseFalse_skipsRule3456() throws IOException {
        runScenario("special-clause-false-skips-rule3456.json");
    }

    @Test
    void applicabilityUnknown_isIndeterminateAndNeverCompliant() {
        OvertimeMonthMinutes safe = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeComplianceInput input = new OvertimeComplianceInput(
                LEGAL_ENTITY_ID, TARGET_MONTH, null, safe, List.of(safe), List.of(safe), AGREEMENT_NO_OVERRIDE);

        assertThat(calculator.evaluate(input))
                .containsExactly(new OvertimeComplianceFinding(
                        OvertimeRule.APPLICABILITY_UNKNOWN,
                        OvertimeComplianceSeverity.INDETERMINATE,
                        LEGAL_ENTITY_ID, TARGET_MONTH, null, null, null));
    }

    @Test
    void requiredAnnualHistoryMissing_isIndeterminateAndNeverZeroFilled() {
        OvertimeMonthMinutes safe = new OvertimeMonthMinutes(TARGET_MONTH, 0, 0);
        OvertimeComplianceInput input = new OvertimeComplianceInput(
                LEGAL_ENTITY_ID, TARGET_MONTH, false, safe, List.of(), List.of(), AGREEMENT_NO_OVERRIDE);

        assertThat(calculator.evaluate(input))
                .containsExactly(new OvertimeComplianceFinding(
                        OvertimeRule.HISTORY_INSUFFICIENT,
                        OvertimeComplianceSeverity.INDETERMINATE,
                        LEGAL_ENTITY_ID, TARGET_MONTH, null, null, null));
    }

    // ---- helpers ----

    private OvertimeComplianceInput baseInput(OvertimeMonthMinutes current,
                                               List<OvertimeMonthMinutes> agreementYearMonths,
                                               List<OvertimeMonthMinutes> rollingWindowMonths) {
        return new OvertimeComplianceInput(LEGAL_ENTITY_ID, TARGET_MONTH, false,
                current, agreementYearMonths, rollingWindowMonths, AGREEMENT_NO_OVERRIDE);
    }

    private Set<OvertimeRule> evaluateRules(OvertimeComplianceInput input) {
        return calculator.evaluate(input).stream().map(OvertimeComplianceFinding::rule).collect(Collectors.toSet());
    }

    private void assertOnlyRule(OvertimeComplianceInput input, OvertimeRule rule, boolean expectedViolation) {
        Set<OvertimeRule> rules = evaluateRules(input);
        if (expectedViolation) {
            assertThat(rules).containsExactly(rule);
        } else {
            assertThat(rules).isEmpty();
        }
    }

    private void runScenario(String fileName) throws IOException {
        ScenarioFixture fixture = readFixture(fileName, ScenarioFixture.class);
        OvertimeComplianceInput input = fixture.toInput();
        List<OvertimeComplianceFinding> findings = calculator.evaluate(input);

        Set<FindingKey> actual = findings.stream()
                .map(f -> new FindingKey(f.rule(), f.severity(), f.windowMonths()))
                .collect(Collectors.toSet());
        Set<FindingKey> expected = fixture.expectedFindings.stream()
                .map(e -> new FindingKey(OvertimeRule.valueOf(e.rule), OvertimeComplianceSeverity.valueOf(e.severity), e.windowMonths))
                .collect(Collectors.toSet());
        assertThat(actual).as(fixture.description).isEqualTo(expected);
    }

    private static List<DeltaCase> loadDeltaCases(String fileName) throws IOException {
        return readFixture(fileName, DeltaCaseFixture.class).cases;
    }

    private static <T> T readFixture(String fileName, Class<T> type) throws IOException {
        try (InputStream in = OvertimeComplianceCalculatorTest.class.getResourceAsStream("/fixtures/overtime/" + fileName)) {
            if (in == null) {
                throw new IOException("fixture not found: fixtures/overtime/" + fileName);
            }
            return MAPPER.readValue(in, type);
        }
    }

    private record FindingKey(OvertimeRule rule, OvertimeComplianceSeverity severity, Integer windowMonths) {
    }

    /** {"cases": [{"delta": -1, "expectedViolation": false}, ...]} 形式のfixture。 */
    static class DeltaCaseFixture {
        public String name;
        public String description;
        public List<DeltaCase> cases;
    }

    static class DeltaCase {
        public int delta;
        public boolean expectedViolation;

        @Override
        public String toString() {
            return "delta=" + delta;
        }
    }

    /** 完全なOvertimeComplianceInputを1件記述する構造的fixture。 */
    static class ScenarioFixture {
        public String name;
        public String description;
        public Long legalEntityId;
        public String targetMonth;
        public boolean applicableExemption;
        public AgreementFixture agreement;
        public MonthFixture currentMonth;
        public List<MonthFixture> agreementYearMonths;
        public List<MonthFixture> rollingWindowMonths;
        public List<ExpectedFindingFixture> expectedFindings;

        OvertimeComplianceInput toInput() {
            OvertimeAgreementSnapshot snapshot = agreement == null ? null : agreement.toSnapshot();
            return new OvertimeComplianceInput(
                    legalEntityId,
                    YearMonth.parse(targetMonth),
                    applicableExemption,
                    currentMonth == null ? null : currentMonth.toDomain(),
                    toDomainList(agreementYearMonths),
                    toDomainList(rollingWindowMonths),
                    snapshot);
        }

        private static List<OvertimeMonthMinutes> toDomainList(List<MonthFixture> months) {
            if (months == null) {
                return List.of();
            }
            return months.stream().map(MonthFixture::toDomain).collect(Collectors.toList());
        }
    }

    static class AgreementFixture {
        public boolean specialClauseEnabled = true;
        public Integer monthNormalLimitMinutes;
        public Integer yearNormalLimitMinutes;
        public Integer yearSpecialLimitMinutes;
        public Integer monthTotalLimitMinutes;
        public Integer multiMonthAverageLimitMinutes;
        public Integer exceedMonthCountLimit;

        OvertimeAgreementSnapshot toSnapshot() {
            return new OvertimeAgreementSnapshot(specialClauseEnabled, monthNormalLimitMinutes, yearNormalLimitMinutes,
                    yearSpecialLimitMinutes, monthTotalLimitMinutes, multiMonthAverageLimitMinutes, exceedMonthCountLimit);
        }
    }

    static class MonthFixture {
        public String yearMonth;
        public int overtimeMinutes;
        public int totalMinutes;

        OvertimeMonthMinutes toDomain() {
            return new OvertimeMonthMinutes(YearMonth.parse(yearMonth), overtimeMinutes, totalMinutes);
        }
    }

    static class ExpectedFindingFixture {
        public String rule;
        public String severity;
        public Integer windowMonths;
    }

    /** {@code m_system_config}に行が無い状態を模す（呼び出し側は常に既定値を受け取る）。 */
    private static final class NoOverrideSystemConfigService implements SystemConfigService {
        @Override
        public String getString(String key, String defaultValue) {
            return defaultValue;
        }

        @Override
        public int getInt(String key, int defaultValue) {
            return defaultValue;
        }

        @Override
        public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
            return defaultValue;
        }

        @Override
        public void put(String key, String value, String description) {
        }

        @Override
        public List<SystemConfig> all() {
            return List.of();
        }

        @Override
        public void updateAll(List<SystemConfig> configs) {
        }
    }
}
