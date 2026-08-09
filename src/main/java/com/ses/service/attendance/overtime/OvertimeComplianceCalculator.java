package com.ses.service.attendance.overtime;

import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;
import com.ses.dto.attendance.overtime.OvertimeComplianceInput;
import com.ses.dto.attendance.overtime.OvertimeComplianceSeverity;
import com.ses.dto.attendance.overtime.OvertimeMonthMinutes;
import com.ses.dto.attendance.overtime.OvertimeRule;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 時間外労働の36協定コンプライアンス判定（attendance-leave-overtime-compliance T069/F2）。
 *
 * <p>唯一の正は{@code overtime-rules.md}。判定式は1メソッド1ルールで書き、条件を複数箇所へ
 * 散らさない（overtime-rules.md §4.3）。閾値の解決順は
 * {@code m_overtime_agreement}（第1段、{@link OvertimeAgreementThresholds}）→
 * {@code m_system_config}（第2段、{@link SystemConfigService}）→
 * コード定数（第3段、{@link OvertimeLimitDefaults}）。第1段のsnapshotは
 * {@link OvertimeComplianceInput#agreement()}が{@code null}の法人は判定不能findingを返す
 * （既定値で「適合」にしない、overtime-rules.md §3）。対象月の協定行は
 * {@link OvertimeAgreementResolver}で解決してから入力へ渡す。</p>
 *
 * <p>findingは違反・判定不能の項目のみを返す（適合ルールはfindingを生成しない）。</p>
 */
@Service
@RequiredArgsConstructor
public class OvertimeComplianceCalculator {

    private final SystemConfigService systemConfigService;

    public List<OvertimeComplianceFinding> evaluate(OvertimeComplianceInput input) {
        Objects.requireNonNull(input, "input must not be null");

        // 適用除外者（管理監督者等）はルール1〜6の対象外（overtime-rules.md §2 #14）。
        // NULLは未確認なので、対象外とも対象とも推測しない。
        if (Boolean.TRUE.equals(input.applicableExemption())) {
            return List.of();
        }
        if (input.applicableExemption() == null) {
            return List.of(indeterminateFinding(OvertimeRule.APPLICABILITY_UNKNOWN, input));
        }

        OvertimeAgreementThresholds agreement = input.agreement();
        if (agreement == null) {
            return List.of(agreementMissingFinding(input));
        }

        if (!hasRequiredHistory(input)) {
            return List.of(indeterminateFinding(OvertimeRule.HISTORY_INSUFFICIENT, input));
        }

        List<OvertimeComplianceFinding> findings = new ArrayList<>();
        evaluateRule1(input, agreement).ifPresent(findings::add);
        evaluateRule2(input, agreement).ifPresent(findings::add);

        // 特別条項が無ければ月45h・年360hを超えられないため、超えた時点でルール1/2の違反になる。
        // ルール3/4/5/6は判定しない（overtime-rules.md §3）。
        if (agreement.specialClauseEnabled()) {
            evaluateRule3(input, agreement).ifPresent(findings::add);
            evaluateRule4(input, agreement).ifPresent(findings::add);
            findings.addAll(evaluateRule5(input, agreement));
            evaluateRule6(input, agreement).ifPresent(findings::add);
        }
        return findings;
    }

    /** ルール1: 月の時間外45時間。休日労働を含まない（overtimeMinutes）。超過のみ違反。 */
    private Optional<OvertimeComplianceFinding> evaluateRule1(OvertimeComplianceInput input, OvertimeAgreementThresholds agreement) {
        int limit = resolveMonthNormalLimitMinutes(agreement);
        int actual = input.currentMonth().overtimeMinutes();
        if (actual > limit) {
            return Optional.of(violation(OvertimeRule.RULE1_MONTH_NORMAL, input, actual, limit, null));
        }
        return Optional.empty();
    }

    /** ルール2: 年の時間外360時間（協定年度累計）。休日労働を含まない。超過のみ違反。 */
    private Optional<OvertimeComplianceFinding> evaluateRule2(OvertimeComplianceInput input, OvertimeAgreementThresholds agreement) {
        int limit = resolveYearNormalLimitMinutes(agreement);
        int actual = sumOvertimeMinutes(input.agreementYearMonths());
        if (actual > limit) {
            return Optional.of(violation(OvertimeRule.RULE2_YEAR_NORMAL, input, actual, limit, null));
        }
        return Optional.empty();
    }

    /** ルール3: 年の時間外720時間（特別条項、協定年度累計）。休日労働を含まない。超過のみ違反。 */
    private Optional<OvertimeComplianceFinding> evaluateRule3(OvertimeComplianceInput input, OvertimeAgreementThresholds agreement) {
        int limit = resolveYearSpecialLimitMinutes(agreement);
        int actual = sumOvertimeMinutes(input.agreementYearMonths());
        if (actual > limit) {
            return Optional.of(violation(OvertimeRule.RULE3_YEAR_SPECIAL, input, actual, limit, null));
        }
        return Optional.empty();
    }

    /**
     * ルール4: 月の合計100時間。休日労働を含む（totalMinutes）。「100時間未満」なので
     * ここだけ{@code >=}判定（ちょうど100時間は違反）。他ルールと混同しないこと。
     */
    private Optional<OvertimeComplianceFinding> evaluateRule4(OvertimeComplianceInput input, OvertimeAgreementThresholds agreement) {
        int limit = resolveMonthTotalLimitMinutes(agreement);
        int actual = input.currentMonth().totalMinutes();
        if (actual >= limit) {
            return Optional.of(violation(OvertimeRule.RULE4_MONTH_TOTAL, input, actual, limit, null));
        }
        return Optional.empty();
    }

    /**
     * ルール5: 複数月平均80時間。休日労働を含む（totalMinutes）。対象月を含むn=2〜6か月の
     * 「すべて」を判定し、1つでも超過なら違反。データがn月分そろわない場合はそのnをskipする
     * （不足月を0として平均を下げない）。協定年度をまたいで計算する（リセットしない）。
     * 浮動小数を避けるため、平均比較は「sum &gt; limit × n」で行う。
     */
    private List<OvertimeComplianceFinding> evaluateRule5(OvertimeComplianceInput input, OvertimeAgreementThresholds agreement) {
        int limit = resolveMultiMonthAverageLimitMinutes(agreement);
        List<OvertimeMonthMinutes> window = input.rollingWindowMonths();
        List<OvertimeComplianceFinding> findings = new ArrayList<>();
        for (int n = 2; n <= 6; n++) {
            if (window.size() < n) {
                continue;
            }
            int sum = window.subList(window.size() - n, window.size()).stream()
                    .mapToInt(OvertimeMonthMinutes::totalMinutes)
                    .sum();
            if (sum > limit * n) {
                findings.add(violation(OvertimeRule.RULE5_MULTI_MONTH_AVERAGE, input, sum, limit * n, n));
            }
        }
        return findings;
    }

    /**
     * ルール6: 45時間超（ルール1の定義に従う）の月数、協定年度で年6回まで。7回目が違反。
     */
    private Optional<OvertimeComplianceFinding> evaluateRule6(OvertimeComplianceInput input, OvertimeAgreementThresholds agreement) {
        int monthLimit = resolveMonthNormalLimitMinutes(agreement);
        long exceedCount = input.agreementYearMonths().stream()
                .filter(m -> m.overtimeMinutes() > monthLimit)
                .count();
        int countLimit = resolveExceedMonthCountLimit(agreement);
        if (exceedCount > countLimit) {
            return Optional.of(violation(OvertimeRule.RULE6_EXCEED_MONTH_COUNT, input, (int) exceedCount, countLimit, null));
        }
        return Optional.empty();
    }

    private int resolveMonthNormalLimitMinutes(OvertimeAgreementThresholds agreement) {
        Integer fromAgreement = agreement.monthNormalLimitMinutes();
        if (fromAgreement != null) {
            return fromAgreement;
        }
        return systemConfigService.getInt(OvertimeLimitDefaults.CONFIG_MONTH_NORMAL, OvertimeLimitDefaults.MONTH_NORMAL_MINUTES);
    }

    private int resolveYearNormalLimitMinutes(OvertimeAgreementThresholds agreement) {
        Integer fromAgreement = agreement.yearNormalLimitMinutes();
        if (fromAgreement != null) {
            return fromAgreement;
        }
        return systemConfigService.getInt(OvertimeLimitDefaults.CONFIG_YEAR_NORMAL, OvertimeLimitDefaults.YEAR_NORMAL_MINUTES);
    }

    private int resolveYearSpecialLimitMinutes(OvertimeAgreementThresholds agreement) {
        Integer fromAgreement = agreement.yearSpecialLimitMinutes();
        if (fromAgreement != null) {
            return fromAgreement;
        }
        return systemConfigService.getInt(OvertimeLimitDefaults.CONFIG_YEAR_SPECIAL, OvertimeLimitDefaults.YEAR_SPECIAL_MINUTES);
    }

    private int resolveMonthTotalLimitMinutes(OvertimeAgreementThresholds agreement) {
        Integer fromAgreement = agreement.monthTotalLimitMinutes();
        if (fromAgreement != null) {
            return fromAgreement;
        }
        return systemConfigService.getInt(OvertimeLimitDefaults.CONFIG_MONTH_TOTAL, OvertimeLimitDefaults.MONTH_TOTAL_MINUTES);
    }

    private int resolveMultiMonthAverageLimitMinutes(OvertimeAgreementThresholds agreement) {
        Integer fromAgreement = agreement.multiMonthAverageLimitMinutes();
        if (fromAgreement != null) {
            return fromAgreement;
        }
        return systemConfigService.getInt(OvertimeLimitDefaults.CONFIG_MULTI_MONTH_AVERAGE, OvertimeLimitDefaults.MULTI_MONTH_AVERAGE_MINUTES);
    }

    private int resolveExceedMonthCountLimit(OvertimeAgreementThresholds agreement) {
        Integer fromAgreement = agreement.exceedMonthCountLimit();
        if (fromAgreement != null) {
            return fromAgreement;
        }
        return systemConfigService.getInt(OvertimeLimitDefaults.CONFIG_EXCEED_MONTH_COUNT, OvertimeLimitDefaults.EXCEED_MONTH_COUNT);
    }

    private static int sumOvertimeMinutes(List<OvertimeMonthMinutes> months) {
        return months.stream().mapToInt(OvertimeMonthMinutes::overtimeMinutes).sum();
    }

    private static boolean hasRequiredHistory(OvertimeComplianceInput input) {
        if (input.targetMonth() == null || input.currentMonth() == null
                || input.agreementYearMonths() == null || input.agreementYearMonths().isEmpty()) {
            return false;
        }
        return input.targetMonth().equals(input.currentMonth().yearMonth())
                && input.agreementYearMonths().stream()
                .anyMatch(month -> input.targetMonth().equals(month.yearMonth()));
    }

    private static OvertimeComplianceFinding violation(OvertimeRule rule, OvertimeComplianceInput input, int actual, int limit, Integer windowMonths) {
        return new OvertimeComplianceFinding(rule, OvertimeComplianceSeverity.VIOLATION,
                input.legalEntityId(), input.targetMonth(), actual, limit, windowMonths);
    }

    private static OvertimeComplianceFinding agreementMissingFinding(OvertimeComplianceInput input) {
        return new OvertimeComplianceFinding(OvertimeRule.AGREEMENT_MISSING, OvertimeComplianceSeverity.INDETERMINATE,
                input.legalEntityId(), input.targetMonth(), null, null, null);
    }

    private static OvertimeComplianceFinding indeterminateFinding(OvertimeRule rule, OvertimeComplianceInput input) {
        return new OvertimeComplianceFinding(rule, OvertimeComplianceSeverity.INDETERMINATE,
                input.legalEntityId(), input.targetMonth(), null, null, null);
    }
}
