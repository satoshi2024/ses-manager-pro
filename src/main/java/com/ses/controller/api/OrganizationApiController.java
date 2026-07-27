package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.EntityProtectUtil;
import com.ses.dto.organization.CostCenterSaveRequest;
import com.ses.dto.organization.OrganizationSaveRequest;
import com.ses.dto.organization.OrganizationMergeRequest;
import com.ses.dto.organization.UserOrganizationSaveRequest;
import com.ses.entity.CostCenter;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.CostCenterService;
import com.ses.service.OrganizationService;
import com.ses.service.security.OrganizationScopeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 組織・所属・原価部門API。全一覧・詳細・更新は組織scopeをDBクエリ境界で適用する。 */
@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationApiController {

    private final OrganizationService organizationService;
    private final OrganizationScopeService organizationScopeService;
    private final CostCenterService costCenterService;
    private final UserOrganizationMapper userOrganizationMapper;

    @GetMapping
    public ApiResult<List<OrganizationUnit>> list(
            @RequestParam(required = false) Long legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResult.success(organizationScopeService.listVisibleOrganizations(legalEntityId, asOf));
    }

    @GetMapping("/{id}")
    public ApiResult<OrganizationUnit> get(@PathVariable Long id,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        organizationScopeService.assertAllowedOrganization(id, asOf);
        OrganizationUnit result = organizationService.getById(id);
        if (result == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return ApiResult.success(result);
    }

    @PostMapping
    public ApiResult<OrganizationUnit> create(@Valid @RequestBody OrganizationSaveRequest request) {
        OrganizationUnit unit = toEntity(request);
        assertParentScope(unit.getParentId());
        EntityProtectUtil.protectForCreate(unit);
        if (!organizationService.save(unit)) {
            throw BusinessException.of("error.organization.saveFailed");
        }
        return ApiResult.success(unit);
    }

    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id,
                                     @Valid @RequestBody OrganizationSaveRequest request) {
        organizationScopeService.assertAllowedOrganization(id);
        OrganizationUnit unit = toEntity(request);
        unit.setId(id);
        assertParentScope(unit.getParentId());
        EntityProtectUtil.protectForUpdate(unit);
        if (request.version() == null) {
            throw BusinessException.of(400, "error.organization.versionRequired");
        }
        organizationService.reorganize(id, unit, request.version());
        return ApiResult.success(true);
    }

    @PostMapping("/{id}/merge")
    public ApiResult<Boolean> merge(@PathVariable Long id,
                                    @Valid @RequestBody OrganizationMergeRequest request) {
        organizationScopeService.assertAllowedOrganization(id);
        organizationScopeService.assertAllowedOrganization(request.targetOrganizationId());
        return ApiResult.success(organizationService.merge(id, request.targetOrganizationId(), request.version()));
    }

    @PutMapping("/{id}/status")
    public ApiResult<Boolean> status(@PathVariable Long id, @RequestParam String status) {
        organizationScopeService.assertAllowedOrganization(id);
        if (!"有効".equals(status) && !"無効".equals(status)) {
            throw BusinessException.of("error.organization.invalid");
        }
        OrganizationUnit unit = organizationService.getById(id);
        if (unit == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        unit.setStatus(status);
        if (!organizationService.updateById(unit)) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return ApiResult.success(true);
    }

    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        organizationScopeService.assertAllowedOrganization(id);
        if (!organizationService.removeById(id)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return ApiResult.success(true);
    }

    @GetMapping("/{userId}/assignments")
    public ApiResult<List<UserOrganization>> assignments(@PathVariable Long userId,
                                                         @RequestParam(required = false)
                                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        LambdaQueryWrapper<UserOrganization> query = new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, userId)
                .le(UserOrganization::getValidFrom, date)
                .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, date));
        organizationScopeService.applyOrganizationScope(query, UserOrganization::getOrganizationId, date);
        return ApiResult.success(userOrganizationMapper.selectList(query));
    }

    @PostMapping("/{userId}/assignments")
    public ApiResult<UserOrganization> assign(@PathVariable Long userId,
                                              @Valid @RequestBody UserOrganizationSaveRequest request) {
        if (!organizationScopeService.hasFullAccess()) {
            organizationScopeService.assertAllowedUser(userId, request.validFrom());
        }
        organizationScopeService.assertAllowedOrganization(request.organizationId());
        UserOrganization assignment = UserOrganization.builder()
                .userId(userId)
                .organizationId(request.organizationId())
                .positionName(request.positionName())
                .managerUserId(request.managerUserId())
                .primaryFlag(request.primaryFlag() == null ? 0 : request.primaryFlag())
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .build();
        EntityProtectUtil.protectForCreate(assignment);
        return ApiResult.success(organizationService.assignUser(assignment));
    }

    @PostMapping("/{userId}/transfer")
    public ApiResult<UserOrganization> transfer(@PathVariable Long userId,
                                                @Valid @RequestBody UserOrganizationSaveRequest request) {
        if (!organizationScopeService.hasFullAccess()) {
            organizationScopeService.assertAllowedUser(userId, request.validFrom());
        }
        organizationScopeService.assertAllowedOrganization(request.organizationId());
        UserOrganization assignment = UserOrganization.builder()
                .userId(userId).organizationId(request.organizationId()).positionName(request.positionName())
                .managerUserId(request.managerUserId()).primaryFlag(request.primaryFlag() == null ? 0 : request.primaryFlag())
                .validFrom(request.validFrom()).validTo(request.validTo()).version(0).build();
        EntityProtectUtil.protectForCreate(assignment);
        return ApiResult.success(organizationService.transferUser(assignment, null));
    }

    @GetMapping("/cost-centers")
    public ApiResult<List<CostCenter>> costCenters(
            @RequestParam(required = false) Long legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        LambdaQueryWrapper<CostCenter> query = new LambdaQueryWrapper<CostCenter>()
                .eq(legalEntityId != null, CostCenter::getLegalEntityId, legalEntityId)
                .le(CostCenter::getValidFrom, date)
                .and(w -> w.isNull(CostCenter::getValidTo).or().ge(CostCenter::getValidTo, date))
                .eq(CostCenter::getStatus, "有効")
                .orderByAsc(CostCenter::getCode);
        organizationScopeService.applyOrganizationScope(query, CostCenter::getOrganizationId, date);
        return ApiResult.success(costCenterService.list(query));
    }

    @PostMapping("/cost-centers")
    public ApiResult<CostCenter> createCostCenter(@Valid @RequestBody CostCenterSaveRequest request) {
        assertOrganizationScope(request.organizationId());
        CostCenter center = toCostCenter(request);
        EntityProtectUtil.protectForCreate(center);
        if (!costCenterService.save(center)) {
            throw BusinessException.of("error.organization.saveFailed");
        }
        return ApiResult.success(center);
    }

    @PutMapping("/cost-centers/{id}")
    public ApiResult<Boolean> updateCostCenter(@PathVariable Long id,
                                               @Valid @RequestBody CostCenterSaveRequest request) {
        CostCenter existing = costCenterService.getById(id);
        if (existing == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        assertOrganizationScope(existing.getOrganizationId());
        assertOrganizationScope(request.organizationId());
        CostCenter center = toCostCenter(request);
        center.setId(id);
        EntityProtectUtil.protectForUpdate(center);
        if (!costCenterService.updateById(center)) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        return ApiResult.success(true);
    }

    @DeleteMapping("/cost-centers/{id}")
    public ApiResult<Boolean> deleteCostCenter(@PathVariable Long id) {
        CostCenter existing = costCenterService.getById(id);
        if (existing == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        assertOrganizationScope(existing.getOrganizationId());
        if (!costCenterService.removeById(id)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return ApiResult.success(true);
    }

    private void assertParentScope(Long parentId) {
        if (parentId != null) {
            organizationScopeService.assertAllowedOrganization(parentId);
        }
    }

    private void assertOrganizationScope(Long organizationId) {
        if (organizationId != null) {
            organizationScopeService.assertAllowedOrganization(organizationId);
        } else if (!organizationScopeService.hasFullAccess()) {
            throw BusinessException.of("error.organization.scope.notAllowed");
        }
    }

    private OrganizationUnit toEntity(OrganizationSaveRequest request) {
        OrganizationUnit unit = OrganizationUnit.builder()
                .legalEntityId(request.legalEntityId())
                .code(request.code())
                .name(request.name())
                .type(request.type())
                .parentId(request.parentId())
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .status(request.status() == null ? "有効" : request.status())
                .version(request.version() == null ? 0 : request.version())
                .build();
        return unit;
    }

    private CostCenter toCostCenter(CostCenterSaveRequest request) {
        return CostCenter.builder()
                .legalEntityId(request.legalEntityId())
                .code(request.code())
                .name(request.name())
                .organizationId(request.organizationId())
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .status(request.status() == null ? "有効" : request.status())
                .version(request.version() == null ? 0 : request.version())
                .build();
    }
}
