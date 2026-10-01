package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエスト添付ファイルリンクエンティティ（t_service_attachment_link）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_attachment_link")
public class ServiceAttachmentLink {

    /** request・documentと同じtenantであることを短transaction内で確定する。 */
    private String tenantId;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** サービスリクエストID */
    private Long serviceRequestId;

    /** 紐づくコメントID (任意) */
    private Long commentId;

    /** 文書管理ID (t_document.id) */
    private Long documentId;

    /** 公開範囲 (PORTAL_VISIBLE, INTERNAL) */
    private String visibility;

    /** request・comment・visibility・content hashを含む安定した業務冪等キー。 */
    private String businessKey;

    /** ファイル名 */
    private String fileName;

    /** ファイルサイズ (バイト) */
    private Long fileSize;

    /** 作成日時 */
    private LocalDateTime createdAt;
}
