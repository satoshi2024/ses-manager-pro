package com.ses.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ses.dto.attendance.overtime.OvertimeAgreementSnapshot;
import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;
import com.ses.dto.attendance.overtime.OvertimeComplianceInput;
import com.ses.dto.attendance.overtime.OvertimeMonthMinutes;
import com.ses.dto.attendance.overtime.OvertimeRule;
import com.ses.entity.SystemConfig;
import com.ses.service.SystemConfigService;
import com.ses.service.attendance.overtime.OvertimeComplianceCalculator;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

public class OvertimeEvaluator {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final SystemConfigService CONFIG = new SystemConfigService() {
        @Override public String getString(String key, String defaultValue) { return defaultValue; }
        @Override public int getInt(String key, int defaultValue) { return defaultValue; }
        @Override public BigDecimal getDecimal(String key, BigDecimal defaultValue) { return defaultValue; }
        @Override public void put(String key, String value, String description) {}
        @Override public List<SystemConfig> all() { return List.of(); }
        @Override public void updateAll(List<SystemConfig> configs) {}
    };
    private static final OvertimeComplianceCalculator CALCULATOR = new OvertimeComplianceCalculator(CONFIG);
    private static final YearMonth TARGET = YearMonth.of(2026, 8);
    private static final OvertimeAgreementSnapshot AGREEMENT_SPECIAL = new OvertimeAgreementSnapshot(true, null, null, null, null, null, null);
    private static final OvertimeAgreementSnapshot AGREEMENT_NO_SPECIAL = new OvertimeAgreementSnapshot(false, null, null, null, null, null, null);

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        Map<String, Object> results = new LinkedHashMap<>();

        if ("rule1".equals(mode) || "all".equals(mode)) {
            // MOD08-07: 44:59 (2699m), 45:00 (2700m), 45:01 (2701m)
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int minutes : List.of(2699, 2700, 2701)) {
                OvertimeMonthMinutes m = new OvertimeMonthMinutes(TARGET, minutes, minutes);
                OvertimeComplianceInput input = new OvertimeComplianceInput(1L, TARGET, false, m, List.of(m), List.of(m), AGREEMENT_NO_SPECIAL);
                List<OvertimeComplianceFinding> findings = CALCULATOR.evaluate(input);
                boolean violated = findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE1_MONTH_NORMAL);
                boolean expected = minutes > 2700;
                rows.add(Map.of(
                        "input_minutes", minutes,
                        "formatted_time", String.format("%02d:%02d", minutes / 60, minutes % 60),
                        "limit_minutes", 2700,
                        "expected_violation", expected,
                        "actual_violation", violated,
                        "findings_count", findings.size(),
                        "findings", findings
                ));
            }
            results.put("rule1_month_normal", rows);
        }

        if ("rule4".equals(mode) || "all".equals(mode)) {
            // MOD08-08: 99:59 (5999m), 100:00 (6000m), 100:01 (6001m)
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int minutes : List.of(5999, 6000, 6001)) {
                OvertimeMonthMinutes current = new OvertimeMonthMinutes(TARGET, 0, minutes);
                OvertimeMonthMinutes safe = new OvertimeMonthMinutes(TARGET, 0, 0);
                OvertimeComplianceInput input = new OvertimeComplianceInput(1L, TARGET, false, current, List.of(safe), List.of(current), AGREEMENT_SPECIAL);
                List<OvertimeComplianceFinding> findings = CALCULATOR.evaluate(input);
                boolean violated = findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE4_MONTH_TOTAL);
                boolean expected = minutes >= 6000; // >= 100h is violation
                rows.add(Map.of(
                        "input_total_minutes", minutes,
                        "formatted_time", String.format("%02d:%02d", minutes / 60, minutes % 60),
                        "limit_minutes", 6000,
                        "expected_violation", expected,
                        "actual_violation", violated,
                        "findings_count", findings.size(),
                        "findings", findings
                ));
            }
            results.put("rule4_month_total", rows);
        }

        if ("rule5".equals(mode) || "all".equals(mode)) {
            // MOD08-09: multi-month average 80h (4800m) for n=2..6 (79:59=4799m, 80:00=4800m, 80:01=4801m)
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int n = 2; n <= 6; n++) {
                for (int minutes : List.of(4799, 4800, 4801)) {
                    List<OvertimeMonthMinutes> window = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        window.add(new OvertimeMonthMinutes(TARGET.minusMonths(n - 1L - i), 0, minutes));
                    }
                    OvertimeMonthMinutes safeCurrent = new OvertimeMonthMinutes(TARGET, 0, 0);
                    OvertimeComplianceInput input = new OvertimeComplianceInput(1L, TARGET, false, safeCurrent, List.of(safeCurrent), window, AGREEMENT_SPECIAL);
                    List<OvertimeComplianceFinding> findings = CALCULATOR.evaluate(input);
                    final int winN = n;
                    boolean violated = findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE5_MULTI_MONTH_AVERAGE && f.windowMonths() != null && f.windowMonths() == winN);
                    boolean expected = minutes > 4800; // sum > 4800 * n
                    rows.add(Map.of(
                            "window_n", n,
                            "input_average_minutes", minutes,
                            "formatted_time", String.format("%02d:%02d", minutes / 60, minutes % 60),
                            "limit_minutes", 4800,
                            "sum_minutes", minutes * n,
                            "limit_sum_minutes", 4800 * n,
                            "expected_violation", expected,
                            "actual_violation", violated,
                            "findings", findings
                    ));
                }
            }
            results.put("rule5_multi_month_average", rows);
        }

        if ("rule2".equals(mode) || "all".equals(mode)) {
            // MOD08-10: annual overtime without special clause: 359:59 (21599m), 360:00 (21600m), 360:01 (21601m)
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int minutes : List.of(21599, 21600, 21601)) {
                OvertimeMonthMinutes currentSafe = new OvertimeMonthMinutes(TARGET, 0, 0);
                OvertimeMonthMinutes yearTotal = new OvertimeMonthMinutes(TARGET, minutes, 0);
                OvertimeComplianceInput input = new OvertimeComplianceInput(1L, TARGET, false, currentSafe, List.of(yearTotal), List.of(currentSafe), AGREEMENT_NO_SPECIAL);
                List<OvertimeComplianceFinding> findings = CALCULATOR.evaluate(input);
                boolean violated = findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE2_YEAR_NORMAL);
                boolean expected = minutes > 21600;
                rows.add(Map.of(
                        "input_year_minutes", minutes,
                        "formatted_time", String.format("%02d:%02d", minutes / 60, minutes % 60),
                        "limit_minutes", 21600,
                        "expected_violation", expected,
                        "actual_violation", violated,
                        "findings", findings
                ));
            }
            results.put("rule2_year_normal", rows);
        }

        if ("rule3_6".equals(mode) || "all".equals(mode)) {
            // MOD08-11: special clause annual 719:59 (43199m), 720:00 (43200m), 720:01 (43201m) & exceed count 6/7
            List<Map<String, Object>> annualRows = new ArrayList<>();
            for (int minutes : List.of(43199, 43200, 43201)) {
                OvertimeMonthMinutes currentSafe = new OvertimeMonthMinutes(TARGET, 0, 0);
                OvertimeMonthMinutes yearTotal = new OvertimeMonthMinutes(TARGET, minutes, 0);
                OvertimeComplianceInput input = new OvertimeComplianceInput(1L, TARGET, false, currentSafe, List.of(yearTotal), List.of(currentSafe), AGREEMENT_SPECIAL);
                List<OvertimeComplianceFinding> findings = CALCULATOR.evaluate(input);
                boolean violated = findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE3_YEAR_SPECIAL);
                boolean expected = minutes > 43200;
                annualRows.add(Map.of(
                        "input_year_special_minutes", minutes,
                        "formatted_time", String.format("%02d:%02d", minutes / 60, minutes % 60),
                        "limit_minutes", 43200,
                        "expected_violation", expected,
                        "actual_violation", violated,
                        "findings", findings
                ));
            }
            results.put("rule3_year_special", annualRows);

            List<Map<String, Object>> countRows = new ArrayList<>();
            for (int count : List.of(6, 7)) {
                List<OvertimeMonthMinutes> months = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    months.add(new OvertimeMonthMinutes(TARGET.minusMonths(count - 1L - i), 2701, 0));
                }
                OvertimeMonthMinutes currentSafe = new OvertimeMonthMinutes(TARGET, 0, 0);
                OvertimeComplianceInput input = new OvertimeComplianceInput(1L, TARGET, false, currentSafe, months, List.of(currentSafe), AGREEMENT_SPECIAL);
                List<OvertimeComplianceFinding> findings = CALCULATOR.evaluate(input);
                boolean violated = findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE6_EXCEED_MONTH_COUNT);
                boolean expected = count > 6;
                countRows.add(Map.of(
                        "exceed_month_count", count,
                        "limit_count", 6,
                        "expected_violation", expected,
                        "actual_violation", violated,
                        "findings", findings
                ));
            }
            results.put("rule6_exceed_month_count", countRows);
        }

        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
