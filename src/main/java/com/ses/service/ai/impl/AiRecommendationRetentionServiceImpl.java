package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.privacy.production.provider.AiLogProvider;
import com.ses.privacy.production.retention.hold.HoldSubjectResolver;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard;
import com.ses.service.ai.AiRecommendationRetentionService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AI 推薦の redacted summary の保存期限超過分を null にする。
 *
 * <p>NF07 A1H-2（D6）: 保全 hold の gate は scheduler（{@code ai.retention.scheduler-enabled} のときだけ Bean がある）ではなく
 * 本 service に置き、Bean の有無に依らず効かせる。回の初めに gate を1回だけ照会し、hold 機構を使っていない tenant では
 * 従来の1文の purge をそのまま使う（増える照会はこの1回だけ）。DATA_ELEMENT hold・分類 UNKNOWN・gate が判定できない
 * 場合はその回を消さずに件数を警告し、SUBJECT hold では本人に帰属する run（F2 {@code AiLogProvider} の導出）だけを除いて消す。
 * 本人を導出できない run は SUBJECT hold では止めない（§3.4.3 規則4、plan §3.4.1「D6 の残余」）。
 */
@Service
public class AiRecommendationRetentionServiceImpl implements AiRecommendationRetentionService {

    private static final int MAX_BATCH = 1000;

    private final AiRecommendationRunMapper runMapper;
    private final int redactedDays;
    private final Clock clock;
    private final PrivacyHoldPathGuard privacyHoldPathGuard;

    public AiRecommendationRetentionServiceImpl(
            AiRecommendationRunMapper runMapper,
            @Value("${ai.retention.redacted-days:730}") int redactedDays,
            Clock clock,
            PrivacyHoldPathGuard privacyHoldPathGuard) {
        this.runMapper = runMapper;
        this.redactedDays = redactedDays;
        this.clock = clock;
        this.privacyHoldPathGuard = privacyHoldPathGuard;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredRedactedSummaries(LocalDateTime now) {
        return purgeExpiredRedactedSummaries(now, MAX_BATCH);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredRedactedSummaries(LocalDateTime now, int maxRows) {
        if (now == null || maxRows <= 0) {
            throw new IllegalArgumentException("invalid AI retention purge request");
        }
        // 明示tenantのみ。Holder defaultやSecurityUtils補完では全tenant掃除を許さない。
        String tenantId = AccountingTenantContextHolder.getExplicitTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalStateException("TENANT_CONTEXT_REQUIRED");
        }
        LocalDateTime cutoff = now.minusDays(redactedDays);
        int limit = Math.min(maxRows, MAX_BATCH);
        PrivacyHoldPathGuard.Batch hold = privacyHoldPathGuard.openBatch(PrivacyHoldPath.D6, tenantId);
        try {
            if (!hold.engaged()) {
                return runMapper.purgeExpiredSummaries(tenantId, cutoff, now, limit);
            }
            PrivacyHoldGate.Verdict elementVerdict = hold.elementVerdict(AiLogProvider.REDACTED_SUMMARY);
            if (elementVerdict != PrivacyHoldGate.Verdict.ALLOW) {
                hold.skip(elementVerdict, candidateIds(tenantId, cutoff, Set.of(), limit).size());
                return 0;
            }
            Set<Long> held = hold.subjectHeldRowIds(AiLogProvider.REDACTED_SUMMARY, HoldSubjectResolver.AI_RUN_TABLE);
            if (held == null) {
                hold.skip(PrivacyHoldGate.Verdict.UNAVAILABLE, candidateIds(tenantId, cutoff, Set.of(), limit).size());
                return 0;
            }
            if (held.isEmpty()) {
                return runMapper.purgeExpiredSummaries(tenantId, cutoff, now, limit);
            }
            // 本人に帰属する run を候補から除く（除いた行が先頭に溜まって batch を埋め続けないよう、選ぶ段階で除く）
            List<Long> heldExpired = toIds(runMapper.selectObjs(expired(tenantId, cutoff)
                    .in(AiRecommendationRun::getId, held).select(AiRecommendationRun::getId)));
            hold.skip(PrivacyHoldGate.Verdict.HELD, heldExpired.size());
            List<Long> ids = candidateIds(tenantId, cutoff, held, limit);
            if (ids.isEmpty()) {
                return 0;
            }
            return runMapper.update(null, new LambdaUpdateWrapper<AiRecommendationRun>()
                    .eq(AiRecommendationRun::getTenantId, tenantId)
                    .in(AiRecommendationRun::getId, ids)
                    .lt(AiRecommendationRun::getCreatedAt, cutoff)
                    .isNotNull(AiRecommendationRun::getRedactedSummaryJson)
                    .set(AiRecommendationRun::getRedactedSummaryJson, null)
                    .set(AiRecommendationRun::getUpdatedAt, now));
        } finally {
            hold.close();
        }
    }

    /** scheduler用。時刻の正本は注入Clockに限定する。 */
    public int purgeExpiredRedactedSummariesFromClock(int maxRows) {
        return purgeExpiredRedactedSummaries(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), maxRows);
    }

    /** 保存期限を過ぎ summary が残る run（mapper の1文の purge と同じ条件。論理削除済みは MyBatis-Plus が除く）。 */
    private static LambdaQueryWrapper<AiRecommendationRun> expired(String tenantId, LocalDateTime cutoff) {
        return new LambdaQueryWrapper<AiRecommendationRun>()
                .eq(AiRecommendationRun::getTenantId, tenantId)
                .lt(AiRecommendationRun::getCreatedAt, cutoff)
                .isNotNull(AiRecommendationRun::getRedactedSummaryJson);
    }

    /** 古い順の候補（{@code excluded} を除く）。 */
    private List<Long> candidateIds(String tenantId, LocalDateTime cutoff, Set<Long> excluded, int limit) {
        LambdaQueryWrapper<AiRecommendationRun> query = expired(tenantId, cutoff)
                .notIn(!excluded.isEmpty(), AiRecommendationRun::getId, excluded)
                .select(AiRecommendationRun::getId)
                .orderByAsc(AiRecommendationRun::getCreatedAt)
                .orderByAsc(AiRecommendationRun::getId)
                .last("LIMIT " + limit);
        return toIds(runMapper.selectObjs(query));
    }

    private static List<Long> toIds(List<?> values) {
        List<Long> ids = new ArrayList<>();
        if (values != null) {
            for (Object value : values) {
                if (value instanceof Number number) {
                    ids.add(number.longValue());
                }
            }
        }
        return ids;
    }
}
