package com.ses.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 契約書ドキュメント（テンプレートから生成した送信原本と、CloudSign 配送・署名の状態証跡）。
 *
 * <p>HFP-02: {@code pdfSha256} は送信原本hashとして不変。締結済みPDF／証明書は
 * {@code signedPdfSha256}／{@code certificateSha256} と文書台帳ID（archive document id）で別管理する。
 * {@code dispatchState} は技術的な配送工程、{@code status} は業務状態を示す。
 */
@Data @EqualsAndHashCode(callSuper=true) @TableName("t_contract_document")
public class ContractDocument extends BaseEntity {
    private Long contractId;
    private Long templateId;
    private Integer templateVersion;
    private String renderedHtml;
    private String pdfPath;
    /** 送信原本PDFのSHA-256。締結済みPDF hashで上書きしない。 */
    private String pdfSha256;
    private String cloudsignDocumentId;
    private String cloudsignFileId;
    private String status;
    private String recipientName;
    private String recipientEmail;
    private String signedPdfPath;
    private String certificatePath;
    /** 締結済みPDFのSHA-256（provider artifact）。 */
    private String signedPdfSha256;
    /** 合意締結証明書PDFのSHA-256（provider artifact）。 */
    private String certificateSha256;
    /** 文書台帳(legal document ledger)の署名済みPDF document id。 */
    private Long signedArchiveDocumentId;
    /** 文書台帳(legal document ledger)の証明書PDF document id。 */
    private Long certificateArchiveDocumentId;
    private String cloudsignParticipantId;
    /** provider raw numeric status（未知値も保存可）。 */
    private Integer cloudsignStatus;
    /** 技術的な配送工程（com.ses.common.enums.DispatchState）。 */
    private String dispatchState;
    /** 一送信操作のUUID。外部照合markerの元。 */
    private String operationId;
    /** source/recipient/title/optionsをcanonicalizeしたhash。 */
    private String sendPayloadSha256;
    private Integer dispatchAttemptCount;
    private java.time.LocalDateTime nextAttemptAt;
    private java.time.LocalDateTime claimedAt;
    private String claimOwner;
    /** PIIを含まないprovider error分類code。 */
    private String lastProviderErrorCode;
    @Version
    private Integer version;
    private java.time.LocalDateTime sentAt;
    private java.time.LocalDateTime completedAt;
    private java.time.LocalDateTime lastSyncedAt;
    private String errorMessage;
}
