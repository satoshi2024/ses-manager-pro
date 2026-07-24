package com.ses.controller.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.constant.SkillSheetConstants;
import com.ses.common.result.ApiResult;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/skillsheet-templates")
@RequiredArgsConstructor
public class SkillSheetTemplateApiController {

    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResult<List<Map<String, String>>> getTemplates() {
        String templatesJson = systemConfigService.getString(
                SkillSheetConstants.CONFIG_KEY_TEMPLATES, SkillSheetConstants.DEFAULT_TEMPLATES_JSON);
        try {
            List<Map<String, String>> templates = objectMapper.readValue(templatesJson, new TypeReference<>() {});
            return ApiResult.success(templates);
        } catch (Exception e) {
            // 設定値が壊れていても様式選択が空にならないよう、既定の様式を返す
            log.error("Failed to parse skillsheet.templates config", e);
            try {
                return ApiResult.success(objectMapper.readValue(
                        SkillSheetConstants.DEFAULT_TEMPLATES_JSON, new TypeReference<>() {}));
            } catch (Exception ignored) {
                return ApiResult.success(List.of(
                    Map.of("id", SkillSheetConstants.TEMPLATE_STANDARD, "name", "自社標準"),
                    Map.of("id", SkillSheetConstants.TEMPLATE_SIMPLE, "name", "簡易")
                ));
            }
        }
    }
}
