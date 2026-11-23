package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 機能の開放の世代（{@code t_feature_enablement_generation}、V203）。tenant ごとに1行で、全ての書込みの transaction が最初に
 * lock して1つ上げる（design §2.2・§5.1）。snapshot はこの値で新旧を判断する。
 */
@Data
@TableName("t_feature_enablement_generation")
public class FeatureEnablementGeneration {

    /** 設定の tenant（主キー）。 */
    @TableId(type = IdType.INPUT)
    private String tenantId;

    /** 変更の通し番号。 */
    private Long generation;

    /** 最後に上げた時刻。 */
    private LocalDateTime updatedAt;
}
