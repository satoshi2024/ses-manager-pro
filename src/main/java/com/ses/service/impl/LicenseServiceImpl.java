package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.LicenseAssignment;
import com.ses.entity.LicensePlan;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.mapper.LicensePlanMapper;
import com.ses.service.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseServiceImpl extends ServiceImpl<LicensePlanMapper, LicensePlan> implements LicenseService {

    private final LicensePlanMapper licensePlanMapper;
    private final LicenseAssignmentMapper licenseAssignmentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LicensePlan savePlan(LicensePlan plan, Long actorUserId) {
        if (!StringUtils.hasText(plan.getPlanCode())) {
            throw new BusinessException("プランコードは必須です。");
        }
        if (!StringUtils.hasText(plan.getPlanName())) {
            throw new BusinessException("プラン名は必須です。");
        }
        if (plan.getSeatLimit() == null || plan.getSeatLimit() <= 0) {
            throw new BusinessException("ライセンス席数上限は1以上の数値を指定してください。");
        }

        if (plan.getId() == null) {
            LicensePlan existing = getOne(new LambdaQueryWrapper<LicensePlan>()
                    .eq(LicensePlan::getPlanCode, plan.getPlanCode().trim()), false);
            if (existing != null) {
                throw new BusinessException("プランコード「" + plan.getPlanCode() + "」は既に使用されています。");
            }
            plan.setPlanCode(plan.getPlanCode().trim());
            if (plan.getAllocatedCount() == null) {
                plan.setAllocatedCount(0);
            }
            save(plan);
        } else {
            LicensePlan current = getById(plan.getId());
            if (current == null) {
                throw new BusinessException("指定されたライセンスプランが見つかりません。");
            }
            current.setPlanName(plan.getPlanName());
            current.setSystemId(plan.getSystemId());
            current.setSeatLimit(plan.getSeatLimit());
            current.setCostPerSeat(plan.getCostPerSeat());
            current.setCostCenterId(plan.getCostCenterId());
            current.setExpiryDate(plan.getExpiryDate());
            current.setStatus(plan.getStatus());
            updateById(current);
            return current;
        }
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LicenseAssignment assignLicense(Long planId,
                                           String assigneeType,
                                           Long assigneeId,
                                           Long accountReferenceId,
                                           LocalDate assignedDate,
                                           Long actorUserId) {
        if (planId == null) {
            throw new BusinessException("プランIDは必須です。");
        }
        if (!StringUtils.hasText(assigneeType) || assigneeId == null) {
            throw new BusinessException("割当先は必須です。");
        }
        if (assignedDate == null) {
            assignedDate = LocalDate.now();
        }

        // 1. プランを行ロック取得
        LicensePlan plan = licensePlanMapper.selectByIdForUpdate(planId);
        if (plan == null) {
            throw new BusinessException("指定されたライセンスプランが見つかりません。");
        }
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BusinessException("このライセンスプランは現在有効ではありません。");
        }

        // 2. 席数上限判定とCASインクリメント
        int updated = licensePlanMapper.incrementAllocatedCountWithCas(planId, plan.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "ライセンス席数が上限に達しているか、並行更新競合が発生しました。上限: " + plan.getSeatLimit());
        }

        // 3. 割当レコード登録
        LicenseAssignment assignment = LicenseAssignment.builder()
                .planId(planId)
                .assigneeType(assigneeType)
                .assigneeId(assigneeId)
                .accountReferenceId(accountReferenceId)
                .assignedDate(assignedDate)
                .releasedDate(null)
                .status("ACTIVE")
                .build();
        licenseAssignmentMapper.insert(assignment);

        log.info("License assigned successfully: planId={}, assignee={}/{}", planId, assigneeType, assigneeId);
        return assignment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LicenseAssignment releaseLicense(Long assignmentId,
                                            LocalDate releasedDate,
                                            Long actorUserId) {
        // 割当行を先にロックし、同じACTIVE割当を二つのtransactionが解放できないようにする。
        LicenseAssignment assignment = licenseAssignmentMapper.selectByIdForUpdate(assignmentId);
        if (assignment == null) {
            throw new BusinessException("指定されたライセンス割当が見つかりません。");
        }
        if ("RELEASED".equals(assignment.getStatus())) {
            return assignment;
        }

        if (releasedDate == null) {
            releasedDate = LocalDate.now();
        }

        LicensePlan plan = licensePlanMapper.selectByIdForUpdate(assignment.getPlanId());
        if (plan == null) {
            throw new BusinessException(409, "ライセンスプランが見つからないため、解放を完了できません。");
        }

        int assignmentRows = licenseAssignmentMapper.releaseWithCas(
                assignment.getId(), releasedDate, assignment.getVersion());
        if (assignmentRows != 1) {
            throw new BusinessException(409, "ライセンス割当の更新が競合しました。再読み込みして解放してください。");
        }
        int planRows = licensePlanMapper.decrementAllocatedCountWithCas(plan.getId(), plan.getVersion());
        if (planRows != 1) {
            throw new BusinessException(409, "ライセンス席数の更新が競合しました。再読み込みして解放してください。");
        }

        assignment.setStatus("RELEASED");
        assignment.setReleasedDate(releasedDate);

        log.info("License released: assignmentId={}, planId={}", assignmentId, assignment.getPlanId());
        return assignment;
    }

    @Override
    public List<LicenseAssignment> getActiveAssignmentsByAssignee(String assigneeType, Long assigneeId) {
        return licenseAssignmentMapper.selectActiveByAssignee(assigneeType, assigneeId);
    }

    @Override
    public List<LicenseAssignment> getAssignmentsByPlanId(Long planId) {
        return licenseAssignmentMapper.selectList(new LambdaQueryWrapper<LicenseAssignment>()
                .eq(LicenseAssignment::getPlanId, planId)
                .orderByDesc(LicenseAssignment::getId));
    }

    @Override
    public IPage<LicensePlan> searchPlans(int page, int size, String keyword, String status) {
        return searchPlansScoped(page, size, keyword, status, null);
    }

    @Override
    public IPage<LicensePlan> searchPlansScoped(int page, int size, String keyword, String status,
                                                List<Long> accessiblePlanIds) {
        Page<LicensePlan> pageable = new Page<>(page, size);
        LambdaQueryWrapper<LicensePlan> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(LicensePlan::getPlanCode, keyword.trim())
                    .or().like(LicensePlan::getPlanName, keyword.trim()));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(LicensePlan::getStatus, status);
        }
        if (accessiblePlanIds != null) {
            if (accessiblePlanIds.isEmpty()) {
                wrapper.eq(LicensePlan::getId, -1L);
            } else {
                wrapper.in(LicensePlan::getId, accessiblePlanIds);
            }
        }
        wrapper.orderByDesc(LicensePlan::getId);
        return page(pageable, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteAssignment(Long assignmentId) {
        LicenseAssignment assignment = licenseAssignmentMapper.selectByIdForUpdate(assignmentId);
        if (assignment == null) {
            return;
        }
        if ("RELEASED".equals(assignment.getStatus()) || assignment.getReleasedDate() != null) {
            throw new BusinessException("解放済みライセンス割当の終端履歴は論理削除できません。台帳上の履歴を保持してください。");
        }
        if ("ACTIVE".equals(assignment.getStatus()) || assignment.getReleasedDate() == null) {
            throw new BusinessException("未解放ライセンス割当は論理削除できません。先にライセンスを解放してください。");
        }
        licenseAssignmentMapper.deleteById(assignmentId);
        log.info("License assignment soft-deleted: assignmentId={}", assignmentId);
    }
}
