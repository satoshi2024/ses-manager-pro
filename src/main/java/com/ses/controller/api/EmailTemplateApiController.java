package com.ses.controller.api;

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
    public List<EmailTemplate> getAllTemplates() {
        return emailTemplateService.list();
    }

    /**
     * IDでテンプレートを取得する
     */
    @GetMapping("/{id}")
    public EmailTemplate getTemplateById(@PathVariable Long id) {
        return emailTemplateService.getById(id);
    }

    /**
     * テンプレートを作成する
     */
    @PostMapping
    public EmailTemplate createTemplate(@RequestBody EmailTemplate emailTemplate) {
        emailTemplateService.save(emailTemplate);
        return emailTemplate;
    }

    /**
     * テンプレートを更新する
     */
    @PutMapping("/{id}")
    public EmailTemplate updateTemplate(@PathVariable Long id, @RequestBody EmailTemplate emailTemplate) {
        emailTemplate.setId(id);
        emailTemplateService.updateById(emailTemplate);
        return emailTemplate;
    }

    /**
     * テンプレートを削除する
     */
    @DeleteMapping("/{id}")
    public void deleteTemplate(@PathVariable Long id) {
        emailTemplateService.removeById(id);
    }
}
