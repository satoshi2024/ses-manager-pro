package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CostCenter;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.OrganizationUnit;
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
    public OrganizationUnit reorganize(Long organizationId, OrganizationUnit replacement, Integer expectedVersion) {
        OrganizationUnit current = getById(organizationId);
        if (current == null || expectedVersion == null || !Objects.equals(expectedVersion, current.getVersion())) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        replacement.setId(null);
        replacement.setTenantId(current.getTenantId());
        if (replacement.getLegalEntityId() == null) {
            replacement.setLegalEntityId(current.getLegalEntityId());
        }
        validateOrganization(replacement, organizationId);
        current.setValidTo(replacement.getValidFrom().isAfter(current.getValidFrom())
                ? replacement.getValidFrom().minusDays(1) : current.getValidFrom());
        current.setStatus("無効");
        current.setVersion(expectedVersion);
        if (baseMapper.updateById(current) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        replacement.setVersion(0);
        if (!super.save(replacement)) {
            throw BusinessException.of("error.organization.saveFailed");
        }
        return replacement;
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
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (expectedVersion == null || !Objects.equals(expectedVersion, current.getVersion())) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        current.setMergedInto(targetOrganizationId);
        current.setStatus("無効");
        current.setVersion(expectedVersion);
        return baseMapper.updateById(current) == 1;
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
        if (unit == null) {
            return false;
        }
        unit.setStatus("無効");
        return updateById(unit);
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
                        .eq(MonthlyAccountingDimension::getOrganizationId, organizationId)) > 0;
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
    public boolean updateUserOrganization(UserOrganization assignment) {
        if (assignment == null || assignment.getVersion() == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        validateAssignment(assignment, assignment.getId());
        return userOrganizationMapper.updateById(assignment) == 1;
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
        if (candidate.getParentId() != null) {
            if (Objects.equals(candidate.getId(), candidate.getParentId())
                    || Objects.equals(excludedId, candidate.getParentId())
                    || createsCycle(candidate.getParentId(), candidate.getId(), new HashSet<>())) {
                throw BusinessException.of("error.organization.cycle");
            }
            if (getById(candidate.getParentId()) == null) {
                throw BusinessException.of("error.organization.parentNotFound");
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
        if (getById(candidate.getOrganizationId()) == null) {
            throw BusinessException.of("error.organization.assignment.organizationNotFound");
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
