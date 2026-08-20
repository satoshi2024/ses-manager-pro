package com.ses.service.ai.impl;

import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.AiRecommendationRetentionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AiRecommendationRetentionServiceImpl implements AiRecommendationRetentionService {

    private final AiRecommendationRunMapper runMapper;
    private final int redactedDays;

    public AiRecommendationRetentionServiceImpl(
            AiRecommendationRunMapper runMapper,
            @Value("${ai.retention.redacted-days:730}") int redactedDays) {
        this.runMapper = runMapper;
        this.redactedDays = redactedDays;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredRedactedSummaries(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(redactedDays);
        return runMapper.purgeExpiredSummaries(cutoff, now);
    }
}
