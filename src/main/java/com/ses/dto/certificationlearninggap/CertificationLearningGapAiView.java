package com.ses.dto.certificationlearninggap;

import com.ses.dto.skillgap.AiCourseCandidateResult;
import com.ses.dto.skillgap.SkillGapResult;

/** staffingのrule gapとAIのcourse候補を分離した応答。AIは公式評価・配置・採否を持たない。 */
public record CertificationLearningGapAiView(
        SkillGapResult ruleGap,
        AiCourseCandidateResult aiCandidate) {
}
