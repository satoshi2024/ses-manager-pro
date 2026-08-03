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

    private static final java.util.Set<String> ENGINEER_STATUSES =
            java.util.Set.of("稼動中", "Bench", "提案中", "退場予定");

    private final EngineerService engineerService;
    private final com.ses.service.EngineerSalesService engineerSalesService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final com.ses.service.ProposalService proposalService;
    private final com.ses.service.RetentionRiskService retentionRiskService;
    private final com.ses.service.EngineerAccountLinkService engineerAccountLinkService;

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
            @RequestParam(required = false) Long salesUserId,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) Boolean accountLinked) {

        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ（旧 defaultSize 1000 はそのまま引き継ぐ）
        Page<Engineer> page = PageUtils.safePage(current, size, 1000L);
        if (org.springframework.util.StringUtils.hasText(status) && !ENGINEER_STATUSES.contains(status)) {
            // MySQL/H2のENUM比較差に依存せず、未知値は安全に0件へ正規化する。
            return ApiResult.success(new Page<>(page.getCurrent(), page.getSize(), 0));
        }
        java.util.Set<Long> allowedIds = effectiveEngineerIds();
        if (allowedIds != null && allowedIds.isEmpty()) {
            return ApiResult.success(new Page<>(current, size, 0));
        }
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Engineer> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (allowedIds != null) {
            queryWrapper.in(Engineer::getId, allowedIds);
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
        if (accountLinked != null) {
            // 要員セルフサービス勤怠の初期設定漏れ（＝ログインアカウント未紐付け）を探すための絞り込み。
            // engineer_id IS NOT NULL を明示するのは、NULL が1件でも混ざると NOT IN が
            // 全行 UNKNOWN になり結果が黙って空になるため。
            String subQuery = "SELECT engineer_id FROM t_engineer_account_link WHERE engineer_id IS NOT NULL";
            if (accountLinked) {
                queryWrapper.inSql(Engineer::getId, subQuery);
            } else {
                queryWrapper.notInSql(Engineer::getId, subQuery);
            }
        }

        queryWrapper.orderByDesc(Engineer::getId);

        boolean highRiskOnly = "high".equalsIgnoreCase(riskLevel);
        Page<com.ses.dto.engineer.EngineerListDto> dtoPage;
        if (highRiskOnly) {
            // 定着リスクは算出項目のためDBクエリで絞り込めない。上限(1000件)まで取得しメモリ上でフィルタ・ページングする。
            queryWrapper.last("LIMIT " + PageUtils.MAX_PAGE_SIZE);
            java.util.List<Engineer> all = engineerService.list(queryWrapper);
            java.util.List<com.ses.dto.engineer.EngineerListDto> filtered = toDtoList(all).stream()
                    .filter(dto -> Boolean.TRUE.equals(dto.getRetentionHighRisk()))
                    .collect(java.util.stream.Collectors.toList());

            long total = filtered.size();
            int fromIndex = (int) Math.min((page.getCurrent() - 1) * page.getSize(), total);
            int toIndex = (int) Math.min(fromIndex + page.getSize(), total);

            dtoPage = new Page<>(page.getCurrent(), page.getSize(), total);
            dtoPage.setRecords(filtered.subList(fromIndex, toIndex));
        } else {
            Page<Engineer> resultPage = engineerService.page(page, queryWrapper);
            dtoPage = new Page<>(resultPage.getCurrent(), resultPage.getSize(), resultPage.getTotal());
            dtoPage.setRecords(toDtoList(resultPage.getRecords()));
        }

        return ApiResult.success(dtoPage);
    }

    /** Engineer一覧→DTO変換（担当営業・定着リスクスコアを付与） */
    private java.util.List<com.ses.dto.engineer.EngineerListDto> toDtoList(java.util.List<Engineer> engineers) {
        java.util.List<com.ses.dto.engineer.EngineerListDto> dtoList = new java.util.ArrayList<>();
        if (engineers == null || engineers.isEmpty()) {
            return dtoList;
        }
        java.util.List<Long> engineerIds = engineers.stream().map(Engineer::getId).collect(java.util.stream.Collectors.toList());
        java.util.Map<Long, com.ses.dto.engineersales.EngineerPrimarySalesDto> primarySalesMap = engineerSalesService.mapPrimaryByEngineerIds(engineerIds);
        // 定着リスクは一括算出する（1件ずつ呼ぶと一覧表示のたびにN+1）。
        // ここでは要員エンティティを既に持っているので、ID版ではなく実体版を渡して
        // 同じ行の読み直し(selectBatchIds)を1クエリ分省く。
        java.util.Map<Long, com.ses.dto.engineerfollowup.RetentionRiskDto> riskMap =
                retentionRiskService.scoreBatchFor(engineers);
        // 紐付けも一括で引く（1件ずつ findByEngineerId を呼ぶとN+1）。
        java.util.Set<Long> linkedEngineerIds = engineerAccountLinkService.findLinkedEngineerIds(engineerIds);

        for (Engineer eng : engineers) {
            com.ses.dto.engineer.EngineerListDto dto = new com.ses.dto.engineer.EngineerListDto();
            org.springframework.beans.BeanUtils.copyProperties(eng, dto);

            com.ses.dto.engineersales.EngineerPrimarySalesDto primarySales = primarySalesMap.get(eng.getId());
            if (primarySales != null) {
                dto.setPrimarySalesUserId(primarySales.getSalesUserId());
                dto.setPrimarySalesUserName(primarySales.getSalesUserName());
            }

            com.ses.dto.engineerfollowup.RetentionRiskDto risk = riskMap.get(eng.getId());
            if (risk != null) {
                dto.setRetentionRiskScore(risk.getScore());
                dto.setRetentionHighRisk(risk.isHighRisk());
            }

            dto.setAccountLinked(linkedEngineerIds.contains(eng.getId()));

            dtoList.add(dto);
        }
        return dtoList;
    }

    /**
     * ドロップダウン用要員一覧（軽量化）
     */
    @GetMapping("/options")
    public ApiResult<java.util.List<com.ses.dto.common.OptionDto>> getOptions() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Engineer> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        java.util.Set<Long> allowed = effectiveEngineerIds();
        if (allowed != null) {
            if (allowed.isEmpty()) return ApiResult.success(java.util.Collections.emptyList());
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
        assertEngineerVisible(id);
        var entity = engineerService.getById(id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(entity);
    }

    /**
     * エンジニア提案履歴取得
     */
    @GetMapping("/{id}/proposal-history")
    public ApiResult<java.util.List<com.ses.dto.proposal.ProposalKanbanDto>> getProposalHistory(@PathVariable Long id) {
        assertEngineerVisible(id);
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
        assertEngineerVisible(id);
        return ApiResult.success(engineerService.updateWithStatusGuard(engineer));
    }

    /**
     * エンジニア削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        assertEngineerVisible(id);
        boolean success = engineerService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    private java.util.Set<Long> effectiveEngineerIds() {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedEngineerIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new java.util.HashSet<>(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedEngineerIds(java.time.LocalDate.now()), dataIds);
    }

    private void assertEngineerVisible(Long id) {
        java.util.Set<Long> allowed = effectiveEngineerIds();
        if (allowed != null && !allowed.contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }
}
