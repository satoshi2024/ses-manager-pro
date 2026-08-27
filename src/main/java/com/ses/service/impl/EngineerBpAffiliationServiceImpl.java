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
    @Transactional(rollbackFor = Exception.class)
    public EngineerBpAffiliation assignBpAffiliation(Long engineerId, Long bpCompanyId, LocalDate validFrom, LocalDate validTo) {
        if (engineerId == null || validFrom == null) {
            throw new BusinessException(400, "要員IDおよび開始日付は必須です");
        }
        if (bpCompanyId == null) {
            throw new BusinessException(400, "BP会社IDは必須です");
        }
        if (validTo != null && validFrom.isAfter(validTo)) {
            throw new BusinessException(400, "開始日は終了日以前を指定してください");
        }

        LocalDate today = LocalDate.now();
        boolean futureReservation = validFrom.isAfter(today);

        // 同一日開始: 原地更新（過去へ分割しない）
        LambdaQueryWrapper<EngineerBpAffiliation> sameStartWrapper = new LambdaQueryWrapper<>();
        sameStartWrapper.eq(EngineerBpAffiliation::getEngineerId, engineerId)
                .eq(EngineerBpAffiliation::getValidFrom, validFrom);
        EngineerBpAffiliation sameStart = this.getOne(sameStartWrapper, false);
        if (sameStart != null) {
            sameStart.setBpCompanyId(bpCompanyId);
            sameStart.setValidTo(validTo);
            this.updateById(sameStart);
            return sameStart;
        }

        // 未来の乗換予約: 現在の所属を切らず、未来行を追加
        if (futureReservation) {
            return saveAffiliation(engineerId, bpCompanyId, validFrom, validTo);
        }

        // 遡及・即時登録: 既存区間を分割し、重複区間を作らない
        // 無期限の新規区間は、後続の既存所属が始まる前日までで打ち切る。
        LocalDate effectiveEnd = validTo;
        if (effectiveEnd == null) {
            LambdaQueryWrapper<EngineerBpAffiliation> laterWrapper = new LambdaQueryWrapper<>();
            laterWrapper.eq(EngineerBpAffiliation::getEngineerId, engineerId)
                    .gt(EngineerBpAffiliation::getValidFrom, validFrom)
                    .orderByAsc(EngineerBpAffiliation::getValidFrom)
                    .last("LIMIT 1");
            EngineerBpAffiliation later = this.getOne(laterWrapper, false);
            if (later != null) {
                effectiveEnd = later.getValidFrom().minusDays(1);
            }
        }

        LambdaQueryWrapper<EngineerBpAffiliation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EngineerBpAffiliation::getEngineerId, engineerId)
                .and(w -> w.isNull(EngineerBpAffiliation::getValidTo)
                        .or().ge(EngineerBpAffiliation::getValidTo, validFrom));
        if (effectiveEnd != null) {
            wrapper.le(EngineerBpAffiliation::getValidFrom, effectiveEnd);
        }
        wrapper.orderByAsc(EngineerBpAffiliation::getValidFrom);
        List<EngineerBpAffiliation> overlapping = this.list(wrapper);

        for (EngineerBpAffiliation existing : overlapping) {
            if (existing.getValidFrom().isBefore(validFrom)) {
                // 左側の未被覆区間だけを残して旧区間を閉じる
                LocalDate originalEnd = existing.getValidTo();
                existing.setValidTo(validFrom.minusDays(1));
                this.updateById(existing);
                // 右側の未被覆区間（呼び出し側で有効期限 validTo が明示された場合に残る尻尾）を別行として維持する
                if (validTo != null
                        && (originalEnd == null || originalEnd.isAfter(validTo))) {
                    saveAffiliation(existing.getEngineerId(), existing.getBpCompanyId(),
                            validTo.plusDays(1), originalEnd);
                }
            } else {
                // 新区間の中に開始する既存行: 未被覆の尻尾だけを残す
                if (effectiveEnd != null && existing.getValidTo() != null
                        && existing.getValidTo().isAfter(effectiveEnd)) {
                    existing.setValidFrom(effectiveEnd.plusDays(1));
                    this.updateById(existing);
                }
                // 完全に被覆される既存行はそのまま残す（後続区間がasOf解決時に優先される）
            }
        }

        LocalDate finalEnd = validTo != null ? validTo : effectiveEnd;
        return saveAffiliation(engineerId, bpCompanyId, validFrom, finalEnd);
    }

    private EngineerBpAffiliation saveAffiliation(Long engineerId, Long bpCompanyId, LocalDate validFrom, LocalDate validTo) {
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
