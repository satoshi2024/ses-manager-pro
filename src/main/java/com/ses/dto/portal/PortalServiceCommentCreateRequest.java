package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ポータル利用者向けサービスリクエスト返信コメント登録DTO (WIP-8)
 * （visibility や authorId などの内部専用フィールドを構造的に排除）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceCommentCreateRequest {

    @NotBlank(message = "コメント内容は必須です")
    private String commentText;
}
