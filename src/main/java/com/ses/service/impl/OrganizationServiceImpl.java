package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CostCenter;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 組織階層・所属の業務ルール実装。 */
@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl extends ServiceImpl<OrganizationUnitMapper, OrganizationUnit>
        implements OrganizationService {

    private final UserOrganizationMapper userOrganizationMapper;
    private final CostCenterMapper costCenterMapper;
    private final ManagementBudgetMapper managementBudgetMapper;
    private final MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;
    private final SysUserMapper sysUserMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    /** 要員の所属組織を扱う。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.EngineerMapper engineerMapper;

    @Override
    public boolean save(OrganizationUnit entity) {
        validateOrganization(entity, null);
        return super.save(entity);
    }

    @Override
    public boolean updateById(OrganizationUnit entity) {
        validateOrganization(entity, entity.getId());
        return super.updateById(entity);
    }

    @Override
    @Transactional
    public boolean updateOrganization(OrganizationUnit entity, Integer expectedVersion) {
        if (entity == null || entity.getId() == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        OrganizationUnit current = getById(entity.getId());
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        entity.setTenantId(current.getTenantId());
        validateOrganization(entity, entity.getId());
        entity.setVersion(expectedVersion);
        // OptimisticLockerInnerInterceptor が version を検査し、成功時に +1 する。
        // 同じIDを更新するので、所属・原価部門・予算・snapshotの参照は一切壊れない。
        if (baseMapper.updateById(entity) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean merge(Long organizationId, Long targetOrganizationId, Integer expectedVersion) {
        if (organizationId == null || targetOrganizationId == null || organizationId.equals(targetOrganizationId)) {
            throw BusinessException.of("error.organization.invalid");
        }
        OrganizationUnit current = getById(organizationId);
        OrganizationUnit target = getById(targetOrganizationId);
        if (current == null || target == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        if (!Objects.equals(current.getLegalEntityId(), target.getLegalEntityId())) {
            throw BusinessException.of("error.organization.legalEntityMismatch");
        }
        if (expectedVersion == null || !Objects.equals(expectedVersion, current.getVersion())) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        // 統合先が統合元の子孫だと、親子関係の付け替えで循環する。
        if (descendantIds(organizationId, LocalDate.now()).contains(targetOrganizationId)) {
            throw BusinessException.of("error.organization.cycle");
        }
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        // 子組織を統合先へ付け替える。
        for (OrganizationUnit child : list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getParentId, organizationId))) {
            child.setParentId(targetOrganizationId);
            if (baseMapper.updateById(child) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
        }
        // 有効な所属は統合日で旧行を閉じ、統合先の新しい履歴行を作る。
        // 旧行のorganization_idを直接書き換えると、統合日前のas-of照会まで改変される。
        for (UserOrganization assignment : userOrganizationMapper.selectByUserOrganizationForUpdate(organizationId)) {
            if (assignment.getValidFrom() != null && assignment.getValidFrom().isBefore(today)) {
                assignment.setValidTo(today.minusDays(1));
                if (userOrganizationMapper.updateById(assignment) != 1) {
                    throw BusinessException.of(409, "error.organization.versionConflict");
                }
                UserOrganization targetExisting = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId()).stream()
                        .filter(existing -> Objects.equals(existing.getOrganizationId(), targetOrganizationId)
                                && overlaps(existing.getValidFrom(), existing.getValidTo(), today, null))
                        .findFirst().orElse(null);
                if (targetExisting != null) {
                    // 既存の統合先所属がある場合は同日重複行を作らず、主所属だけ必要なら昇格する。
                    if (isPrimary(assignment) && !isPrimary(targetExisting)) {
                        targetExisting.setPrimaryFlag(1);
                        if (userOrganizationMapper.updateById(targetExisting) != 1) {
                            throw BusinessException.of(409, "error.organization.versionConflict");
                        }
                    }
                    continue;
                }
                UserOrganization successor = new UserOrganization();
                successor.setUserId(assignment.getUserId());
                successor.setOrganizationId(targetOrganizationId);
                successor.setPositionName(assignment.getPositionName());
                successor.setManagerUserId(assignment.getManagerUserId());
                successor.setPrimaryFlag(assignment.getPrimaryFlag());
                successor.setValidFrom(today);
                successor.setValidTo(null);
                successor.setVersion(0);
                if (userOrganizationMapper.insert(successor) != 1) {
                    throw BusinessException.of("error.organization.assignment.saveFailed");
                }
            } else {
                // まだ開始していない未来行は歴史を持たないため統合先へ移す。
                assignment.setOrganizationId(targetOrganizationId);
                if (userOrganizationMapper.updateById(assignment) != 1) {
                    throw BusinessException.of(409, "error.organization.versionConflict");
                }
            }
        }
        // 要員の所属組織も付け替える。
        if (engineerMapper != null) {
            engineerMapper.reassignOrganization(organizationId, targetOrganizationId);
        }
        // 原価部門は組織にぶら下がるマスタなので全件付け替える。
        for (CostCenter center : costCenterMapper.selectList(new LambdaQueryWrapper<CostCenter>()
                .eq(CostCenter::getOrganizationId, organizationId))) {
            center.setOrganizationId(targetOrganizationId);
            if (costCenterMapper.updateById(center) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
        }
        // 予算は当月以降だけ移す。過去月の予実は確定済みの比較対象なので動かさない(R2.2)。
        for (ManagementBudget budget : managementBudgetMapper.selectList(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getOrganizationId, organizationId)
                .ge(ManagementBudget::getBudgetMonth, monthStart))) {
            boolean duplicated = managementBudgetMapper.selectCount(new LambdaQueryWrapper<ManagementBudget>()
                    .eq(ManagementBudget::getOrganizationId, targetOrganizationId)
                    .eq(ManagementBudget::getBudgetMonth, budget.getBudgetMonth())
                    .eq(budget.getCostCenterId() != null, ManagementBudget::getCostCenterId, budget.getCostCenterId())
                    .isNull(budget.getCostCenterId() == null, ManagementBudget::getCostCenterId)) > 0;
            if (duplicated) {
                throw BusinessException.of("error.organization.merge.budgetConflict");
            }
            budget.setOrganizationId(targetOrganizationId);
            if (managementBudgetMapper.updateById(budget) != 1) {
                throw BusinessException.of(409, "error.organization.budget.conflict");
            }
        }
        // 月次snapshotは動かさない。過去の帰属を書き換えないのがR2.2の要件そのもの。
        current.setMergedInto(targetOrganizationId);
        current.setStatus("無効");
        current.setVersion(expectedVersion);
        if (baseMapper.updateById(current) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return true;
    }

    @Override
    public List<OrganizationUnit> listTree(Long legalEntityId, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(legalEntityId != null, OrganizationUnit::getLegalEntityId, legalEntityId)
                .le(OrganizationUnit::getValidFrom, date)
                .and(w -> w.isNull(OrganizationUnit::getValidTo)
                        .or().ge(OrganizationUnit::getValidTo, date))
                .eq(OrganizationUnit::getStatus, "有効")
                .orderByAsc(OrganizationUnit::getParentId)
                .orderByAsc(OrganizationUnit::getCode));
    }

    @Override
    public List<Long> descendantIds(Long organizationId, LocalDate asOf) {
        if (organizationId == null) {
            return List.of();
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        List<OrganizationUnit> units = list(new LambdaQueryWrapper<OrganizationUnit>()
                .le(OrganizationUnit::getValidFrom, date)
                .and(w -> w.isNull(OrganizationUnit::getValidTo)
                        .or().ge(OrganizationUnit::getValidTo, date))
                .eq(OrganizationUnit::getStatus, "有効"));
        Set<Long> result = new HashSet<>();
        result.add(organizationId);
        boolean changed;
        do {
            changed = false;
            for (OrganizationUnit unit : units) {
                if (unit.getId() != null && unit.getParentId() != null
                        && result.contains(unit.getParentId())) {
                    changed |= result.add(unit.getId());
                }
            }
        } while (changed);
        return new ArrayList<>(result);
    }

    @Override
    @Transactional
    public boolean deactivate(Long organizationId) {
        OrganizationUnit unit = getById(organizationId);
        return unit != null && updateStatus(organizationId, "無効", unit.getVersion());
    }

    @Override
    @Transactional
    public boolean updateStatus(Long organizationId, String status, Integer expectedVersion) {
        if (organizationId == null || expectedVersion == null
                || (!"有効".equals(status) && !"無効".equals(status))) {
            throw BusinessException.of(400, "error.organization.invalid");
        }
        OrganizationUnit current = baseMapper.selectByIdForUpdate(organizationId);
        if (current == null) {
            current = getById(organizationId);
        }
        if (current == null) {
            return false;
        }
        if ("無効".equals(status)) {
            if (count(new LambdaQueryWrapper<OrganizationUnit>()
                    .eq(OrganizationUnit::getParentId, organizationId)
                    .eq(OrganizationUnit::getStatus, "有効")) > 0) {
                throw BusinessException.of("error.organization.deactivate.hasChildren");
            }
            if (userOrganizationMapper.selectCount(new LambdaQueryWrapper<UserOrganization>()
                    .eq(UserOrganization::getOrganizationId, organizationId)
                    .isNull(UserOrganization::getValidTo)) > 0) {
                throw BusinessException.of("error.organization.deactivate.hasMembers");
            }
        }
        OrganizationUnit patch = new OrganizationUnit();
        patch.setId(organizationId);
        patch.setStatus(status);
        patch.setVersion(expectedVersion);
        if (baseMapper.updateById(patch) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return true;
    }

    @Override
    public boolean isReferenced(Long organizationId) {
        if (organizationId == null) {
            return false;
        }
        return count(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getParentId, organizationId)) > 0
                || userOrganizationMapper.selectCount(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getOrganizationId, organizationId)) > 0
                || costCenterMapper.selectCount(new LambdaQueryWrapper<CostCenter>()
                .eq(CostCenter::getOrganizationId, organizationId)) > 0
                || managementBudgetMapper.selectCount(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getOrganizationId, organizationId)) > 0
                || monthlyAccountingDimensionMapper.selectCount(
                new LambdaQueryWrapper<MonthlyAccountingDimension>()
                        .eq(MonthlyAccountingDimension::getOrganizationId, organizationId)) > 0
                || (engineerMapper != null && engineerMapper.selectCount(
                new LambdaQueryWrapper<com.ses.entity.Engineer>()
                        .eq(com.ses.entity.Engineer::getOrganizationId, organizationId)) > 0);
    }

    @Override
    @Transactional
    public boolean removeById(Serializable id) {
        Long organizationId = Long.valueOf(id.toString());
        if (isReferenced(organizationId)) {
            throw BusinessException.of("error.organization.delete.referenced");
        }
        return super.removeById(id);
    }

    @Override
    @Transactional
    public UserOrganization assignUser(UserOrganization assignment) {
        if (sysUserMapper.selectByIdForUpdate(assignment.getUserId()) == null) {
            throw BusinessException.of("error.organization.assignment.userNotFound");
        }
        List<UserOrganization> locked = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId());
        OrganizationUnit lockedOrganization = baseMapper.selectByIdForUpdate(assignment.getOrganizationId());
        if (lockedOrganization == null) {
            throw BusinessException.of("error.organization.assignment.organizationNotFound");
        }
        validateAssignment(assignment, null);
        // validateAssignment は通常の一覧を使うが、先に同一ユーザーの行をロックして
        // primary/期間の二重登録を同時実行で通さない。
        if (locked.stream().anyMatch(item -> overlaps(item.getValidFrom(), item.getValidTo(),
                assignment.getValidFrom(), assignment.getValidTo())
                && (Objects.equals(item.getOrganizationId(), assignment.getOrganizationId())
                || isPrimary(item) && isPrimary(assignment)))) {
            throw BusinessException.of(isPrimary(assignment)
                    ? "error.organization.assignment.primaryOverlap"
                    : "error.organization.assignment.periodOverlap");
        }
        if (userOrganizationMapper.insert(assignment) != 1) {
            throw BusinessException.of("error.organization.assignment.saveFailed");
        }
        return assignment;
    }

    @Override
    @Transactional
    public boolean updateUserOrganization(UserOrganization assignment, Integer expectedVersion) {
        if (assignment == null || assignment.getId() == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        UserOrganization current = userOrganizationMapper.selectById(assignment.getId());
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        assignment.setUserId(current.getUserId());
        validateAssignment(assignment, assignment.getId());
        assignment.setVersion(expectedVersion);
        if (userOrganizationMapper.updateById(assignment) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return true;
    }

    @Override
    @Transactional
    public UserOrganization transferUser(UserOrganization assignment, Integer expectedVersion) {
        if (sysUserMapper.selectByIdForUpdate(assignment.getUserId()) == null) {
            throw BusinessException.of("error.organization.assignment.userNotFound");
        }
        List<UserOrganization> locked = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId());
        validateAssignmentBasics(assignment);
        LocalDate transferDate = assignment.getValidFrom();
        // 画面が読んだ「異動元の主所属」が古くなっていないかを検査する。
        // ここを見ないと、他の担当者が先に異動させた後の古いフォームでも異動が通ってしまう。
        UserOrganization currentPrimary = locked.stream()
                .filter(item -> isPrimary(item) && item.getValidTo() == null)
                .findFirst().orElse(null);
        if (currentPrimary != null
                && !transferDate.isAfter(currentPrimary.getValidFrom())) {
            throw BusinessException.of("error.organization.assignment.periodOverlap");
        }
        if (currentPrimary != null
                && (expectedVersion == null || !Objects.equals(expectedVersion, currentPrimary.getVersion()))) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        for (UserOrganization old : locked) {
            if (!overlaps(old.getValidFrom(), old.getValidTo(), transferDate, transferDate)) {
                continue;
            }
            if (Objects.equals(old.getOrganizationId(), assignment.getOrganizationId())) {
                throw BusinessException.of("error.organization.assignment.periodOverlap");
            }
            old.setValidTo(transferDate.minusDays(1));
            if (old.getValidTo().isBefore(old.getValidFrom())) {
                old.setValidTo(old.getValidFrom());
            }
            if (userOrganizationMapper.updateById(old) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
        }
        if (userOrganizationMapper.insert(assignment) != 1) {
            throw BusinessException.of("error.organization.assignment.saveFailed");
        }
        return assignment;
    }

    @Override
    @Transactional
    public boolean releaseAssignment(Long assignmentId, LocalDate releaseDate, Integer expectedVersion) {
        if (assignmentId == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        UserOrganization current = userOrganizationMapper.selectById(assignmentId);
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        LocalDate date = releaseDate == null ? LocalDate.now() : releaseDate;
        if (date.isBefore(current.getValidFrom())) {
            date = current.getValidFrom();
        }
        UserOrganization patch = new UserOrganization();
        patch.setId(assignmentId);
        patch.setValidTo(date);
        patch.setVersion(expectedVersion);
        if (userOrganizationMapper.updateById(patch) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return true;
    }

    @Override
    @Transactional
    public int closeAssignmentsForUser(Long userId, LocalDate releaseDate) {
        if (userId == null) {
            return 0;
        }
        LocalDate date = releaseDate == null ? LocalDate.now() : releaseDate;
        int closed = 0;
        for (UserOrganization assignment : userOrganizationMapper.selectByUserForUpdate(userId)) {
            if (assignment.getValidTo() != null) {
                continue;
            }
            LocalDate validTo = date.isBefore(assignment.getValidFrom()) ? assignment.getValidFrom() : date;
            UserOrganization patch = new UserOrganization();
            patch.setId(assignment.getId());
            patch.setValidTo(validTo);
            patch.setVersion(assignment.getVersion());
            // CAS失敗を黙って捨てると、退職・無効化が「成功」で返るのに所属が開いたまま残り、
            // 退職者が組織scope・部門損益に居座る。トランザクションごと失敗させて再実行させる。
            if (userOrganizationMapper.updateById(patch) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
            closed++;
        }
        // 上長として登録されている行も外す。退職者が上長のまま残ると承認者不在になる。
        userOrganizationMapper.clearManager(userId);
        return closed;
    }

    @Override
    public java.util.Map<Long, String> namesByIds(java.util.Collection<Long> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> ids = organizationIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (OrganizationUnit unit : listByIds(ids)) {
            names.put(unit.getId(), unit.getName());
        }
        return names;
    }

    @Override
    public List<UserOrganization> listUserOrganizations(Long userId, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, userId)
                .le(UserOrganization::getValidFrom, date)
                .and(w -> w.isNull(UserOrganization::getValidTo)
                        .or().ge(UserOrganization::getValidTo, date))
                .orderByDesc(UserOrganization::getPrimaryFlag)
                .orderByAsc(UserOrganization::getValidFrom));
    }

    private void validateOrganization(OrganizationUnit candidate, Long excludedId) {
        if (candidate == null || candidate.getValidFrom() == null
                || candidate.getCode() == null || candidate.getCode().isBlank()
                || candidate.getName() == null || candidate.getName().isBlank()
                || candidate.getType() == null || candidate.getType().isBlank()) {
            throw BusinessException.of("error.organization.invalid");
        }
        validateDateRange(candidate.getValidFrom(), candidate.getValidTo());
        OrganizationUnit current = excludedId == null ? null : getById(excludedId);
        if (current != null && isReferenced(excludedId)
                && (!Objects.equals(current.getValidFrom(), candidate.getValidFrom())
                || !Objects.equals(current.getValidTo(), candidate.getValidTo())
                || !Objects.equals(current.getParentId(), candidate.getParentId()))) {
            throw BusinessException.of("error.organization.referencedPeriodImmutable");
        }
        if (current != null && !Objects.equals(current.getLegalEntityId(), candidate.getLegalEntityId())
                && isReferenced(excludedId)) {
            throw BusinessException.of("error.organization.legalEntityChangeReferenced");
        }
        if (candidate.getParentId() != null) {
            OrganizationUnit parent = getById(candidate.getParentId());
            if (parent == null) {
                throw BusinessException.of("error.organization.parentNotFound");
            }
            if (!Objects.equals(parent.getLegalEntityId(), candidate.getLegalEntityId())) {
                throw BusinessException.of("error.organization.legalEntityMismatch");
            }
            if (Objects.equals(candidate.getId(), candidate.getParentId())
                    || Objects.equals(excludedId, candidate.getParentId())
                    || createsCycle(candidate.getParentId(), candidate.getId(), new HashSet<>())) {
                throw BusinessException.of("error.organization.cycle");
            }
        }
        List<OrganizationUnit> sameCode = list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getCode, candidate.getCode())
                .eq(candidate.getLegalEntityId() != null,
                        OrganizationUnit::getLegalEntityId, candidate.getLegalEntityId())
                .isNull(candidate.getLegalEntityId() == null, OrganizationUnit::getLegalEntityId));
        if (sameCode.stream().anyMatch(existing -> !Objects.equals(existing.getId(), excludedId)
                && overlaps(existing.getValidFrom(), existing.getValidTo(),
                candidate.getValidFrom(), candidate.getValidTo()))) {
            throw BusinessException.of("error.organization.periodOverlap");
        }
    }

    private boolean createsCycle(Long parentId, Long childId, Set<Long> visited) {
        if (parentId == null || Objects.equals(parentId, childId)) {
            return parentId != null;
        }
        if (!visited.add(parentId)) {
            return true;
        }
        OrganizationUnit parent = getById(parentId);
        return parent != null && createsCycle(parent.getParentId(), childId, visited);
    }

    private void validateAssignment(UserOrganization candidate, Long excludedId) {
        validateAssignmentBasics(candidate);
        List<UserOrganization> existing = userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>().eq(UserOrganization::getUserId, candidate.getUserId()));
        boolean overlap = existing.stream().anyMatch(item -> !Objects.equals(item.getId(), excludedId)
                && overlaps(item.getValidFrom(), item.getValidTo(), candidate.getValidFrom(), candidate.getValidTo())
                && (Objects.equals(item.getOrganizationId(), candidate.getOrganizationId())
                || isPrimary(item) && isPrimary(candidate)));
        if (overlap) {
            throw BusinessException.of(isPrimary(candidate)
                    ? "error.organization.assignment.primaryOverlap"
                    : "error.organization.assignment.periodOverlap");
        }
    }

    private void validateAssignmentBasics(UserOrganization candidate) {
        if (candidate == null || candidate.getUserId() == null || candidate.getOrganizationId() == null
                || candidate.getValidFrom() == null) {
            throw BusinessException.of("error.organization.assignment.invalid");
        }
        validateDateRange(candidate.getValidFrom(), candidate.getValidTo());
        if (Objects.equals(candidate.getUserId(), candidate.getManagerUserId())) {
            throw BusinessException.of("error.organization.assignment.managerSelf");
        }
        OrganizationUnit organization = baseMapper.selectByIdForUpdate(candidate.getOrganizationId());
        if (organization == null) {
            organization = getById(candidate.getOrganizationId());
        }
        if (organization == null) {
            throw BusinessException.of("error.organization.assignment.organizationNotFound");
        }
        if (!"有効".equals(organization.getStatus())
                || organization.getValidFrom().isAfter(candidate.getValidFrom())
                || (organization.getValidTo() != null && organization.getValidTo().isBefore(candidate.getValidFrom()))) {
            throw BusinessException.of("error.organization.assignment.organizationInactive");
        }
        if (candidate.getManagerUserId() != null) {
            SysUser manager = sysUserMapper.selectById(candidate.getManagerUserId());
            if (manager == null || !Integer.valueOf(1).equals(manager.getStatus())
                    || manager.getRole() == null || manager.getRole().isBlank()
                    || "要員".equals(manager.getRole())) {
                throw BusinessException.of("error.organization.assignment.managerNotQualified");
            }
            LocalDate asOf = candidate.getValidFrom();
            if (organizationScopeService != null && !organizationScopeService.hasFullAccess()
                    && !organizationScopeService.isAllowedUser(manager.getId(), asOf)) {
                throw BusinessException.of("error.organization.assignment.managerOutOfScope");
            }
        }
    }

    private boolean isPrimary(UserOrganization assignment) {
        return Integer.valueOf(1).equals(assignment.getPrimaryFlag());
    }

    private boolean overlaps(LocalDate from1, LocalDate to1, LocalDate from2, LocalDate to2) {
        LocalDate max1 = to1 == null ? LocalDate.MAX : to1;
        LocalDate max2 = to2 == null ? LocalDate.MAX : to2;
        return !from1.isAfter(max2) && !from2.isAfter(max1);
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw BusinessException.of("error.organization.invalidPeriod");
        }
    }
}
