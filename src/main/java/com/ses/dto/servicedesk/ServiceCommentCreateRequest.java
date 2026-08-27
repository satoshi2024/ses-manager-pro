package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * コメント・内部メモ投稿リクエストDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCommentCreateRequest {

    @NotBlank(message = "コメント本文は必須です")
    private String commentText;

    private String visibility; // INTERNAL or PORTAL_VISIBLE
}
