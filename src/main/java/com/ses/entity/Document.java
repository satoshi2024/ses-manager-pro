package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 文書台帳（t_document）。
 *
 * <p>設計上の不変条件:</p>
 * <ul>
 *   <li>{@code retention_until IS NULL} は「起算日未確定（下書き）」を意味し、廃棄候補に入れない。</li>
 *   <li>{@code legal_hold_flag=1} の間は廃棄申請を作れない。</li>
 *   <li>status が CONFIRMED 以降は通常UIから上書き・物理削除不可。</li>
 *   <li>{@code version} は楽観ロックに使用する（MyBatis-Plus {@code @Version}）。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document")
public class Document extends BaseEntity {

    /** テナントID */
    private String tenantId;

    /** 法人ID（将来multi-entity用、現在は使用しない） */
    private String legalEntityId;

    /** 文書種別コード (m_document_type.code 参照) */
    private String documentType;

    /** 文書番号 */
    private String documentNo;

    /** 文書タイトル */
    private String title;

    /** 相手先区分: CUSTOMER / BP / INTERNAL */
    private String counterpartyType;

    /** 相手先ID（顧客/BPマスタ）。内部文書はNULL */
    private Long counterpartyId;

    /**
     * 相手先名スナップショット（検索・証跡用）。
     * 登録時に固定し、顧客/BP名称の変更で過去文書の表示を変えない（design §6.1）。
     * 内部文書はNULL。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String counterpartyNameSnapshot;

    /** 取引日 */
    private LocalDate transactionDate;

    /**
     * 金額（円）。BigDecimalで保持し、doubleは使わない。
     * 金額がない文書（契約書等）はNULL。
     */
    private BigDecimal amount;

    /** 通貨コード（デフォルト: JPY） */
    private String currency;

    /** 方向: OUTGOING / INCOMING / INTERNAL */
    private String direction;

    /**
     * 状態機械。
     * DRAFT → CONFIRMED → AMENDED / CANCELLED / DISPOSED（終端）。
     * CONFIRMED後は通常UIから直接変更不可。
     */
    private String status;

    /**
     * 保存期限（計算済み固定値）。
     * NULLは起算日未確定（下書き段階）を意味し、廃棄候補から除外する。
     * design §6.1 / platform-invariants §1.1 の NULL区別に従う。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate retentionUntil;

    /**
     * 法的hold中フラグ (1=hold中)。
     * 1の間は廃棄申請・自動廃棄を拒否する（R4.2）。
     */
    private Integer legalHoldFlag;

    /**
     * 楽観ロック用バージョン。
     * 同時確定・訂正・廃棄の競合をCASで検出する（design §6.3）。
     */
    @Version
    @TableField("version")
    private Long version;

    /** 作成者ユーザーID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
