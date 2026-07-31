package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerBpAffiliation;
import com.ses.mapper.EngineerBpAffiliationMapper;
import com.ses.service.EngineerBpAffiliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EngineerBpAffiliationServiceImpl extends ServiceImpl<EngineerBpAffiliationMapper, EngineerBpAffiliation> implements EngineerBpAffiliationService {

    @Override
    @Transactional
    public EngineerBpAffiliation assignBpAffiliation(Long engineerId, Long bpCompanyId, LocalDate validFrom, LocalDate validTo) {
        if (engineerId == null || validFrom == null) {
            throw new BusinessException(400, "要員IDおよび開始日付は必須です");
        }
        if (validTo != null && validFrom.isAfter(validTo)) {
            throw new BusinessException(400, "開始日は終了日以前を指定してください");
        }

        // 重複・開いている現在の所属を取得
        LambdaQueryWrapper<EngineerBpAffiliation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EngineerBpAffiliation::getEngineerId, engineerId)
                .and(w -> w.isNull(EngineerBpAffiliation::getValidTo).or().ge(EngineerBpAffiliation::getValidTo, validFrom))
                .orderByDesc(EngineerBpAffiliation::getValidFrom);

        List<EngineerBpAffiliation> overlapping = this.list(wrapper);

        for (EngineerBpAffiliation existing : overlapping) {
            if (existing.getValidFrom().isEqual(validFrom)) {
                // 同日開始の場合は上書き更新
                existing.setBpCompanyId(bpCompanyId);
                existing.setValidTo(validTo);
                this.updateById(existing);
                return existing;
            } else if (existing.getValidFrom().isBefore(validFrom)) {
                // 前日閉鎖: 旧区間の終了日を (validFrom - 1日) に切り詰める
                existing.setValidTo(validFrom.minusDays(1));
                this.updateById(existing);
            }
        }

        // 新しい所属を挿入
        EngineerBpAffiliation newAffiliation = EngineerBpAffiliation.builder()
                .tenantId(1L)
                .engineerId(engineerId)
                .bpCompanyId(bpCompanyId)
                .validFrom(validFrom)
                .validTo(validTo)
                .build();

        this.save(newAffiliation);
        return newAffiliation;
    }

    @Override
    public EngineerBpAffiliation getActiveAffiliationAsOf(Long engineerId, LocalDate targetDate) {
        if (engineerId == null || targetDate == null) {
            return null;
        }

        LambdaQueryWrapper<EngineerBpAffiliation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EngineerBpAffiliation::getEngineerId, engineerId)
                .le(EngineerBpAffiliation::getValidFrom, targetDate)
                .and(w -> w.isNull(EngineerBpAffiliation::getValidTo).or().ge(EngineerBpAffiliation::getValidTo, targetDate))
                .orderByDesc(EngineerBpAffiliation::getValidFrom)
                .orderByDesc(EngineerBpAffiliation::getId);

        List<EngineerBpAffiliation> list = this.list(wrapper);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<EngineerBpAffiliation> getAffiliationHistory(Long engineerId) {
        LambdaQueryWrapper<EngineerBpAffiliation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EngineerBpAffiliation::getEngineerId, engineerId)
                .orderByDesc(EngineerBpAffiliation::getValidFrom);
        return this.list(wrapper);
    }
}
