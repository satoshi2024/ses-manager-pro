package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 署名の組（{@code t_feature_sign_off_bundle}、V203）。互いに束縛し合う署名の集合で、登録・確認・差替えの単位（design §2.6.1）。
 * 組の種類ごとに確認済みは1件だけ（生成列 {@code active_slot} と一意索引）。
 */
@Data
@TableName("t_feature_sign_off_bundle")
public class FeatureSignOffBundle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 目録の組の種類。 */
    private String bundleKind;

    /** REGISTERED / CONFIRMED / REJECTED / SUPERSEDED / REVOKED。 */
    private String status;

    /** 確認済みの組だけ1（STORED の生成列。書かない）。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeSlot;

    private Long registeredBy;

    private LocalDateTime registeredAt;

    private Long confirmedBy;

    private LocalDateTime confirmedAt;

    private Long endedBy;

    private LocalDateTime endedAt;

    private String endReason;

    private Integer version;
}
