package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * エンジニアAPIコントローラー
 */
@RestController
@RequestMapping("/api/engineers")
@RequiredArgsConstructor
public class EngineerApiController {

    private final EngineerService engineerService;
    private final com.ses.service.EngineerSalesService engineerSalesService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.ProposalService proposalService;

    /**
     * エンジニア一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<com.ses.dto.engineer.EngineerListDto>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) java.util.List<Long> skillIds,
            @RequestParam(required = false) Long salesUserId) {
        
        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ（旧 defaultSize 1000 はそのまま引き継ぐ）
        Page<Engineer> page = PageUtils.safePage(current, size, 1000L);
        // データスコープ: 営業ロール制限時は担当要員のみ。空集合なら空ページを即返す。
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedEngineerIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(new Page<>(current, size, 0));
            }
        }
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Engineer> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (dataScopeService.isScoped()) {
            queryWrapper.in(Engineer::getId, dataScopeService.allowedEngineerIds());
        }

        if (org.springframework.util.StringUtils.hasText(fullName)) {
            queryWrapper.like(Engineer::getFullName, fullName);
        }
        if (org.springframework.util.StringUtils.hasText(status)) {
            queryWrapper.eq(Engineer::getStatus, status);
        }
        if (org.springframework.util.StringUtils.hasText(employmentType)) {
            queryWrapper.eq(Engineer::getEmploymentType, employmentType);
        }
        if (skillIds != null && !skillIds.isEmpty()) {
            for (Long skillId : skillIds) {
                if (skillId == null) {
                    continue;
                }
                queryWrapper.inSql(Engineer::getId,
                    "SELECT engineer_id FROM t_engineer_skill WHERE skill_id = " + skillId);
            }
        }
        if (salesUserId != null) {
            queryWrapper.inSql(Engineer::getId,
                "SELECT engineer_id FROM t_engineer_sales WHERE sales_user_id = " + salesUserId + " AND released_at IS NULL AND deleted_flag = 0");
        }
        
        queryWrapper.orderByDesc(Engineer::getId);
        Page<Engineer> resultPage = engineerService.page(page, queryWrapper);
        
        // Convert to DTO
        Page<com.ses.dto.engineer.EngineerListDto> dtoPage = new Page<>(resultPage.getCurrent(), resultPage.getSize(), resultPage.getTotal());
        java.util.List<com.ses.dto.engineer.EngineerListDto> dtoList = new java.util.ArrayList<>();
        
        if (resultPage.getRecords() != null && !resultPage.getRecords().isEmpty()) {
            java.util.List<Long> engineerIds = resultPage.getRecords().stream().map(Engineer::getId).collect(java.util.stream.Collectors.toList());
            java.util.Map<Long, com.ses.dto.engineersales.EngineerPrimarySalesDto> primarySalesMap = engineerSalesService.mapPrimaryByEngineerIds(engineerIds);
            
            for (Engineer eng : resultPage.getRecords()) {
                com.ses.dto.engineer.EngineerListDto dto = new com.ses.dto.engineer.EngineerListDto();
                org.springframework.beans.BeanUtils.copyProperties(eng, dto);
                
                com.ses.dto.engineersales.EngineerPrimarySalesDto primarySales = primarySalesMap.get(eng.getId());
                if (primarySales != null) {
                    dto.setPrimarySalesUserId(primarySales.getSalesUserId());
                    dto.setPrimarySalesUserName(primarySales.getSalesUserName());
                }
                dtoList.add(dto);
            }
        }
        dtoPage.setRecords(dtoList);
        
        return ApiResult.success(dtoPage);
    }

    /**
     * ドロップダウン用要員一覧（軽量化）
     */
    @GetMapping("/options")
    public ApiResult<java.util.List<com.ses.dto.common.OptionDto>> getOptions() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Engineer> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedEngineerIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(java.util.Collections.emptyList());
            }
            queryWrapper.in(Engineer::getId, allowed);
        }
        queryWrapper.select(Engineer::getId, Engineer::getFullName)
                    .orderByDesc(Engineer::getId);
        java.util.List<com.ses.dto.common.OptionDto> options = engineerService.list(queryWrapper).stream()
                .map(e -> new com.ses.dto.common.OptionDto(e.getId(), e.getFullName()))
                .collect(java.util.stream.Collectors.toList());
        return ApiResult.success(options);
    }

    /**
     * エンジニア詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Engineer> getById(@PathVariable Long id) {
        if (dataScopeService.isScoped() && !dataScopeService.allowedEngineerIds().contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        var entity = engineerService.getById(id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(entity);
    }

    /**
     * エンジニア提案履歴取得
     */
    @GetMapping("/{id}/proposal-history")
    public ApiResult<java.util.List<com.ses.dto.proposal.ProposalKanbanDto>> getProposalHistory(@PathVariable Long id) {
        // 認可チェックはProposalService内で行われる
        return ApiResult.success(proposalService.getProposalHistory(id));
    }

    /**
     * エンジニア登録
     */
    @PostMapping
    public ApiResult<Engineer> save(@Valid @RequestBody com.ses.dto.engineer.EngineerSaveDto engineerDto) {
        Engineer engineer = new Engineer();
        org.springframework.beans.BeanUtils.copyProperties(engineerDto, engineer);
        com.ses.common.util.EntityProtectUtil.protectForCreate(engineer);
        engineerService.save(engineer);
        return ApiResult.success(engineer);
    }

    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody com.ses.dto.engineer.EngineerSaveDto engineerDto) {
        Engineer engineer = new Engineer();
        org.springframework.beans.BeanUtils.copyProperties(engineerDto, engineer);
        engineer.setId(id);
        dataScopeService.assertAllowedEngineer(id);
        return ApiResult.success(engineerService.updateWithStatusGuard(engineer));
    }

    /**
     * エンジニア削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        dataScopeService.assertAllowedEngineer(id);
        boolean success = engineerService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }
}
