package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.EmailTemplate;
import com.ses.service.EmailTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * メールテンプレートAPIコントローラー
 */
@RestController
@RequestMapping("/api/email-templates")
@RequiredArgsConstructor
public class EmailTemplateApiController {

    private final EmailTemplateService emailTemplateService;

    /**
     * 全てのテンプレートを取得する
     */
    @GetMapping
    public ApiResult<List<EmailTemplate>> getAllTemplates() {
        return ApiResult.success(emailTemplateService.list());
    }

    /**
     * IDでテンプレートを取得する
     */
    @GetMapping("/{id}")
    public ApiResult<EmailTemplate> getTemplateById(@PathVariable Long id) {
        var entity = emailTemplateService.getById(id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(entity);
    }

    /**
     * テンプレートを作成する
     */
    @PostMapping
    public ApiResult<Boolean> createTemplate(@RequestBody EmailTemplate emailTemplate) {
        return ApiResult.success(emailTemplateService.save(emailTemplate));
    }

    /**
     * テンプレートを更新する
     */
    @PutMapping("/{id}")
    public ApiResult<Boolean> updateTemplate(@PathVariable Long id, @RequestBody EmailTemplate emailTemplate) {
        emailTemplate.setId(id);
        boolean success = emailTemplateService.updateById(emailTemplate);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * テンプレートを削除する
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> deleteTemplate(@PathVariable Long id) {
        boolean success = emailTemplateService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }
}
