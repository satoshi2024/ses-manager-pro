package com.ses.service.ai;

import java.time.LocalDateTime;

/**
 * mask済み summary の保存期限超過分を null にする。raw prompt は列自体が無い。
 */
public interface AiRecommendationRetentionService {

    int purgeExpiredRedactedSummaries(LocalDateTime now);
}
