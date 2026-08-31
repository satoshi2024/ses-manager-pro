package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顧客ポータル コメント投稿リクエスト
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceCommentCreateRequest {

    @NotBlank(message = "コメント内容は必須です")
    private String commentText;
}
