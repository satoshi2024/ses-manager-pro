package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
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
        
        Page<Engineer> page = new Page<>(current, size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Engineer> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        
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
     * エンジニア詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Engineer> getById(@PathVariable Long id) {
        return ApiResult.success(engineerService.getById(id));
    }

    /**
     * エンジニア登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@Valid @RequestBody Engineer engineer) {
        return ApiResult.success(engineerService.save(engineer));
    }

    /**
     * エンジニア更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@Valid @RequestBody Engineer engineer) {
        return ApiResult.success(engineerService.updateWithStatusGuard(engineer));
    }

    /**
     * エンジニア削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(engineerService.removeById(id));
    }
}
