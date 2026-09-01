package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.ai.CopilotCatalogResult;
import com.ses.service.ai.copilot.CopilotCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI経営コパイロット API（F1: catalog解決とrun記録）。
 */
@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
public class CopilotApiController {

    private static final int MAX_QUESTION_LENGTH = 2000;

    private final CopilotCatalogService copilotCatalogService;

    @PostMapping("/query")
    public ApiResult<CopilotCatalogResult> query(@Valid @RequestBody CopilotQueryRequest request) {
        return ApiResult.success(copilotCatalogService.resolveAndRecord(request.question()));
    }

    public record CopilotQueryRequest(
            @NotBlank(message = "質問を入力してください。")
            @Size(max = MAX_QUESTION_LENGTH, message = "質問は2000文字以内で入力してください。")
            String question
    ) {
    }
}
