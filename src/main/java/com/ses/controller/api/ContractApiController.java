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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateTo) {
        Page<ContractListDto> page = new Page<>(current, size);
        return ApiResult.success(contractMapper.selectPageWithNames(page, status, customerId, engineerId, projectId, contractNo, endDateFrom, endDateTo));
    }

    /**
     * 契約詳細取得
     *
     * @param id 契約ID
     * @return 契約情報
     */
    @GetMapping("/{id}")
    public ApiResult<Contract> getById(@PathVariable Long id) {
        return ApiResult.success(contractService.getById(id));
    }

    /**
     * 契約登録
     *
     * @param contract 契約情報
     * @return 結果
     */
    @PostMapping
    public ApiResult<Boolean> create(@Valid @RequestBody Contract contract) {
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
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody Contract contract) {
        contract.setId(id);
        contractService.updateWithBusinessRules(contract);
        if (contract.getSellingPrice() != null && contract.getCostPrice() != null 
                && contract.getSellingPrice().compareTo(contract.getCostPrice()) < 0) {
            return ApiResult.<Boolean>success("警告: 粗利がマイナスです", Boolean.TRUE);
        }
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
        return ApiResult.success(contractService.removeById(id));
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
