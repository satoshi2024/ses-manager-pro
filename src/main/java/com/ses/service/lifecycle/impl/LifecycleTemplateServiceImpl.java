package com.ses.service.lifecycle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.LifecycleTemplateTask;
import com.ses.entity.LifecycleTemplateTaskDep;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskDepMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.service.lifecycle.LifecycleDagValidator;
import com.ses.service.lifecycle.LifecycleTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ライフサイクルテンプレート管理サービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleTemplateServiceImpl extends ServiceImpl<LifecycleTemplateMapper, LifecycleTemplate>
        implements LifecycleTemplateService {

    private final LifecycleTemplateMapper templateMapper;
    private final LifecycleTemplateTaskMapper templateTaskMapper;
    private final LifecycleTemplateTaskDepMapper templateTaskDepMapper;
    private final LifecycleDagValidator dagValidator;

    @Override
    public LifecycleTemplate findActiveByTypeAndDate(String templateType, LocalDate asOf) {
        if (templateType == null || asOf == null) {
            return null;
        }
        return templateMapper.findActiveByTypeAndDate(templateType, asOf);
    }

    @Override
    public LifecycleTemplateDto getTemplateDetail(Long id) {
        LifecycleTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw BusinessException.of(404, "error.lifecycle.templateNotFound", "テンプレートが見つかりません");
        }

        List<LifecycleTemplateTask> tasks = templateTaskMapper.selectByTemplateId(id);
        List<LifecycleTemplateTaskDep> deps = templateTaskDepMapper.selectByTemplateId(id);

        Map<String, List<String>> depMap = new HashMap<>();
        for (LifecycleTemplateTaskDep dep : deps) {
            depMap.computeIfAbsent(dep.getSuccessorTaskCode(), k -> new ArrayList<>()).add(dep.getPredecessorTaskCode());
        }

        List<LifecycleTemplateTaskDto> taskDtos = tasks.stream().map(t -> LifecycleTemplateTaskDto.builder()
                .id(t.getId())
                .templateId(t.getTemplateId())
                .taskCode(t.getTaskCode())
                .taskName(t.getTaskName())
                .description(t.getDescription())
                .relativeDueDays(t.getRelativeDueDays())
                .assigneeRule(t.getAssigneeRule())
                .assigneeRuleValue(t.getAssigneeRuleValue())
                .isMandatory(t.getIsMandatory())
                .isBlocking(t.getIsBlocking())
                .evidenceType(t.getEvidenceType())
                .isEngineerVisible(t.getIsEngineerVisible())
                .targetEmploymentTypes(t.getTargetEmploymentTypes())
                .sortOrder(t.getSortOrder())
                .predecessorTaskCodes(depMap.getOrDefault(t.getTaskCode(), List.of()))
                .build()).collect(Collectors.toList());

        return LifecycleTemplateDto.builder()
                .id(template.getId())
                .templateType(template.getTemplateType())
                .name(template.getName())
                .description(template.getDescription())
                .versionNo(template.getVersionNo())
                .status(template.getStatus())
                .validFrom(template.getValidFrom())
                .validTo(template.getValidTo())
                .tasks(taskDtos)
                .build();
    }

    @Override
    public List<LifecycleTemplateDto> listTemplates(String templateType, String status) {
        LambdaQueryWrapper<LifecycleTemplate> wrapper = new LambdaQueryWrapper<LifecycleTemplate>()
                .orderByAsc(LifecycleTemplate::getTemplateType)
                .orderByDesc(LifecycleTemplate::getVersionNo);

        if (templateType != null && !templateType.isBlank()) {
            wrapper.eq(LifecycleTemplate::getTemplateType, templateType);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(LifecycleTemplate::getStatus, status);
        }

        List<LifecycleTemplate> list = templateMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> templateIds = list.stream().map(LifecycleTemplate::getId).collect(Collectors.toList());
        QueryWrapper<LifecycleTemplateTask> taskQw = new QueryWrapper<>();
        taskQw.select("template_id", "count(id) as taskCount")
              .in("template_id", templateIds)
              .groupBy("template_id");
        List<Map<String, Object>> countMaps = templateTaskMapper.selectMaps(taskQw);

        Map<Long, Integer> countMap = new HashMap<>();
        for (Map<String, Object> map : countMaps) {
            Number tId = null;
            Number tCount = null;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if ("template_id".equalsIgnoreCase(e.getKey()) || "TEMPLATE_ID".equalsIgnoreCase(e.getKey())) tId = (Number) e.getValue();
                if ("taskCount".equalsIgnoreCase(e.getKey()) || "TASKCOUNT".equalsIgnoreCase(e.getKey())) tCount = (Number) e.getValue();
            }
            if (tId != null && tCount != null) {
                countMap.put(tId.longValue(), tCount.intValue());
            }
        }

        return list.stream().map(t -> LifecycleTemplateDto.builder()
                .id(t.getId())
                .templateType(t.getTemplateType())
                .name(t.getName())
                .description(t.getDescription())
                .versionNo(t.getVersionNo())
                .status(t.getStatus())
                .validFrom(t.getValidFrom())
                .validTo(t.getValidTo())
                .taskCount(countMap.getOrDefault(t.getId(), 0))
                .build()).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LifecycleTemplateDto createTemplate(LifecycleTemplateDto dto, Long userId) {
        dagValidator.validateDtoDag(dto.getTasks());

        Integer maxVer = templateMapper.selectMaxVersionNo(dto.getTemplateType());
        int nextVer = (maxVer != null) ? maxVer + 1 : 1;

        LocalDate createValidFrom = dto.getValidFrom() != null ? dto.getValidFrom() : LocalDate.now();
        if (dto.getValidTo() != null && createValidFrom.isAfter(dto.getValidTo())) {
            throw BusinessException.of(400, "error.lifecycle.invalidDateOrder", "開始日は終了日以前である必要があります");
        }

        LifecycleTemplate entity = LifecycleTemplate.builder()
                .templateType(dto.getTemplateType())
                .name(dto.getName())
                .description(dto.getDescription())
                .versionNo(nextVer)
                .status(dto.getStatus() != null ? dto.getStatus() : "ACTIVE")
                .validFrom(createValidFrom)
                .validTo(dto.getValidTo())
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        templateMapper.insert(entity);

        saveTasksAndDeps(entity.getId(), dto.getTasks());

        return getTemplateDetail(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LifecycleTemplateDto updateTemplate(Long id, LifecycleTemplateDto dto, Long userId) {
        LifecycleTemplate existing = templateMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "error.lifecycle.templateNotFound", "テンプレートが見つかりません");
        }

        Integer maxVer = templateMapper.selectMaxVersionNo(existing.getTemplateType());
        if (maxVer != null && maxVer > existing.getVersionNo()) {
            throw BusinessException.of(400, "error.lifecycle.notLatestVersion", "最新版のテンプレートのみ改定可能です");
        }

        dagValidator.validateDtoDag(dto.getTasks());

        // 改定時は過去の進行中案件の定義を保護するため、旧バージョンを変更せず新バージョンとして保存
        int nextVer = existing.getVersionNo() + 1;

        LocalDate existingValidFrom = existing.getValidFrom();
        LocalDate newValidFrom = dto.getValidFrom() != null ? dto.getValidFrom() : LocalDate.now();

        if (!newValidFrom.isAfter(existingValidFrom)) {
            newValidFrom = LocalDate.now();
            if (!newValidFrom.isAfter(existingValidFrom)) {
                newValidFrom = existingValidFrom.plusDays(1);
            }
        }

        if (dto.getValidTo() != null && newValidFrom.isAfter(dto.getValidTo())) {
            throw BusinessException.of(400, "error.lifecycle.invalidDateOrder", "開始日は終了日以前である必要があります");
        }

        // 旧バージョンの有効終了日をクローズ
        existing.setValidTo(newValidFrom.minusDays(1));
        existing.setUpdatedBy(userId);
        templateMapper.updateById(existing);

        LifecycleTemplate newVersion = LifecycleTemplate.builder()
                .templateType(existing.getTemplateType())
                .name(dto.getName() != null ? dto.getName() : existing.getName())
                .description(dto.getDescription() != null ? dto.getDescription() : existing.getDescription())
                .versionNo(nextVer)
                .status("ACTIVE")
                .validFrom(newValidFrom)
                .validTo(dto.getValidTo())
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        templateMapper.insert(newVersion);

        saveTasksAndDeps(newVersion.getId(), dto.getTasks());

        return getTemplateDetail(newVersion.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id, String status, Long userId) {
        LifecycleTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.of(404, "error.lifecycle.templateNotFound", "テンプレートが見つかりません");
        }
        entity.setStatus(status);
        entity.setUpdatedBy(userId);
        templateMapper.updateById(entity);
    }

    private void saveTasksAndDeps(Long templateId, List<LifecycleTemplateTaskDto> taskDtos) {
        if (taskDtos == null || taskDtos.isEmpty()) {
            return;
        }

        int order = 10;
        for (LifecycleTemplateTaskDto tDto : taskDtos) {
            LifecycleTemplateTask tEntity = LifecycleTemplateTask.builder()
                    .templateId(templateId)
                    .taskCode(tDto.getTaskCode())
                    .taskName(tDto.getTaskName())
                    .description(tDto.getDescription())
                    .relativeDueDays(tDto.getRelativeDueDays() != null ? tDto.getRelativeDueDays() : 0)
                    .assigneeRule(tDto.getAssigneeRule() != null ? tDto.getAssigneeRule() : "APPLICANT")
                    .assigneeRuleValue(tDto.getAssigneeRuleValue())
                    .isMandatory(tDto.getIsMandatory() != null ? tDto.getIsMandatory() : 1)
                    .isBlocking(tDto.getIsBlocking() != null ? tDto.getIsBlocking() : 1)
                    .evidenceType(tDto.getEvidenceType() != null ? tDto.getEvidenceType() : "NONE")
                    .isEngineerVisible(tDto.getIsEngineerVisible() != null ? tDto.getIsEngineerVisible() : 1)
                    .targetEmploymentTypes(tDto.getTargetEmploymentTypes())
                    .sortOrder(tDto.getSortOrder() != null ? tDto.getSortOrder() : order)
                    .build();
            templateTaskMapper.insert(tEntity);
            order += 10;

            if (tDto.getPredecessorTaskCodes() != null) {
                for (String pred : tDto.getPredecessorTaskCodes()) {
                    LifecycleTemplateTaskDep dep = LifecycleTemplateTaskDep.builder()
                            .templateId(templateId)
                            .predecessorTaskCode(pred)
                            .successorTaskCode(tDto.getTaskCode())
                            .build();
                    templateTaskDepMapper.insert(dep);
                }
            }
        }
    }
}
