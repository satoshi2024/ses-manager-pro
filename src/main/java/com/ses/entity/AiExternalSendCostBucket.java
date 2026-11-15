package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NF08 外部送信費用の日次・月次合計（V194 {@code t_ai_external_send_cost_bucket}）。
 * <p>cost_scope（provider単位）ごとにJST業務日・月の1行を持ち、予約中と精算済みの合計を {@code committed_yen} に積む。
 * 更新は {@code AiExternalSendCostBucketMapper} の列を明示したSQLだけで行う（汎用CRUDを持たない）。
 * 再承認（digestの変更）や月途中の上限変更でも合計を0へ戻さない。</p>
 */
@Data
@TableName("t_ai_external_send_cost_bucket")
public class AiExternalSendCostBucket {

    public static final String TYPE_DAY = "DAY";
    public static final String TYPE_MONTH = "MONTH";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String costScope;
    private String bucketType;
    private String bucketKey;
    private Long committedYen;
    private Long lastLimitYen;
    private byte[] lastApprovalDigest;
    /** アプリClockで観測した時刻の高水位（UTC）。DBの自動updated_atは時刻の比較基準にしない。 */
    private LocalDateTime clockHighWaterAt;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
