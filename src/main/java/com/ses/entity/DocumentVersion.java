package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文書版（t_document_version）。append-only。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document_version")
public class DocumentVersion extends BaseEntity {

    /** テナントID */
    private String tenantId;

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
     */
    private String sha256;

    /**
     * 取得経路。
     * GENERATED / RECEIVED / CLOUDSIGN_SIGNED / CLOUDSIGN_CERT / MANUAL
     */
    private String sourceType;

    /**
     * 業務一意キー（source_typeとの組み合わせで冪等制御に使用）。
     */
    private String businessKey;

    /**
     * 版識別子（同一business_keyでの複数版区別）。
     */
    private String versionDiscriminator;

    /**
     * 外部文書ID（CloudSign document_id等）。
     */
    private String externalId;

    /**
     * scanステータス。
     * PENDING / CLEAN / REJECTED / SKIPPED
     */
    private String scanStatus;

    /** 差替理由 */
    private String changeReason;

    /** 登録ユーザーID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
