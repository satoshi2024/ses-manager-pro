package com.ses.attendance;

import com.ses.dto.attendance.overtime.OvertimeAgreementSnapshot;
import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;
import com.ses.dto.attendance.overtime.OvertimeComplianceInput;
import com.ses.dto.attendance.overtime.OvertimeMonthMinutes;
import com.ses.dto.attendance.overtime.OvertimeRule;
import com.ses.entity.OvertimeAgreement;
import com.ses.mapper.OvertimeAgreementMapper;
import com.ses.service.attendance.overtime.OvertimeAgreementResolver;
import com.ses.service.attendance.overtime.OvertimeComplianceCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T069のL2〜L3定向test。法人・asOf期間からV83の協定行を解決する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OvertimeAgreementResolverTest {

    @Autowired private OvertimeAgreementMapper overtimeAgreementMapper;
    @Autowired private OvertimeAgreementResolver resolver;
    @Autowired private OvertimeComplianceCalculator calculator;

    @Test
    void 対象月時点で最新の有効協定をsnapshotへ変換する() {
        insertAgreement(8101L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 0, 111);
        insertAgreement(8101L, LocalDate.of(2026, 7, 1), null, 1, 222);

        OvertimeAgreementSnapshot snapshot = resolver.resolve(8101L, YearMonth.of(2026, 8));

        assertEquals(true, snapshot.specialClauseEnabled());
        assertEquals(222, snapshot.monthNormalLimitMinutes());
        assertNull(snapshot.yearSpecialLimitMinutes(), "agreementのnull項目はcalculatorのconfig fallback対象");
    }

    @Test
    void 有効協定が無い法人はnullで返しcalculatorの判定不能へ渡せる() {
        assertNull(resolver.resolve(8199L, YearMonth.of(2026, 8)));
        assertNull(resolver.resolve(null, YearMonth.of(2026, 8)));
    }

    @Test
    void resolverの法人別上限がconfigより優先されcalculatorへ渡る() {
        YearMonth target = YearMonth.of(2026, 8);
        insertAgreement(8102L, target.atDay(1), null, 0, 120);
        OvertimeMonthMinutes month = new OvertimeMonthMinutes(target, 121, 121);

        List<OvertimeComplianceFinding> findings = calculator.evaluate(new OvertimeComplianceInput(
                8102L, target, false, month, List.of(month), List.of(month), resolver.resolve(8102L, target)));

        assertEquals(List.of(OvertimeRule.RULE1_MONTH_NORMAL), findings.stream()
                .map(OvertimeComplianceFinding::rule).toList());
    }

    private void insertAgreement(Long legalEntityId, LocalDate validFrom, LocalDate validTo,
                                 int specialClause, int monthLimit) {
        overtimeAgreementMapper.insert(OvertimeAgreement.builder()
                .legalEntityId(legalEntityId)
                .validFrom(validFrom)
                .validTo(validTo)
                .specialClause(specialClause)
                .normalMonthLimitMinutes(monthLimit)
                .build());
    }
}
