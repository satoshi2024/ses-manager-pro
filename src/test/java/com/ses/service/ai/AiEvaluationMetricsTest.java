package com.ses.service.ai;

import com.ses.entity.AiFeedback;
import com.ses.entity.AiOutcome;
import com.ses.entity.AiRecommendationItem;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiEvaluationMetricsTest {

    @Test
    void 面談率と成約率の分母は採用() {
        List<AiRecommendationItem> items = items(10);
        Map<Long, List<AiFeedback>> fb = new HashMap<>();
        fb.put(1L, List.of(accept(1L)));
        fb.put(2L, List.of(accept(2L)));
        Map<Long, List<AiOutcome>> oc = new HashMap<>();
        oc.put(1L, List.of(outcome(1L, "INTERVIEW")));

        assertEquals(50.0, AiEvaluationMetrics.existsRateAmongAccepted(items, fb, oc, "INTERVIEW"), 0.001);
        assertEquals(0.0, AiEvaluationMetrics.existsRateAmongAccepted(items, fb, oc, "WIN"), 0.001);
        assertEquals(100.0, AiEvaluationMetrics.adoptionRate(items, fb), 0.001);
    }

    @Test
    void 採用が0なら面談率は0() {
        List<AiRecommendationItem> items = items(10);
        Map<Long, List<AiFeedback>> fb = Map.of();
        Map<Long, List<AiOutcome>> oc = Map.of(1L, List.of(outcome(1L, "INTERVIEW")));
        assertEquals(0.0, AiEvaluationMetrics.existsRateAmongAccepted(items, fb, oc, "INTERVIEW"), 0.001);
    }

    @Test
    void precisionAtはrank以下の決定済み採用率() {
        List<AiRecommendationItem> items = items(10);
        Map<Long, List<AiFeedback>> fb = new HashMap<>();
        fb.put(1L, List.of(accept(1L)));
        fb.put(2L, List.of(accept(2L)));
        fb.put(3L, List.of(reject(3L)));
        fb.put(6L, List.of(accept(6L)));
        assertEquals(2 * 100.0 / 3, AiEvaluationMetrics.precisionAt(items, fb, 5), 0.001);
        assertEquals(3 * 100.0 / 4, AiEvaluationMetrics.precisionAt(items, fb, 10), 0.001);
    }

    private static List<AiRecommendationItem> items(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> {
            AiRecommendationItem item = new AiRecommendationItem();
            item.setId((long) i);
            item.setRankNo(i);
            return item;
        }).toList();
    }

    private static AiFeedback accept(Long itemId) {
        AiFeedback feedback = new AiFeedback();
        feedback.setItemId(itemId);
        feedback.setDecision("ACCEPT");
        return feedback;
    }

    private static AiFeedback reject(Long itemId) {
        AiFeedback feedback = new AiFeedback();
        feedback.setItemId(itemId);
        feedback.setDecision("REJECT");
        return feedback;
    }

    private static AiOutcome outcome(Long itemId, String type) {
        AiOutcome outcome = new AiOutcome();
        outcome.setItemId(itemId);
        outcome.setOutcomeType(type);
        return outcome;
    }
}
