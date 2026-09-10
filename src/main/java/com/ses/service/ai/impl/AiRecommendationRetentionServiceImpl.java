package com.ses.service.ai.impl;

import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.AiRecommendationRetentionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;

@Service
public class AiRecommendationRetentionServiceImpl implements AiRecommendationRetentionService {

    private final AiRecommendationRunMapper runMapper;
    private final int redactedDays;
    private final Clock clock;

    public AiRecommendationRetentionServiceImpl(
            AiRecommendationRunMapper runMapper,
            @Value("${ai.retention.redacted-days:730}") int redactedDays,
            Clock clock) {
        this.runMapper = runMapper;
        this.redactedDays = redactedDays;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredRedactedSummaries(LocalDateTime now) {
        return purgeExpiredRedactedSummaries(now, 1000);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredRedactedSummaries(LocalDateTime now, int maxRows) {
        if (now == null || maxRows <= 0) {
            throw new IllegalArgumentException("invalid AI retention purge request");
        }
        LocalDateTime cutoff = now.minusDays(redactedDays);
        return runMapper.purgeExpiredSummaries(cutoff, now, Math.min(maxRows, 1000));
    }

    /** scheduler用。時刻の正本は注入Clockに限定する。 */
    public int purgeExpiredRedactedSummariesFromClock(int maxRows) {
        return purgeExpiredRedactedSummaries(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), maxRows);
    }
}
