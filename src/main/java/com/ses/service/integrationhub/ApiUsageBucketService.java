package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.ApiUsageBucket;

import java.time.LocalDateTime;
import java.util.Set;

/** NF-05 DB-backed quota service。JVMローカルcounterへfallbackしない。 */
public interface ApiUsageBucketService extends IService<ApiUsageBucket> {
    RateDecision consume(String clientId, String scopeCode, String tenantId, String routeTemplate);

    RateDecision consumeAt(String clientId, String scopeCode, String tenantId, String routeTemplate,
                           LocalDateTime serverNowUtc);

    record RateDecision(boolean allowed, int retryAfterSeconds, Set<String> exhaustedLimits) {
        public RateDecision {
            exhaustedLimits = Set.copyOf(exhaustedLimits);
        }

        public static RateDecision allow() {
            return new RateDecision(true, 0, Set.of());
        }
    }
}
