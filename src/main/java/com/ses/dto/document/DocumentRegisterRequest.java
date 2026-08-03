package com.ses.dto.document;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 文書登録リクエストDTO（生成文書・受領文書共通）。
 */
@Data
@Builder
public class DocumentRegisterRequest {

    /** 文書種別コード（m_document_type.code） */
    private String documentType;

    /** 文書番号（任意） */
    private String documentNo;

    /** 文書タイトル（任意） */
    private String title;

    /** 相手先区分: CUSTOMER / BP / INTERNAL */
    private String counterpartyType;

    /** 相手先ID */
    private Long counterpartyId;

    /** 相手先名スナップショット（登録時に固定） */
    private String counterpartyNameSnapshot;

    /** 取引日 */
    private LocalDate transactionDate;

    /** 金額（円、任意） */
    private BigDecimal amount;

    /** 方向: OUTGOING / INCOMING / INTERNAL */
    private String direction;

    // ---- 版情報 ----

    /** 取得経路: GENERATED / RECEIVED / CLOUDSIGN_SIGNED / CLOUDSIGN_CERT / MANUAL */
    private String sourceType;

    /**
     * 業務一意キー（冪等制御用）。
     * 例: "INVOICE:123"（請求書ID=123のPDF）
     */
    private String businessKey;

    /** 版識別子（同一business_keyでの複数版区別） */
    private String versionDiscriminator;

    /** 外部文書ID（CloudSign document_id等、任意） */
    private String externalId;

    /** 元ファイル名（表示・manifest用） */
    private String originalName;

    /** MIMEタイプ */
    private String contentType;

    /** 差替理由（訂正・差替時） */
    private String changeReason;

    // ---- 業務リンク情報 ----

    /** 紐付け先エンティティ種別（CONTRACT/CUSTOMER/PROJECT等） */
    private String targetType;

    /** 紐付け先エンティティID */
    private Long targetId;
}
