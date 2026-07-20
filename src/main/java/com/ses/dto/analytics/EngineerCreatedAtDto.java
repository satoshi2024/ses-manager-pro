package com.ses.dto.analytics;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 稼動率推移の集計専用の軽量射影（idと作成日時のみ）。
 * Engineerエンティティ全体（remarks等の大きな列を含む）を丸ごと読み込む代わりに
 * 必要な列だけを取得し、大量データ時のメモリ使用量を抑える。
 */
@Data
public class EngineerCreatedAtDto {
    private Long id;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deletedFlag;
}
