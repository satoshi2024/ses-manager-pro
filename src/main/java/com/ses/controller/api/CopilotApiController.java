package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.ai.CopilotQueryResult;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.CopilotQueryService;
import com.ses.service.ai.copilot.citation.CitationAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI経営コパイロット API（F2/A1: typed result + citation再認可）。
 */
@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
public class CopilotApiController {

    private static final int MAX_QUESTION_LENGTH = 2000;

    private final CopilotQueryService copilotQueryService;
    private final CitationAuthorizationService citationAuthorizationService;

    @PostMapping("/query")
    public ApiResult<CopilotQueryResult> query(@Valid @RequestBody CopilotQueryRequest request) {
        return ApiResult.success(copilotQueryService.query(request.question()));
    }

    @GetMapping("/citations")
    public ApiResult<ResolvedCitationDto> citation(@RequestParam("key") String citationKey) {
        return ApiResult.success(citationAuthorizationService.authorize(citationKey));
    }

    public record CopilotQueryRequest(
            @NotBlank(message = "質問を入力してください。")
            @Size(max = MAX_QUESTION_LENGTH, message = "質問は2000文字以内で入力してください。")
            String question
    ) {
    }
}
