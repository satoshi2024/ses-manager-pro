package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AIログエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_log")
public class AiLog extends BaseEntity {

    /**
     * t_ai_log には updated_at / deleted_flag 列が存在しないため、
     * BaseEntity 由来の共通カラムをマッピング対象外にする。
     * これを無効化しないと SELECT/INSERT 時に「Unknown column」で失敗する
     * （特に論理削除フィルタで一覧取得＝通知取得が壊れる）。
     */
    @TableField(exist = false)
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private Integer deletedFlag;

    /** リクエストタイプ: 'マッチング','スキルシート','営業メール' */
    private String requestType;

    /** リクエストパラメータ (JSON) */
    private String requestParams;

    /** レスポンステキスト */
    private String responseText;

    /** 使用トークン数 */
    private Integer tokensUsed;

    /** コスト (円) */
    private BigDecimal costJpy;

    /** 作成者ID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
