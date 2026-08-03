package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.BpPriceNegotiation;
import com.ses.mapper.BpPriceNegotiationMapper;
import com.ses.service.BpPriceNegotiationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BpPriceNegotiationServiceImpl extends ServiceImpl<BpPriceNegotiationMapper, BpPriceNegotiation> implements BpPriceNegotiationService {

    @Override
    @Transactional
    public BpPriceNegotiation requestNegotiation(Long bpCompanyId, BigDecimal requestedAmount, String summary, Long documentId) {
        if (bpCompanyId == null || requestedAmount == null) {
            throw new BusinessException(400, "BP会社IDおよび希望希望額は必須です");
        }

        BpPriceNegotiation neg = BpPriceNegotiation.builder()
                .tenantId(1L)
                .bpCompanyId(bpCompanyId)
                .requestedAt(LocalDate.now())
                .status("REQUESTED")
                .requestedAmount(requestedAmount)
                .summary(summary)
                .documentId(documentId)
                .build();

        this.save(neg);
        return neg;
    }

    @Override
    @Transactional
    public BpPriceNegotiation respondNegotiation(Long negotiationId, String status, BigDecimal agreedAmount, String summary) {
        BpPriceNegotiation neg = this.getById(negotiationId);
        if (neg == null) {
            throw new BusinessException(404, "対象の価格協議記録が見つかりません");
        }

        String nextStatus = status != null ? status : "AGREED";
        if (!List.of("RESPONDED", "AGREED", "REJECTED").contains(nextStatus)) {
            throw new BusinessException(400, "価格協議の回答状態が不正です");
        }
        boolean allowed = "REQUESTED".equals(neg.getStatus()) || "RESPONDED".equals(neg.getStatus());
        if (!allowed) {
            throw new BusinessException(409, "この価格協議は回答済みのため更新できません");
        }

        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BpPriceNegotiation> update =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        update.eq(BpPriceNegotiation::getId, negotiationId)
                .eq(BpPriceNegotiation::getStatus, neg.getStatus())
                .set(BpPriceNegotiation::getStatus, nextStatus)
                .set(BpPriceNegotiation::getRespondedAt, LocalDate.now());
        if (agreedAmount != null) {
            update.set(BpPriceNegotiation::getAgreedAmount, agreedAmount);
        }
        if (summary != null) {
            update.set(BpPriceNegotiation::getSummary, summary);
        }
        boolean updated = this.update(update);
        if (!updated) {
            throw new BusinessException(409, "価格協議の状態が変更されています。再読み込みしてください");
        }
        return this.getById(negotiationId);
    }

    @Override
    public List<BpPriceNegotiation> getNegotiationsByBpCompany(Long bpCompanyId) {
        LambdaQueryWrapper<BpPriceNegotiation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BpPriceNegotiation::getBpCompanyId, bpCompanyId)
                .orderByDesc(BpPriceNegotiation::getRequestedAt)
                .orderByDesc(BpPriceNegotiation::getId);
        return this.list(wrapper);
    }
}
