package com.ses.dto.certificationlearninggap;

import com.ses.dto.skillgap.SkillGapItem;

import java.util.List;

/** 資格・学習・gapを同じengineer母集団から組み立てる一覧/detail応答。 */
public record CertificationLearningGapRow(
        Long engineerId,
        String engineerName,
        String engineerStatus,
        String lifecycleState,
        List<CertificationLearningGapCertificationDto> certifications,
        List<CertificationLearningGapTrainingDto> trainings,
        String gapStatus,
        String gapUnavailableReason,
        Long gapSnapshotId,
        List<SkillGapItem> skillGaps) {
}
