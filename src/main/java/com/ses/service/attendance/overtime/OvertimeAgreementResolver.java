package com.ses.service.attendance.overtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.attendance.overtime.OvertimeAgreementSnapshot;
import com.ses.entity.OvertimeAgreement;
import com.ses.mapper.OvertimeAgreementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 対象月時点の法人別36協定を解決する。対象月は必ず月初でasOfする。
 * 行が無い場合はnullを返し、呼び出し側のcalculatorが判定不能findingにする。
 */
@Service
@RequiredArgsConstructor
public class OvertimeAgreementResolver {

    private final OvertimeAgreementMapper overtimeAgreementMapper;

    @Transactional(readOnly = true)
    public OvertimeAgreementSnapshot resolve(Long legalEntityId, YearMonth targetMonth) {
        if (legalEntityId == null || targetMonth == null) {
            return null;
        }
        LocalDate asOf = targetMonth.atDay(1);
        OvertimeAgreement agreement = overtimeAgreementMapper.selectOne(
                new LambdaQueryWrapper<OvertimeAgreement>()
                        .eq(OvertimeAgreement::getLegalEntityId, legalEntityId)
                        .le(OvertimeAgreement::getValidFrom, asOf)
                        .and(wrapper -> wrapper.isNull(OvertimeAgreement::getValidTo)
                                .or()
                                .ge(OvertimeAgreement::getValidTo, asOf))
                        .orderByDesc(OvertimeAgreement::getValidFrom)
                        .last("LIMIT 1"));
        return OvertimeAgreementSnapshot.from(agreement);
    }
}
