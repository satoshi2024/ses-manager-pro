package com.ses.dto.portal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顧客ポータルCSAT評価投稿リクエストDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalCsatCreateRequest {

    @NotNull(message = "評価スコアは必須です")
    @Min(value = 1, message = "評価は1以上を指定してください")
    @Max(value = 5, message = "評価は5以下を指定してください")
    private Integer score;

    private String feedbackComment;
}
