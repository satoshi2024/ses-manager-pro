package com.ses.service.report;

import com.ses.dto.report.ReportTemplateVersionCreateRequest;
import com.ses.entity.ReportTemplate;
import com.ses.entity.ReportTemplateVersion;

import java.util.List;

/** 管理レポートtemplate/versionの管理境界。 */
public interface ReportTemplateService {
    List<ReportTemplate> listTemplates();
    List<ReportTemplateVersion> listVersions(Long templateId);
    ReportTemplate createTemplate(String key, String name);
    ReportTemplateVersion createVersion(Long templateId, ReportTemplateVersionCreateRequest request);
    ReportTemplateVersion updateVersion(Long versionId, ReportTemplateVersionCreateRequest request);
    ReportTemplateVersion publishVersion(Long versionId);
    ReportTemplateVersion getPublishedVersion(Long versionId);
}
