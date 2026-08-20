package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AiFeedback;
import com.ses.entity.AiRecommendationItem;
import com.ses.mapper.AiFeedbackMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.service.ai.AiFeedbackService;
import com.ses.service.ai.AiPiiMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AiFeedbackServiceImpl implements AiFeedbackService {

    public static final Set<String> REASON_CODES = Set.of(
            "SKILL_MISMATCH", "PRICE_MISMATCH", "AVAILABILITY", "LOCATION",
            "CUSTOMER_REQUEST", "ALREADY_ASSIGNED", "OTHER_REDACTED");

    private static final Set<String> DECISIONS = Set.of("ACCEPT", "REJECT", "HOLD");

    private final AiFeedbackMapper feedbackMapper;
    private final AiRecommendationItemMapper itemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiFeedback record(Long itemId, String decision, String reasonCode, String comment) {
        if (itemId == null) {
            throw new BusinessException(400, "itemId は必須です");
        }
        AiRecommendationItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(404, "推薦候補が見つかりません");
        }
        if (decision != null && !decision.isBlank() && !DECISIONS.contains(decision)) {
            throw new BusinessException(400, "decision が不正です");
        }
        if (reasonCode != null && !reasonCode.isBlank() && !REASON_CODES.contains(reasonCode)) {
            throw new BusinessException(400, "reasonCode が不正です");
        }
        AiFeedback feedback = new AiFeedback();
        feedback.setItemId(itemId);
        feedback.setDecision(decision == null || decision.isBlank() ? null : decision);
        feedback.setReasonCode(reasonCode == null || reasonCode.isBlank() ? null : reasonCode);
        String redacted = AiPiiMasker.stripHtml(comment);
        if (redacted != null && redacted.length() > 500) {
            redacted = redacted.substring(0, 500);
        }
        feedback.setCommentRedacted(redacted);
        feedback.setDecidedBy(SecurityUtils.currentUserId());
        feedback.setDecidedAt(LocalDateTime.now());
        feedbackMapper.insert(feedback);
        if ("ACCEPT".equals(feedback.getDecision())) {
            item.setSelectedFlag(1);
            itemMapper.updateById(item);
        }
        return feedback;
    }
}
