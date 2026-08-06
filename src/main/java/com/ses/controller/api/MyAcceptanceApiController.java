package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.ContractMapper;
import com.ses.service.EngineerAccountLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 要員向け検収状態API（design §5.2: 要員は自分が対象の検収状態のみ・金額非表示）。
 */
@RestController
@RequestMapping("/api/my/acceptances")
@RequiredArgsConstructor
public class MyAcceptanceApiController {

    private final EngineerAccountLinkService linkService;
    private final ContractMapper contractMapper;
    private final AcceptanceMapper acceptanceMapper;

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        List<Contract> contracts = contractMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Contract>()
                        .eq(Contract::getEngineerId, engineerId)
                        .eq(Contract::getAcceptanceRequired, true));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Contract contract : contracts) {
            List<Acceptance> acceptances = acceptanceMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Acceptance>()
                            .eq(Acceptance::getContractId, contract.getId())
                            .orderByDesc(Acceptance::getWorkMonth));
            for (Acceptance acceptance : acceptances) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("contractId", contract.getId());
                row.put("contractNo", contract.getContractNo());
                row.put("workMonth", acceptance.getWorkMonth());
                row.put("status", acceptance.getStatus());
                row.put("submittedAt", acceptance.getSubmittedAt());
                row.put("acceptedAt", acceptance.getAcceptedAt());
                row.put("rejectComment", acceptance.getRejectComment());
                // 金額は返さない（design §5.2）
                result.add(row);
            }
        }
        return ApiResult.success(result);
    }
}
