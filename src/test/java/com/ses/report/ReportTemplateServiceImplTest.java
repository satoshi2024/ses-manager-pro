package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportTemplateVersionCreateRequest;
import com.ses.entity.ReportTemplateVersion;
import com.ses.mapper.ReportTemplateMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.service.report.impl.ReportTemplateServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportTemplateServiceImplTest {

    @Test
    void draftVersionは画面からsectionとrecipientを編集できる() {
        ReportTemplateMapper templateMapper = mock(ReportTemplateMapper.class);
        ReportTemplateVersionMapper versionMapper = mock(ReportTemplateVersionMapper.class);
        ReportTemplateServiceImpl service = new ReportTemplateServiceImpl(templateMapper, versionMapper,
                new ObjectMapper());
        ReportTemplateVersion version = draftVersion();
        when(versionMapper.selectById(3L)).thenReturn(version);
        ReportTemplateVersionCreateRequest request = new ReportTemplateVersionCreateRequest();
        request.setSectionConfigJson("{\"sections\":[\"sales\"]}");
        request.setRecipientConfigJson("{\"roles\":[\"管理者\"]}");

        ReportTemplateVersion result = service.updateVersion(3L, request);

        assertThat(result.getSectionConfigJson()).contains("sales");
        assertThat(result.getRecipientConfigJson()).contains("管理者");
        verify(versionMapper).updateById(version);
    }

    @Test
    void publishedVersionは編集できない() {
        ReportTemplateMapper templateMapper = mock(ReportTemplateMapper.class);
        ReportTemplateVersionMapper versionMapper = mock(ReportTemplateVersionMapper.class);
        ReportTemplateServiceImpl service = new ReportTemplateServiceImpl(templateMapper, versionMapper,
                new ObjectMapper());
        ReportTemplateVersion version = draftVersion();
        version.setStatus("PUBLISHED");
        when(versionMapper.selectById(3L)).thenReturn(version);

        assertThatThrownBy(() -> service.updateVersion(3L, new ReportTemplateVersionCreateRequest()))
                .hasMessageContaining("error.managementReport.publishedVersionImmutable");
    }

    private ReportTemplateVersion draftVersion() {
        ReportTemplateVersion version = new ReportTemplateVersion();
        version.setId(3L);
        version.setStatus("DRAFT");
        version.setSectionConfigJson("{\"sections\":[\"sales\"]}");
        version.setFormatConfigJson("{\"formats\":[\"PDF\",\"XLSX\",\"CSV\"]}");
        version.setRecipientConfigJson("{\"roles\":[\"管理者\",\"マネージャー\"]}");
        version.setScopeConfigJson("{\"ownerType\":\"CURRENT_USER_SCOPE\"}");
        version.setTimezoneId("Asia/Tokyo");
        version.setRetentionYears(7);
        return version;
    }
}
