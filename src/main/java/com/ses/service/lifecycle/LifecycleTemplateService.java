package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.entity.LifecycleTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * ライフサイクルテンプレート管理サービス
 */
public interface LifecycleTemplateService extends IService<LifecycleTemplate> {

    LifecycleTemplate findActiveByTypeAndDate(String templateType, LocalDate asOf);

    LifecycleTemplateDto getTemplateDetail(Long id);

    List<LifecycleTemplateDto> listTemplates(String templateType, String status);

    LifecycleTemplateDto createTemplate(LifecycleTemplateDto dto, Long userId);

    LifecycleTemplateDto updateTemplate(Long id, LifecycleTemplateDto dto, Long userId);

    void toggleStatus(Long id, String status, Long userId);
}
