package com.ses.service.ai;

import com.ses.entity.AiFeedback;
import com.ses.entity.AiOutcome;
import com.ses.entity.AiRecommendationItem;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * オンライン評価metric。分母は g10-pii-allowlist.md §8 に従う。
 */
public final class AiEvaluationMetrics {

    private AiEvaluationMetrics() {
    }

    /** 採用率: ACCEPT / (ACCEPT または REJECT)。未判断と HOLD は分母から除外。 */
    public static double adoptionRate(List<AiRecommendationItem> items,
                                      Map<Long, List<AiFeedback>> fbByItem) {
        int accept = 0;
        int judged = 0;
        for (AiRecommendationItem item : items) {
            Decision decision = decisionOf(item, fbByItem);
            if (decision == Decision.UNJUDGED || decision == Decision.HOLD) {
                continue;
            }
            judged++;
            if (decision == Decision.ACCEPT) {
                accept++;
            }
        }
        return judged == 0 ? 0 : accept * 100.0 / judged;
    }

    /** 面談率・成約率: EXISTS / 採用。採用 0 なら 0。COUNT ではない。 */
    public static double existsRateAmongAccepted(List<AiRecommendationItem> items,
                                                 Map<Long, List<AiFeedback>> fbByItem,
                                                 Map<Long, List<AiOutcome>> ocByItem,
                                                 String type) {
        List<AiRecommendationItem> accepted = items.stream()
                .filter(item -> decisionOf(item, fbByItem) == Decision.ACCEPT)
                .toList();
        if (accepted.isEmpty()) {
            return 0;
        }
        Set<Long> hits = new HashSet<>();
        for (AiRecommendationItem item : accepted) {
            boolean exists = ocByItem.getOrDefault(item.getId(), List.of()).stream()
                    .anyMatch(o -> type.equals(o.getOutcomeType()));
            if (exists) {
                hits.add(item.getId());
            }
        }
        return hits.size() * 100.0 / accepted.size();
    }

    /** precision@k: rank≤k かつ決定済みのうち採用。 */
    public static double precisionAt(List<AiRecommendationItem> items,
                                     Map<Long, List<AiFeedback>> fbByItem,
                                     int k) {
        List<AiRecommendationItem> ranked = items.stream()
                .filter(item -> item.getRankNo() != null && item.getRankNo() <= k)
                .toList();
        return adoptionRate(ranked, fbByItem);
    }

    private static Decision decisionOf(AiRecommendationItem item, Map<Long, List<AiFeedback>> fbByItem) {
        List<AiFeedback> list = fbByItem.getOrDefault(item.getId(), List.of());
        boolean hasAccept = list.stream().anyMatch(f -> "ACCEPT".equals(f.getDecision()));
        boolean hasReject = list.stream().anyMatch(f -> "REJECT".equals(f.getDecision()));
        if (hasAccept) {
            return Decision.ACCEPT;
        }
        if (hasReject) {
            return Decision.REJECT;
        }
        boolean hasHold = list.stream().anyMatch(f -> "HOLD".equals(f.getDecision()));
        return hasHold ? Decision.HOLD : Decision.UNJUDGED;
    }

    private enum Decision {
        ACCEPT, REJECT, HOLD, UNJUDGED
    }
}
