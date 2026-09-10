package com.ses.dto.certificationlearninggap;

import java.math.BigDecimal;

/** 要員本人が選択する研修catalogの公開projection。tenant・監査・内部関連は含めない。 */
public record TrainingCourseCatalogView(
        Long id,
        String provider,
        String name,
        String description,
        BigDecimal costJpy,
        Integer periodDays,
        Integer capacity,
        Integer activeFlag,
        Integer version) {
}
