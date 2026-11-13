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
import com.ses.entity.SysUser;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskDepMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.mapper.SysUserMapper;
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

    /** 確認機能（CONFIRM）の公開まで新規作成・改定を受け付けない証跡種別。 */
    private static final String DUAL_CONFIRMATION = "DUAL_CONFIRMATION";

    private final LifecycleTemplateMapper templateMapper;
    private final LifecycleTemplateTaskMapper templateTaskMapper;
    private final LifecycleTemplateTaskDepMapper templateTaskDepMapper;
    private final LifecycleDagValidator dagValidator;
    private final SysUserMapper sysUserMapper;

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
        requireTemplateManager(userId);
        rejectUnavailableEvidenceTypes(dto.getTasks());
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
        requireTemplateManager(userId);
        LifecycleTemplate existing = templateMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "error.lifecycle.templateNotFound", "テンプレートが見つかりません");
        }

        Integer maxVer = templateMapper.selectMaxVersionNo(existing.getTemplateType());
        if (maxVer != null && maxVer > existing.getVersionNo()) {
            throw BusinessException.of(400, "error.lifecycle.notLatestVersion", "最新版のテンプレートのみ改定可能です");
        }

        rejectUnavailableEvidenceTypes(dto.getTasks());
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
        requireTemplateManager(userId);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw BusinessException.of(400, "error.lifecycle.invalidTemplateStatus");
        }
        LifecycleTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.of(404, "error.lifecycle.templateNotFound", "テンプレートが見つかりません");
        }
        entity.setStatus(status);
        entity.setUpdatedBy(userId);
        templateMapper.updateById(entity);
    }

    /**
     * 確認機能（CONFIRM）の公開前は、二者確認（DUAL_CONFIRMATION）タスクを持つテンプレートの新規作成・改定を拒否する
     * （決定 B-NF01-3）。DUAL_CONFIRMATIONは通常完了経路で完了できず（LifecycleTaskServiceImpl.completeTask は409）、
     * 新しい案件へ展開されると免除以外に進めない。CONFIRM公開の批次で本判定を開放する。
     * 値は前後空白・大文字小文字を畳んで照合する（小文字等で登録されると完了経路の完全一致判定をすり抜けるため）。
     * 拒否は1件も書き込む前に行う。
     */
    private void rejectUnavailableEvidenceTypes(List<LifecycleTemplateTaskDto> tasks) {
        if (tasks == null) {
            return;
        }
        for (LifecycleTemplateTaskDto task : tasks) {
            String evidenceType = task == null ? null : task.getEvidenceType();
            if (evidenceType != null && DUAL_CONFIRMATION.equalsIgnoreCase(evidenceType.trim())) {
                throw BusinessException.of(400, "error.lifecycle.dualConfirmationNotAvailable",
                        "二者確認タスクは確認機能の公開前のため作成・改定できません");
            }
        }
    }

    private void requireTemplateManager(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !("管理者".equals(user.getRole()) || "HR".equals(user.getRole()))) {
            throw BusinessException.of(403, "error.lifecycle.templateMutationDenied");
        }
    }

    private void saveTasksAndDeps(Long templateId, List<LifecycleTemplateTaskDto> taskDtos) {
        if (taskDtos == null || taskDtos.isEmpty()) {
            return;
        }
        // 呼出元（作成・改定）は書込み前に検査済み。将来の呼出元が検査を省いても保存しないよう、保存直前にも確認する。
        rejectUnavailableEvidenceTypes(taskDtos);

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
