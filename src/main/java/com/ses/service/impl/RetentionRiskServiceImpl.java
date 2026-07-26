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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link RetentionRiskService} 実装。
 * 長期Bench・直近満足度低・フォロー間隔超過の3要素を加点合成する簡易スコア。
 * 基準日数・閾値は m_system_config（retention.risk.*）で調整可能。
 *
 * <p>要員一覧（{@code EngineerApiController}）はページ内全要員のスコアを必要とするため、
 * 算出の実体は {@link #scoreBatch(Collection)} に置き、要員数によらず定数回のクエリで済ませる
 * （1件ずつ算出すると要員数×3クエリのN+1になり、一覧表示のたびに数百クエリが発行される）。
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
        return computeAll(List.of(engineer)).get(engineerId);
    }

    @Override
    public Map<Long, RetentionRiskDto> scoreBatch(Collection<Long> engineerIds) {
        if (engineerIds == null || engineerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = engineerIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return computeAll(engineerMapper.selectBatchIds(ids));
    }

    @Override
    public Map<Long, RetentionRiskDto> scoreBatchFor(Collection<Engineer> engineers) {
        if (engineers == null || engineers.isEmpty()) {
            return Collections.emptyMap();
        }
        return computeAll(new java.util.ArrayList<>(engineers));
    }

    /**
     * 与えられた要員群のスコアをまとめて算出する。
     * 追加クエリは「契約の一括取得」「フォロー記録の一括取得」の2回のみ。
     */
    private Map<Long, RetentionRiskDto> computeAll(List<Engineer> engineers) {
        if (engineers == null || engineers.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = engineers.stream().map(Engineer::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        LocalDate today = LocalDate.now();
        int benchWarnDays = systemConfigService.getInt("retention.risk.bench-warn-days", 30);
        int intervalDays = systemConfigService.getInt("retention.risk.followup-interval-days", 30);
        int threshold = systemConfigService.getInt("retention.risk.threshold", 60);

        Map<Long, LocalDate> latestContractEnd = loadLatestContractEnd(ids);
        Map<Long, List<EngineerFollowup>> followups = loadFollowups(ids);

        Map<Long, RetentionRiskDto> result = new LinkedHashMap<>();
        for (Engineer engineer : engineers) {
            if (engineer.getId() == null) {
                continue;
            }
            result.put(engineer.getId(), compute(engineer, today, benchWarnDays, intervalDays, threshold,
                    latestContractEnd.get(engineer.getId()),
                    followups.getOrDefault(engineer.getId(), List.of())));
        }
        return result;
    }

    /** 要員ごとの直近契約終了日（最大の end_date）。Bench継続開始日の基準になる。 */
    private Map<Long, LocalDate> loadLatestContractEnd(List<Long> engineerIds) {
        List<Contract> contracts = contractMapper.selectList(
                new QueryWrapper<Contract>().in("engineer_id", engineerIds));
        Map<Long, LocalDate> latest = new HashMap<>();
        for (Contract c : contracts) {
            if (c.getEngineerId() == null || c.getEndDate() == null) {
                continue;
            }
            latest.merge(c.getEngineerId(), c.getEndDate(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return latest;
    }

    /** 要員ごとのフォロー記録（新しい順）。 */
    private Map<Long, List<EngineerFollowup>> loadFollowups(List<Long> engineerIds) {
        return engineerFollowupMapper.selectList(
                        new LambdaQueryWrapper<EngineerFollowup>()
                                .in(EngineerFollowup::getEngineerId, engineerIds)
                                .orderByDesc(EngineerFollowup::getFollowupDate, EngineerFollowup::getId))
                .stream()
                .filter(f -> f.getEngineerId() != null)
                .collect(Collectors.groupingBy(EngineerFollowup::getEngineerId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private RetentionRiskDto compute(Engineer engineer, LocalDate today, int benchWarnDays, int intervalDays,
                                     int threshold, LocalDate latestContractEnd, List<EngineerFollowup> followups) {
        int score = 0;

        // 1. 長期Bench: Bench中かつ直近契約終了日(無ければ登録日)からの経過日数が基準を超えたら加点
        Long benchDays = null;
        if ("Bench".equals(engineer.getStatus())) {
            LocalDate benchSince = latestContractEnd != null ? latestContractEnd
                    : (engineer.getCreatedAt() != null ? engineer.getCreatedAt().toLocalDate() : null);
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

        return RetentionRiskDto.builder()
                .engineerId(engineer.getId())
                .score(score)
                .highRisk(score >= threshold)
                .benchDays(benchDays)
                .lastSatisfaction(lastSatisfaction)
                .daysSinceLastFollowup(daysSinceLastFollowup)
                .build();
    }
}
