package com.ses.service.certificationlearninggap;

import com.ses.dto.certificationlearninggap.CertificationLearningGapAiView;
import com.ses.service.SkillGapService;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;

/** staffing as-ofのrule gapを先に確定し、AIをcandidate-onlyで重ねる。 */
public interface CertificationLearningGapAiService {

    CertificationLearningGapAiView suggest(Long engineerId, Long projectId, LocalDate asOf,
                                           LocalDate periodFrom, LocalDate periodTo,
                                           SkillGapService.DemandSource demandSource,
                                           Long actorUserId, Authentication authentication);
}
