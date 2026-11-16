package com.ses.dto.lifecycle.dual;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 二者確認の証跡候補（NF01細粒度epoch計画 §5.2 A2・A6）。§5.1 条件3〜9を満たすSKILL_SHEETのCLEAN版だけを返す。
 * storage key・hash・scan状態は返さない。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DualConfirmationCandidateDto {
    private Long documentId;
    private Long documentVersionId;
    private Integer versionNo;
    private LocalDateTime createdAt;
    private String title;
}
