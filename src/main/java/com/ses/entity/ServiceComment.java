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
 * サービスリクエストコメントエンティティ（t_service_comment）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_comment")
public class ServiceComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** サービスリクエストID */
    private Long serviceRequestId;

    /** 投稿者種別 (INTERNAL_USER, PORTAL_USER, SYSTEM) */
    private String authorType;

    /** 投稿者ID */
    private Long authorId;

    /** 投稿者名 */
    private String authorName;

    /** 公開範囲 (PORTAL_VISIBLE, INTERNAL) */
    private String visibility;

    /** コメント本文 */
    private String commentText;

    /** 投稿日時 */
    private LocalDateTime createdAt;

    /** 更新日時 */
    private LocalDateTime updatedAt;
}
