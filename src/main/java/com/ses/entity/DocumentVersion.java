package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文書版（t_document_version）。append-only。
 *
 * <p>設計上の不変条件:</p>
 * <ul>
 *   <li>version_noはdocument_id内で単調増加し、削除・上書き不可。</li>
 *   <li>{@code (source_type, business_key, version_discriminator)} のUNIQUE制約で冪等登録を保証する。</li>
 *   <li>{@code scan_status} がNULLまたはPENDING/REJECTEDの場合はダウンロード不可（fail-closed）。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document_version")
public class DocumentVersion extends BaseEntity {

    /** 文書ID */
    private Long documentId;

    /** 版番号（1始まり、document_id内で単調増加） */
    private Integer versionNo;

    /**
     * Storageオブジェクトキー（推測困難なUUIDベース。元ファイル名はpathに使わない）。
     * R5.4: objectKey SHALL 推測困難かつtenant分離
     */
    private String storageKey;

    /** 元ファイル名（表示・manifest用） */
    private String originalName;

    /** MIMEタイプ */
    private String contentType;

    /** ファイルサイズ（バイト） */
    private Long sizeBytes;

    /**
     * SHA-256ハッシュ値（hex小文字・64文字）。
     * R6: storage binary改ざん時にhash不一致を検知する。
     */
    private String sha256;

    /**
     * 取得経路。
     * GENERATED / RECEIVED / CLOUDSIGN_SIGNED / CLOUDSIGN_CERT / MANUAL
     */
    private String sourceType;

    /**
     * 業務一意キー（source_typeとの組み合わせで冪等制御に使用）。
     * 例: "INVOICE:123" (Invoice ID=123の請求書PDF)
     */
    private String businessKey;

    /**
     * 版識別子（同一business_keyでの複数版区別）。
     * 例: "v1", "2025-03", 再送時の日時stamp
     */
    private String versionDiscriminator;

    /**
     * 外部文書ID（CloudSign document_id等）。
     * R2.3: CloudSign外部document IDを記録する。
     */
    private String externalId;

    /**
     * scanステータス。
     * PENDING（未scan）/ CLEAN（合格）/ REJECTED（拒否）/ SKIPPED（scan対象外）
     * NULLは未scan扱い＝閲覧不可（design §6.1のNULL区別）。
     */
    private String scanStatus;

    /** 差替理由（旧版との差分説明。訂正・差替時に必須） */
    private String changeReason;

    /** 登録ユーザーID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
