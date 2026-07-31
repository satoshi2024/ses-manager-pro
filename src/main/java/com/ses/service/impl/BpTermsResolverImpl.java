package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.BpTerms;
import com.ses.mapper.BpTermsMapper;
import com.ses.service.BpTermsResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BpTermsResolverImpl implements BpTermsResolver {

    private final BpTermsMapper bpTermsMapper;

    @Override
    public BpTerms resolveTermsAsOf(Long bpCompanyId, LocalDate targetDate) {
        if (bpCompanyId == null || targetDate == null) {
            return null;
        }

        LambdaQueryWrapper<BpTerms> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BpTerms::getBpCompanyId, bpCompanyId)
                .le(BpTerms::getEffectiveFrom, targetDate)
                .and(w -> w.isNull(BpTerms::getEffectiveTo).or().ge(BpTerms::getEffectiveTo, targetDate))
                .orderByDesc(BpTerms::getEffectiveFrom)
                .orderByDesc(BpTerms::getId);

        List<BpTerms> list = bpTermsMapper.selectList(wrapper);
        return list.isEmpty() ? null : list.get(0);
    }
}
