package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.Contract;
import com.ses.service.ContractRenewalService;
import com.ses.service.ContractService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ses.dto.contract.ContractListDto;
import com.ses.mapper.ContractMapper;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.util.Map;
import com.ses.dto.contract.ContractStatusChangeRequest;

/**
 * 契約APIコントローラー
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractApiController {

    private final ContractService contractService;
    private final ContractRenewalService contractRenewalService;
    private final ContractMapper contractMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;

    /**
     * 契約一覧取得
     *
     * @return 契約リスト
     */
    @GetMapping
    public ApiResult<Page<ContractListDto>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "100") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long engineerId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String contractNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateTo,
            @RequestParam(required = false) Long salesUserId,
            @RequestParam(required = false) Boolean salesUnassigned) {
        Page<ContractListDto> page = new Page<>(current, size);
        // データスコープ: 営業ロール制限時は担当契約(自分∪未帰属)のみ。件数・ページングもスコープ後の値にするため
        // クエリレベルで IN を注入する（空集合なら空ページを即返し、IN空リストのSQLエラーを回避）。
        java.util.List<Long> allowedIds = null;
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedContractIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(new Page<>(current, size, 0));
            }
            allowedIds = new java.util.ArrayList<>(allowed);
        }
        Page<ContractListDto> result = contractMapper.selectPageWithNames(page, status, customerId, engineerId, projectId, contractNo, endDateFrom, endDateTo, salesUserId, salesUnassigned, allowedIds);
        return ApiResult.success(result);
    }

    /**
     * 契約詳細取得
     *
     * @param id 契約ID
     * @return 契約情報
     */
    private void assertContractVisible(Long id) {
        if (dataScopeService.isScoped() && !dataScopeService.allowedContractIds().contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    @GetMapping("/{id}")
    public ApiResult<Contract> getById(@PathVariable Long id) {
        assertContractVisible(id);
        var entity = contractService.getById(id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(entity);
    }

    /**
     * 契約登録
     *
     * @param contract 契約情報
     * @return 結果
     */
    @PostMapping
    public ApiResult<Boolean> create(@Valid @RequestBody com.ses.dto.contract.ContractSaveDto dto) {
        Contract contract = new Contract();
        org.springframework.beans.BeanUtils.copyProperties(dto, contract);
        contractService.saveWithBusinessRules(contract);
        if (contract.getSellingPrice() != null && contract.getCostPrice() != null 
                && contract.getSellingPrice().compareTo(contract.getCostPrice()) < 0) {
            return ApiResult.<Boolean>success("警告: 粗利がマイナスです", Boolean.TRUE);
        }
        return ApiResult.success(Boolean.TRUE);
    }

    /**
     * 契約更新
     *
     * @param id 契約ID
     * @param contract 契約情報
     * @return 結果
     */
    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody com.ses.dto.contract.ContractSaveDto dto) {
        Contract contract = new Contract();
        org.springframework.beans.BeanUtils.copyProperties(dto, contract);
        assertContractVisible(id);
        contract.setId(id);
        // 状態は専用 API の状態機械を経由させる。通常更新 payload に含まれていても無視し、
        // 準備中→稼動中などの遷移検証を迂回できないようにする。
        contract.setStatus(null);
        contractService.updateWithBusinessRules(contract);
        if (contract.getSellingPrice() != null && contract.getCostPrice() != null 
                && contract.getSellingPrice().compareTo(contract.getCostPrice()) < 0) {
            return ApiResult.<Boolean>success("警告: 粗利がマイナスです", Boolean.TRUE);
        }
        return ApiResult.success(Boolean.TRUE);
    }

    /** 契約状態変更（状態機械を通す専用 API）。 */
    @PutMapping("/{id}/status")
    public ApiResult<Boolean> changeStatus(@PathVariable Long id, @Valid @RequestBody ContractStatusChangeRequest request) {
        assertContractVisible(id);
        contractService.changeStatus(id, request.getStatus(), request.getCancelDate());
        return ApiResult.success(Boolean.TRUE);
    }

    /**
     * 稼働中契約の確認
     *
     * @param engineerId エンジニアID
     * @return 稼働中の契約があればtrue
     */
    @GetMapping("/check-active")
    public ApiResult<Boolean> checkActive(@RequestParam Long engineerId) {
        return ApiResult.success(contractService.hasActiveContract(engineerId));
    }

    /**
     * 契約削除
     *
     * @param id 契約ID
     * @return 結果
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        assertContractVisible(id);
        boolean success = contractService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    // ===== 単価改定履歴（contract-price-history / P6） =====

    @GetMapping("/{id}/price-revisions")
    public ApiResult<?> priceRevisions(@PathVariable Long id) {
        assertContractVisible(id);
        return ApiResult.success(contractService.priceHistory(id));
    }

    @PostMapping("/{id}/price-revisions")
    public ApiResult<?> revisePrice(@PathVariable Long id, @RequestBody PriceRevisionRequest req) {
        assertContractVisible(id);
        boolean warning = contractService.revisePrice(id, req.getApplyFromMonth(),
                req.getSellingPrice(), req.getCostPrice(), req.getReason());
        return ApiResult.success(java.util.Map.of("warning", warning));
    }

    @DeleteMapping("/{id}/price-revisions/{month}")
    public ApiResult<?> deletePriceRevision(@PathVariable Long id, @PathVariable String month) {
        assertContractVisible(id);
        contractService.deleteFuturePriceRevision(id, month);
        return ApiResult.success(null);
    }

    public static class PriceRevisionRequest {
        private String applyFromMonth;
        private java.math.BigDecimal sellingPrice;
        private java.math.BigDecimal costPrice;
        private String reason;
        public String getApplyFromMonth() { return applyFromMonth; }
        public void setApplyFromMonth(String v) { this.applyFromMonth = v; }
        public java.math.BigDecimal getSellingPrice() { return sellingPrice; }
        public void setSellingPrice(java.math.BigDecimal v) { this.sellingPrice = v; }
        public java.math.BigDecimal getCostPrice() { return costPrice; }
        public void setCostPrice(java.math.BigDecimal v) { this.costPrice = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { this.reason = v; }
    }

    /**
     * 自動更新ドラフトの手動生成（管理者のみ。通常は日次バッチで自動実行される）。
     *
     * @return 生成件数
     */
    @PostMapping("/generate-renewals")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Integer> generateRenewals() {
        return ApiResult.success(contractRenewalService.generateRenewalDrafts());
    }
}
