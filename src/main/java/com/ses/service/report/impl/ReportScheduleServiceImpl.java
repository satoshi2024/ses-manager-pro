package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.entity.ReportSchedule;
import com.ses.entity.ReportTemplateVersion;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.service.report.ReportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** scheduleを管理し、二重実行防止用lock keyをDB一意制約へ保存する。 */
@Service
@RequiredArgsConstructor
public class ReportScheduleServiceImpl implements ReportScheduleService {

    private static final String TENANT_ID = "default";
    private static final String TIMEZONE = "Asia/Tokyo";
    private final ReportScheduleMapper scheduleMapper;
    private final ReportTemplateVersionMapper templateVersionMapper;

    @Override
    public List<ReportSchedule> list() {
        return scheduleMapper.selectList(new QueryWrapper<ReportSchedule>()
                .eq("tenant_id", TENANT_ID).orderByAsc("id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportSchedule create(ReportScheduleCreateRequest request) {
        if (request == null || request.getTemplateVersionId() == null
                || request.getCronExpression() == null || request.getCronExpression().isBlank()) {
            throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
        }
        ReportTemplateVersion version = templateVersionMapper.selectById(request.getTemplateVersionId());
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        LocalDateTime next = request.getNextRunAt() == null
                ? LocalDateTime.now(ZoneId.of(TIMEZONE)).plusMinutes(1) : request.getNextRunAt();
        ReportSchedule schedule = new ReportSchedule();
        schedule.setTenantId(TENANT_ID);
        schedule.setTemplateVersionId(request.getTemplateVersionId());
        schedule.setCronExpression(request.getCronExpression().trim());
        schedule.setTimezoneId(TIMEZONE);
        schedule.setEnabled(0);
        schedule.setLockKey("management-report:" + UUID.randomUUID());
        schedule.setNextRunAt(next);
        schedule.setCreatedBy(SecurityUtils.currentUserId());
        schedule.setUpdatedBy(SecurityUtils.currentUserId());
        scheduleMapper.insert(schedule);
        return schedule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportSchedule setEnabled(Long scheduleId, boolean enabled) {
        ReportSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) throw BusinessException.of(404, "error.managementReport.scheduleNotFound");
        schedule.setEnabled(enabled ? 1 : 0);
        schedule.setUpdatedBy(SecurityUtils.currentUserId());
        scheduleMapper.updateById(schedule);
        return schedule;
    }
}
