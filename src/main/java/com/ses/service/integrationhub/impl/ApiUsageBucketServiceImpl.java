package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.ApiUsageBucket;
import com.ses.mapper.ApiUsageBucketMapper;
import com.ses.service.integrationhub.ApiUsageBucketService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * NF-05 quota implementation。
 * minute/day/burstの判定とconsumeは、subject rowのFOR UPDATE中に一つのpredicateとして行う。
 */
@Service
@RequiredArgsConstructor
public class ApiUsageBucketServiceImpl extends ServiceImpl<ApiUsageBucketMapper, ApiUsageBucket>
        implements ApiUsageBucketService {
    private static final int MINUTE_LIMIT = 60;
    private static final int DAY_LIMIT = 50_000;
    private static final int BURST_CAPACITY = 20;
    private static final int REFILL_SECONDS = 3;

    private final Clock clock;

    @Override
    public RateDecision consume(String clientId, String scopeCode, String tenantId, String routeTemplate) {
        return consumeAt(clientId, scopeCode, tenantId, routeTemplate,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RateDecision consumeAt(String clientId, String scopeCode, String tenantId, String routeTemplate,
                                  LocalDateTime serverNowUtc) {
        validateSubject(clientId, scopeCode, tenantId, routeTemplate, serverNowUtc);
        ApiUsageBucket bucket = baseMapper.selectSubjectForUpdate(clientId, scopeCode, tenantId, routeTemplate);
        if (bucket == null) {
            bucket = insertInitialBucket(clientId, scopeCode, tenantId, routeTemplate, serverNowUtc);
            if (bucket != null) {
                // 新規rowはinsert自体を最初のconsumeとして原子的に完了させる。
                return RateDecision.allow();
            }
            // unique競合時は一度だけFOR UPDATEで再読込し、同じ判定へ収束する。
            bucket = baseMapper.selectSubjectForUpdate(clientId, scopeCode, tenantId, routeTemplate);
            if (bucket == null) {
                throw new IllegalStateException("quota bucket could not be loaded after unique conflict");
            }
        }

        return consumeLocked(bucket, serverNowUtc);
    }

    private ApiUsageBucket insertInitialBucket(String clientId, String scopeCode, String tenantId,
                                               String routeTemplate, LocalDateTime now) {
        ApiUsageBucket initial = ApiUsageBucket.builder()
                .clientId(clientId)
                .scopeCode(scopeCode)
                .tenantId(tenantId)
                .routeTemplate(routeTemplate)
                .minuteWindowStart(minuteStart(now))
                .minuteCount(1)
                .dayWindowStart(dayStart(now))
                .dayCount(1)
                .burstTokens(BURST_CAPACITY - 1)
                .burstLastRefillAt(now)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            baseMapper.insert(initial);
            return initial;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    private RateDecision consumeLocked(ApiUsageBucket bucket, LocalDateTime now) {
        int minuteCount = safeCount(bucket.getMinuteCount());
        int dayCount = safeCount(bucket.getDayCount());
        int burstTokens = Math.max(0, Math.min(BURST_CAPACITY, safeCount(bucket.getBurstTokens())));
        LocalDateTime minuteWindow = bucket.getMinuteWindowStart();
        LocalDateTime dayWindow = bucket.getDayWindowStart();
        LocalDateTime lastRefill = bucket.getBurstLastRefillAt();

        // rollback時は既存window/refill stateを後戻りさせない。
        if (minuteWindow != null && !now.isBefore(minuteWindow.plusMinutes(1))) {
            minuteWindow = minuteStart(now);
            minuteCount = 0;
        }
        if (dayWindow != null && !now.isBefore(dayWindow.plusDays(1))) {
            dayWindow = dayStart(now);
            dayCount = 0;
        }
        if (lastRefill != null && now.isAfter(lastRefill)) {
            long elapsedSeconds = Duration.between(lastRefill, now).getSeconds();
            long refillSteps = elapsedSeconds / REFILL_SECONDS;
            if (refillSteps > 0) {
                burstTokens = Math.min(BURST_CAPACITY, burstTokens + (int) Math.min(Integer.MAX_VALUE, refillSteps));
                lastRefill = lastRefill.plusSeconds(refillSteps * REFILL_SECONDS);
            }
        }

        Set<String> exhausted = new LinkedHashSet<>();
        if (minuteCount >= MINUTE_LIMIT) {
            exhausted.add("MINUTE");
        }
        if (dayCount >= DAY_LIMIT) {
            exhausted.add("DAY");
        }
        if (burstTokens < 1) {
            exhausted.add("BURST");
        }
        if (!exhausted.isEmpty()) {
            return denied(exhausted, now, minuteWindow, dayWindow, lastRefill, burstTokens);
        }

        bucket.setMinuteWindowStart(minuteWindow);
        bucket.setMinuteCount(minuteCount + 1);
        bucket.setDayWindowStart(dayWindow);
        bucket.setDayCount(dayCount + 1);
        bucket.setBurstTokens(burstTokens - 1);
        bucket.setBurstLastRefillAt(lastRefill);
        bucket.setUpdatedAt(now);
        if (baseMapper.updateCounters(bucket) != 1) {
            throw new IllegalStateException("quota bucket CAS failed");
        }
        return RateDecision.allow();
    }

    private RateDecision denied(Set<String> exhausted, LocalDateTime now, LocalDateTime minuteWindow,
                                LocalDateTime dayWindow, LocalDateTime lastRefill, int burstTokens) {
        long retryAfter = 1;
        if (exhausted.contains("MINUTE") && minuteWindow != null) {
            retryAfter = Math.max(retryAfter, secondsUntil(now, minuteWindow.plusMinutes(1)));
        }
        if (exhausted.contains("DAY") && dayWindow != null) {
            retryAfter = Math.max(retryAfter, secondsUntil(now, dayWindow.plusDays(1)));
        }
        if (exhausted.contains("BURST")) {
            LocalDateTime nextToken = lastRefill == null ? now.plusSeconds(REFILL_SECONDS)
                    : lastRefill.plusSeconds(REFILL_SECONDS);
            retryAfter = Math.max(retryAfter, secondsUntil(now, nextToken));
        }
        return new RateDecision(false, (int) Math.min(Integer.MAX_VALUE, retryAfter), exhausted);
    }

    private long secondsUntil(LocalDateTime now, LocalDateTime target) {
        long millis = Duration.between(now, target).toMillis();
        return millis <= 0 ? 1 : (millis + 999) / 1000;
    }

    private LocalDateTime minuteStart(LocalDateTime now) {
        return now.withSecond(0).withNano(0);
    }

    private LocalDateTime dayStart(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        return date.atStartOfDay();
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    private void validateSubject(String clientId, String scopeCode, String tenantId, String routeTemplate,
                                 LocalDateTime serverNowUtc) {
        requireText(clientId, 100, "clientId");
        requireText(scopeCode, 100, "scopeCode");
        requireText(tenantId, 64, "tenantId");
        requireText(routeTemplate, 255, "routeTemplate");
        if (!routeTemplate.startsWith("/") || routeTemplate.contains("?")) {
            throw new IllegalArgumentException("routeTemplate must be a canonical path template");
        }
        if (serverNowUtc == null) {
            throw new IllegalArgumentException("serverNowUtc is required");
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
