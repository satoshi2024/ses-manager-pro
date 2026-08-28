package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.report.ReportSectionKey;
import com.ses.dto.report.ReportTemplateVersionCreateRequest;
import com.ses.entity.ReportTemplate;
import com.ses.entity.ReportTemplateVersion;
import com.ses.mapper.ReportTemplateMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.service.report.ReportTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** template/versionを明示的な版として保存し、公開後は変更しない。 */
@Service
@RequiredArgsConstructor
public class ReportTemplateServiceImpl implements ReportTemplateService {

    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_TIMEZONE = "Asia/Tokyo";
    private final ReportTemplateMapper templateMapper;
    private final ReportTemplateVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<ReportTemplate> listTemplates() {
        return templateMapper.selectList(new QueryWrapper<ReportTemplate>()
                .eq("tenant_id", DEFAULT_TENANT).orderByAsc("id"));
    }

    @Override
    public List<ReportTemplateVersion> listVersions(Long templateId) {
        return versionMapper.selectList(new QueryWrapper<ReportTemplateVersion>()
                .eq("tenant_id", DEFAULT_TENANT).eq("template_id", templateId)
                .orderByDesc("version_no"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportTemplate createTemplate(String key, String name) {
        if (key == null || key.isBlank() || name == null || name.isBlank()) {
            throw BusinessException.of(400, "error.managementReport.templateInvalid");
        }
        if (templateMapper.selectOne(new QueryWrapper<ReportTemplate>()
                .eq("tenant_id", DEFAULT_TENANT).eq("template_key", key)) != null) {
            throw BusinessException.of(409, "error.managementReport.templateDuplicated");
        }
        ReportTemplate template = new ReportTemplate();
        template.setTenantId(DEFAULT_TENANT);
        template.setTemplateKey(key.trim());
        template.setTemplateName(name.trim());
        template.setStatus("DRAFT");
        template.setCreatedBy(SecurityUtils.currentUserId());
        template.setUpdatedBy(SecurityUtils.currentUserId());
        templateMapper.insert(template);
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportTemplateVersion createVersion(Long templateId, ReportTemplateVersionCreateRequest request) {
        ReportTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BusinessException.of(404, "error.managementReport.templateNotFound");
        }
        int nextVersion = versionMapper.selectList(new QueryWrapper<ReportTemplateVersion>()
                        .eq("template_id", templateId))
                .stream().map(ReportTemplateVersion::getVersionNo)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0) + 1;
        ReportTemplateVersion version = new ReportTemplateVersion();
        version.setTenantId(DEFAULT_TENANT);
        version.setTemplateId(templateId);
        version.setVersionNo(nextVersion);
        version.setStatus("DRAFT");
        version.setSectionConfigJson(defaultIfBlank(request == null ? null : request.getSectionConfigJson(),
                defaultSectionsJson()));
        version.setFormatConfigJson(defaultIfBlank(request == null ? null : request.getFormatConfigJson(),
                "{\"formats\":[\"PDF\",\"XLSX\",\"CSV\"]}"));
        version.setRecipientConfigJson(defaultIfBlank(request == null ? null : request.getRecipientConfigJson(),
                "{\"roles\":[\"管理者\",\"マネージャー\"]}"));
        version.setScopeConfigJson(defaultIfBlank(request == null ? null : request.getScopeConfigJson(),
                "{\"ownerType\":\"CURRENT_USER_SCOPE\"}"));
        version.setTimezoneId(request != null && request.getTimezoneId() != null
                && !request.getTimezoneId().isBlank() ? request.getTimezoneId() : DEFAULT_TIMEZONE);
        version.setRetentionYears(request != null && request.getRetentionYears() != null
                ? request.getRetentionYears() : 7);
        if (!DEFAULT_TIMEZONE.equals(version.getTimezoneId()) || version.getRetentionYears() != 7) {
            throw BusinessException.of(400, "error.managementReport.policyFixed");
        }
        version.setCreatedBy(SecurityUtils.currentUserId());
        versionMapper.insert(version);
        return version;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportTemplateVersion updateVersion(Long versionId, ReportTemplateVersionCreateRequest request) {
        ReportTemplateVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw BusinessException.of(404, "error.managementReport.templateVersionNotFound");
        }
        if (!"DRAFT".equals(version.getStatus())) {
            throw BusinessException.of(409, "error.managementReport.publishedVersionImmutable");
        }
        if (request == null) {
            throw BusinessException.of(400, "error.managementReport.templateVersionInvalid");
        }
        if (request.getSectionConfigJson() != null && !request.getSectionConfigJson().isBlank()) {
            version.setSectionConfigJson(request.getSectionConfigJson().trim());
        }
        if (request.getFormatConfigJson() != null && !request.getFormatConfigJson().isBlank()) {
            version.setFormatConfigJson(request.getFormatConfigJson().trim());
        }
        if (request.getRecipientConfigJson() != null && !request.getRecipientConfigJson().isBlank()) {
            version.setRecipientConfigJson(request.getRecipientConfigJson().trim());
        }
        if (request.getScopeConfigJson() != null && !request.getScopeConfigJson().isBlank()) {
            version.setScopeConfigJson(request.getScopeConfigJson().trim());
        }
        if (request.getTimezoneId() != null && !request.getTimezoneId().isBlank()) {
            version.setTimezoneId(request.getTimezoneId().trim());
        }
        if (request.getRetentionYears() != null) {
            version.setRetentionYears(request.getRetentionYears());
        }
        if (!DEFAULT_TIMEZONE.equals(version.getTimezoneId()) || version.getRetentionYears() == null
                || version.getRetentionYears() != 7) {
            throw BusinessException.of(400, "error.managementReport.policyFixed");
        }
        versionMapper.updateById(version);
        return version;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportTemplateVersion publishVersion(Long versionId) {
        ReportTemplateVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw BusinessException.of(404, "error.managementReport.templateVersionNotFound");
        }
        versionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ReportTemplateVersion>()
                .eq("template_id", version.getTemplateId()).eq("status", "PUBLISHED")
                .set("status", "ARCHIVED"));
        version.setStatus("PUBLISHED");
        version.setPublishedAt(LocalDateTime.now(java.time.ZoneId.of(DEFAULT_TIMEZONE)));
        versionMapper.updateById(version);
        return version;
    }

    @Override
    public ReportTemplateVersion getPublishedVersion(Long versionId) {
        ReportTemplateVersion version = versionMapper.selectById(versionId);
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(404, "error.managementReport.templateVersionNotFound");
        }
        return version;
    }

    private String defaultSectionsJson() {
        return toJson(Map.of("sections", ReportSectionKey.DEFAULT_ORDER));
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
    }
}
