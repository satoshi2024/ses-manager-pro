package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * サービスリクエストコメント作成リクエスト
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCommentCreateRequest {

    @NotBlank(message = "コメント内容は必須です")
    private String commentText;

    /** 公開範囲 (PORTAL_VISIBLE, INTERNAL) - 内部ユーザーのみ指定可 */
    private String visibility;
}
