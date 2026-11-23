package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 機能の開放の署名（{@code t_feature_sign_off}、V203）。種別ごとに確認済み（CONFIRMED）は1件だけ（生成列 {@code active_slot} と
 * 一意索引）。署名者の氏名・元の file 名・文書は消去の対象（design §6.6）で、一覧の応答には氏名を出さない。氏名等を NULL にする更新は
 * {@code UpdateWrapper.set(col, null)} で列を名指しする。
 */
@Data
@TableName("t_feature_sign_off")
public class FeatureSignOff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 署名の種別 code（目録で検査する）。 */
    private String kindCode;

    /** 決定 ID・文書 ID・ticket 等。 */
    private String reference;

    /** 署名者の役割の code。 */
    private String signerRole;

    /** 署名者の氏名（任意の個人データ）。 */
    private String signerName;

    /** 署名日（JST 業務日）。 */
    private LocalDate signedOn;

    /** 失効日（JST 業務日。当日から無効）。 */
    private LocalDate expiresOn;

    /** 種別ごとの値（JSON。秘密値を置かない）。 */
    private String structuredValues;

    /** 文書の保存名（消去の後は NULL）。 */
    private String storedName;

    /** 文書の元の file 名（消去の対象）。 */
    private String originalName;

    /** DECISION / EVIDENCE。 */
    private String kindClass;

    /** 文書の SHA-256（小文字 hex）。 */
    private String contentSha256;

    private Long sizeBytes;

    /** REGISTERED / CONFIRMED / REJECTED / REVOKED / SUPERSEDED / EXPIRED / PURGED。 */
    private String status;

    /** 確認済みの署名だけ1（STORED の生成列。書かない）。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeSlot;

    private Long registeredBy;

    private LocalDateTime registeredAt;

    private Long confirmedBy;

    private LocalDateTime confirmedAt;

    private Long endedBy;

    private LocalDateTime endedAt;

    private String endReason;

    private Long supersededById;

    private LocalDate purgeAfter;

    private LocalDateTime purgedAt;

    private Integer version;
}
