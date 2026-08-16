package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.staffing.AllocationCardDto;
import com.ses.dto.staffing.PositionBoardDto;
import com.ses.entity.AllocationPlan;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.staffing.StaffingBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static com.ses.entity.AllocationPlan.STATUS_DISCARDED;

/**
 * ポジションボード/要員タイムラインの表示用集約の実装（T077 A1）。
 * 表示名と承認状態を付与したDTOを返し、集計はserver側で行う。
 */
@Service
@RequiredArgsConstructor
public class StaffingBoardServiceImpl implements StaffingBoardService {

    private final AllocationPlanMapper allocationMapper;
    private final ProjectPositionMapper positionMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final ApprovalRequestMapper approvalRequestMapper;

    @Override
    @Transactional(readOnly = true)
    public PositionBoardDto projectBoard(Long projectId) {
        List<ProjectPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<ProjectPosition>()
                .eq(ProjectPosition::getProjectId, projectId)
                .orderByAsc(ProjectPosition::getPositionNo));
        List<AllocationPlan> allocations = positions.isEmpty()
                ? List.of()
                : allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                        .in(AllocationPlan::getPositionId,
                                positions.stream().map(ProjectPosition::getId).toList())
                        .ne(AllocationPlan::getStatus, STATUS_DISCARDED)
                        .orderByAsc(AllocationPlan::getStartDate));
        List<AllocationPlan> bench = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .isNull(AllocationPlan::getPositionId)
                .ne(AllocationPlan::getStatus, STATUS_DISCARDED)
                .orderByAsc(AllocationPlan::getStartDate));

        Map<Long, AllocationCardDto> cards = toCards(concat(allocations, bench));
        Map<Long, ProjectPosition> positionById = positions.stream()
                .collect(Collectors.toMap(ProjectPosition::getId, Function.identity(), (a, b) -> a));

        List<PositionBoardDto.PositionColumnDto> columns = new ArrayList<>();
        for (ProjectPosition position : positions) {
            List<AllocationCardDto> columnCards = allocations.stream()
                    .filter(a -> position.getId().equals(a.getPositionId()))
                    .map(a -> cards.get(a.getId()))
                    .collect(Collectors.toList());
            int filledCount = (int) allocations.stream()
                    .filter(a -> position.getId().equals(a.getPositionId()))
                    .filter(a -> a.getSourceContractId() != null && STATUS_CONFIRMED.equals(a.getStatus()))
                    .count();
            columns.add(new PositionBoardDto.PositionColumnDto(position, filledCount, columnCards));
        }
        List<AllocationCardDto> benchCards = bench.stream()
                .map(a -> cards.get(a.getId()))
                .collect(Collectors.toList());
        Project project = projectMapper.selectById(projectId);
        return new PositionBoardDto(projectId,
                project == null ? null : project.getProjectName(), columns, benchCards);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllocationCardDto> engineerTimeline(Long engineerId) {
        List<AllocationPlan> allocations = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, engineerId)
                .ne(AllocationPlan::getStatus, STATUS_DISCARDED)
                .orderByAsc(AllocationPlan::getStartDate)
                .orderByDesc(AllocationPlan::getUpdatedAt));
        Map<Long, AllocationCardDto> cards = toCards(allocations);
        return allocations.stream()
                .map(a -> cards.get(a.getId()))
                .filter(card -> card != null)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AllocationCardDto card(Long allocationId) {
        AllocationPlan plan = allocationMapper.selectById(allocationId);
        if (plan == null) {
            throw BusinessException.of(404, "error.staffing.allocationNotFound");
        }
        return toCards(List.of(plan)).get(plan.getId());
    }

    // ---------------------------------------------------------------

    /** 配置一覧を表示用DTOへ変換（要員名・ポジション/案件名・承認状態を解決）。 */
    private Map<Long, AllocationCardDto> toCards(List<AllocationPlan> allocations) {
        if (allocations.isEmpty()) {
            return Map.of();
        }
        Set<Long> engineerIds = allocations.stream()
                .map(AllocationPlan::getEngineerId).collect(Collectors.toSet());
        Set<Long> positionIds = allocations.stream()
                .map(AllocationPlan::getPositionId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> approvalIds = allocations.stream()
                .map(AllocationPlan::getApprovalRequestId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        Map<Long, Engineer> engineers = engineerIds.isEmpty() ? Map.of()
                : engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId, Function.identity(), (a, b) -> a));
        Map<Long, ProjectPosition> positions = positionIds.isEmpty() ? Map.of()
                : positionMapper.selectBatchIds(positionIds).stream()
                .collect(Collectors.toMap(ProjectPosition::getId, Function.identity(), (a, b) -> a));
        Set<Long> projectIds = positions.values().stream()
                .map(ProjectPosition::getProjectId).collect(Collectors.toSet());
        Map<Long, Project> projects = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a));
        Map<Long, ApprovalRequest> approvals = approvalIds.isEmpty() ? Map.of()
                : approvalRequestMapper.selectBatchIds(approvalIds).stream()
                .collect(Collectors.toMap(ApprovalRequest::getId, Function.identity(), (a, b) -> a));

        return allocations.stream().collect(Collectors.toMap(AllocationPlan::getId, plan -> {
            AllocationCardDto dto = new AllocationCardDto(plan);
            Engineer engineer = engineers.get(plan.getEngineerId());
            dto.setEngineerName(engineer == null ? null : engineer.getFullName());
            ProjectPosition position = plan.getPositionId() == null ? null : positions.get(plan.getPositionId());
            if (position != null) {
                dto.setPositionNo(position.getPositionNo());
                dto.setRoleName(position.getRoleName());
                dto.setProjectId(position.getProjectId());
                Project project = projects.get(position.getProjectId());
                dto.setProjectName(project == null ? null : project.getProjectName());
            }
            if (plan.getApprovalRequestId() != null) {
                ApprovalRequest approval = approvals.get(plan.getApprovalRequestId());
                dto.setApprovalStatus(approval == null ? null : approval.getStatus());
            }
            return dto;
        }, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private List<AllocationPlan> concat(List<AllocationPlan> a, List<AllocationPlan> b) {
        List<AllocationPlan> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
