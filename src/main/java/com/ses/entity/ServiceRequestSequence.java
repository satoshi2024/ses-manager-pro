package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエスト月次採番シーケンスエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_request_sequence")
public class ServiceRequestSequence {

    /**
     * 対象年月 (yyyyMM)
     */
    @TableId
    private String sequenceMonth;

    /**
     * 現在採番値 (1〜9999)
     */
    private Integer currentVal;

    /**
     * 作成日時
     */
    private LocalDateTime createdAt;

    /**
     * 更新日時
     */
    private LocalDateTime updatedAt;
}
