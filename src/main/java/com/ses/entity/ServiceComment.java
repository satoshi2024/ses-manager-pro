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
 * サービスリクエストコメント・内部メモエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_comment")
public class ServiceComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long serviceRequestId;

    private String authorType;

    private Long authorId;

    private String authorName;

    private String visibility;

    private String commentText;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
