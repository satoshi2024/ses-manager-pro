package com.ses.dto.certificationlearninggap;

import com.ses.service.SkillGapService;

import java.time.LocalDate;

/** 資格・学習・skill gap一覧の共通検索条件。list/detail/count/exportで同じ値を使用する。 */
public record CertificationLearningGapFilter(
        Long engineerId,
        String engineerName,
        String engineerStatus,
        String lifecycleState,
        String certificationState,
        LocalDate asOf,
        Long projectId,
        SkillGapService.DemandSource demandSource) {
}
