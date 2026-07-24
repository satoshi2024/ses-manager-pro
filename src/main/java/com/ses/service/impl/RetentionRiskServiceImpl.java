package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.engineerfollowup.RetentionRiskDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerFollowup;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerFollowupMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.RetentionRiskService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@link RetentionRiskService} 実装。
 * 長期Bench・直近満足度低・フォロー間隔超過の3要素を加点合成する簡易スコア。
 * 基準日数・閾値は m_system_config（retention.risk.*）で調整可能。
 */
@Service
@RequiredArgsConstructor
public class RetentionRiskServiceImpl implements RetentionRiskService {

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final EngineerFollowupMapper engineerFollowupMapper;
    private final SystemConfigService systemConfigService;

    @Override
    public RetentionRiskDto score(Long engineerId) {
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }

        LocalDate today = LocalDate.now();
        int score = 0;

        // 1. 長期Bench: Bench中かつ直近契約終了日(無ければ登録日)からの経過日数が基準を超えたら加点
        int benchWarnDays = systemConfigService.getInt("retention.risk.bench-warn-days", 30);
        Long benchDays = null;
        if ("Bench".equals(engineer.getStatus())) {
            LocalDate benchSince = resolveBenchSince(engineer);
            if (benchSince != null) {
                benchDays = ChronoUnit.DAYS.between(benchSince, today);
                if (benchDays >= benchWarnDays * 2L) {
                    score += 40;
                } else if (benchDays >= benchWarnDays) {
                    score += 20;
                }
            }
        }

        // 2. 直近満足度: 記録されている中で最新のフォローの満足度が低いほど加点
        List<EngineerFollowup> followups = engineerFollowupMapper.selectList(
                new LambdaQueryWrapper<EngineerFollowup>()
                        .eq(EngineerFollowup::getEngineerId, engineerId)
                        .orderByDesc(EngineerFollowup::getFollowupDate, EngineerFollowup::getId));
        Integer lastSatisfaction = null;
        for (EngineerFollowup f : followups) {
            if (f.getSatisfaction() != null) {
                lastSatisfaction = f.getSatisfaction();
                break;
            }
        }
        if (lastSatisfaction != null) {
            if (lastSatisfaction <= 2) {
                score += 30;
            } else if (lastSatisfaction == 3) {
                score += 10;
            }
        }

        // 3. フォロー間隔超過: 最終フォロー(無ければ要員登録日)からの経過日数が基準を超えたら加点
        int intervalDays = systemConfigService.getInt("retention.risk.followup-interval-days", 30);
        LocalDate lastFollowupDate = !followups.isEmpty() ? followups.get(0).getFollowupDate()
                : (engineer.getCreatedAt() != null ? engineer.getCreatedAt().toLocalDate() : null);
        Long daysSinceLastFollowup = null;
        if (lastFollowupDate != null) {
            daysSinceLastFollowup = ChronoUnit.DAYS.between(lastFollowupDate, today);
            if (daysSinceLastFollowup >= intervalDays * 2L) {
                score += 30;
            } else if (daysSinceLastFollowup >= intervalDays) {
                score += 15;
            }
        }

        score = Math.min(100, score);
        int threshold = systemConfigService.getInt("retention.risk.threshold", 60);

        return RetentionRiskDto.builder()
                .engineerId(engineerId)
                .score(score)
                .highRisk(score >= threshold)
                .benchDays(benchDays)
                .lastSatisfaction(lastSatisfaction)
                .daysSinceLastFollowup(daysSinceLastFollowup)
                .build();
    }

    /** Bench継続開始日: 直近契約の終了日、無ければ要員登録日 */
    private LocalDate resolveBenchSince(Engineer engineer) {
        QueryWrapper<Contract> cQw = new QueryWrapper<>();
        cQw.eq("engineer_id", engineer.getId()).orderByDesc("end_date").last("LIMIT 1");
        Contract lastContract = contractMapper.selectOne(cQw);
        if (lastContract != null && lastContract.getEndDate() != null) {
            return lastContract.getEndDate();
        }
        return engineer.getCreatedAt() != null ? engineer.getCreatedAt().toLocalDate() : null;
    }
}
