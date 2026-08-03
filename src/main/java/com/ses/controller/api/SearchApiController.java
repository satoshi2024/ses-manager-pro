package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.service.search.GlobalSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 横断検索 REST API コントローラー
 */
@RestController
@RequestMapping("/api/search")
public class SearchApiController {

    @Autowired
    private GlobalSearchService globalSearchService;

    @GetMapping
    public ApiResult<Map<String, List<GlobalSearchResultDTO>>> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "types", required = false) String typesParam) {

        List<String> types = null;
        if (typesParam != null && !typesParam.isBlank()) {
            types = Arrays.asList(typesParam.split(","));
        }

        Map<String, List<GlobalSearchResultDTO>> results = globalSearchService.search(query, types);
        return ApiResult.success(results);
    }
}
