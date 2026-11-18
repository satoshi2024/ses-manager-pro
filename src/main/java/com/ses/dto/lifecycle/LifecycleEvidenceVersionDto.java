package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * タスク証跡として選択できる文書版（scan結果CLEAN）の表示用DTO（NF01 Q-9）。
 * 元ファイル名・storage key・hashは含めない。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleEvidenceVersionDto {

    /** 文書版ID（完了時に {@code documentVersionId} として送る値） */
    private Long id;

    /** 文書ID */
    private Long documentId;

    /** 版番号 */
    private Integer versionNo;

    /** 版の登録日時 */
    private LocalDateTime createdAt;
}
