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
 * サービスリクエスト添付ファイルリンクエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_attachment_link")
public class ServiceAttachmentLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long serviceRequestId;

    private Long commentId;

    private Long documentId;

    private String visibility;

    private String fileName;

    private Long fileSize;

    private LocalDateTime createdAt;
}
