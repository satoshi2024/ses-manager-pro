package com.ses.service.skillgap;

import com.ses.dto.skillgap.AiCourseCandidateResult;
import com.ses.dto.skillgap.SkillGapResult;

import java.time.LocalDate;
import java.util.List;

/** rule gapを正本に保ったままAI course候補を返すcandidate-only service。 */
public interface AiLearningCandidateService {

    AiCourseCandidateResult suggest(SkillGapResult ruleGap, List<Long> ruleBasedCourseIds,
                                    LocalDate asOf, Long actorUserId);

    void accept(AiCourseCandidateResult candidate, Long humanActorUserId, String reason);

    void reject(AiCourseCandidateResult candidate, Long humanActorUserId, String reason);

    /** 永続化candidateを再読込し、scope・期限・状態を再検証して判断監査だけを追記する。 */
    void acceptCandidate(Long candidateId, Long humanActorUserId, String reason);

    void rejectCandidate(Long candidateId, Long humanActorUserId, String reason);

    void acceptCandidate(Long candidateId, Long expectedEngineerId, Long humanActorUserId, String reason);

    void rejectCandidate(Long candidateId, Long expectedEngineerId, Long humanActorUserId, String reason);
}
