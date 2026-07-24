package com.ses.service.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.compliance.ComplianceFinding;
import com.ses.dto.compliance.ContractComplianceDto;
import com.ses.dto.compliance.ContractTierDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 偽装請負・多重派遣リスクチェック実装（FR-10）。
 * findings は都度導出のみで永続化しない（新規テーブル不要。design.md 参照）。
 */
@Service
@RequiredArgsConstructor
public class LaborComplianceServiceImpl implements LaborComplianceService {

    private static final String SEVERITY_WARNING = "warning";
    private static final List<String> DIRECT_COMMAND_CONTRACT_TYPES = List.of("準委任", "請負");

    private final ContractMapper contractMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final SystemConfigService systemConfigService;
    private final MessageSource messageSource;

    @Override
    public List<ComplianceFinding> check(Contract contract) {
        if (contract == null || contract.getId() == null) {
            return List.of();
        }
        Integer maxLayer = bpPaymentMapper.selectMaxLayerOrderByContractId(contract.getId());
        return evaluate(contract, maxLayer != null ? maxLayer : 0);
    }

    @Override
    public List<ContractComplianceDto> findCurrentRisks() {
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<>());
        if (contracts.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> tierByContract = bpPaymentMapper.selectMaxLayerOrderGroupedByContract().stream()
                .collect(Collectors.toMap(ContractTierDto::getContractId, ContractTierDto::getMaxLayer, (a, b) -> a));

        List<Long> engineerIds = contracts.stream().map(Contract::getEngineerId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> projectIds = contracts.stream().map(Contract::getProjectId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> engineerNames = engineerIds.isEmpty() ? Map.of() : engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId, Engineer::getFullName, (a, b) -> a));
        Map<Long, String> projectNames = projectIds.isEmpty() ? Map.of() : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Project::getProjectName, (a, b) -> a));

        List<ContractComplianceDto> result = new ArrayList<>();
        for (Contract c : contracts) {
            List<ComplianceFinding> findings = evaluate(c, tierByContract.getOrDefault(c.getId(), 0));
            if (findings.isEmpty()) {
                continue;
            }
            ContractComplianceDto dto = new ContractComplianceDto();
            dto.setContractId(c.getId());
            dto.setContractNo(c.getContractNo());
            dto.setEngineerName(engineerNames.get(c.getEngineerId()));
            dto.setProjectName(projectNames.get(c.getProjectId()));
            dto.setContractType(c.getContractType());
            dto.setFindings(findings);
            result.add(dto);
        }
        return result;
    }

    private boolean ruleEnabled(String key) {
        return "true".equalsIgnoreCase(systemConfigService.getString(key, "true"));
    }

    private List<ComplianceFinding> evaluate(Contract contract, int maxLayer) {
        List<ComplianceFinding> findings = new ArrayList<>();
        Locale locale = LocaleContextHolder.getLocale();
        String contractType = contract.getContractType();

        if (ruleEnabled("compliance.rule.tier-exceeded.enabled")) {
            int maxTier = systemConfigService.getInt("compliance.max-tier", 3);
            if (maxLayer > maxTier) {
                findings.add(finding("TIER_EXCEEDED", contract.getId(), locale, maxLayer, maxTier));
            }
        }

        if (ruleEnabled("compliance.rule.direct-command.enabled")
                && DIRECT_COMMAND_CONTRACT_TYPES.contains(contractType)
                && Boolean.TRUE.equals(contract.getDirectCommandFlag())) {
            findings.add(finding("DIRECT_COMMAND", contract.getId(), locale, contractType));
        }

        if (ruleEnabled("compliance.rule.double-dispatch.enabled")
                && "派遣".equals(contractType) && maxLayer > 1) {
            findings.add(finding("DOUBLE_DISPATCH", contract.getId(), locale, maxLayer));
        }

        if (ruleEnabled("compliance.rule.actual-mismatch.enabled")
                && "請負".equals(contractType)
                && (contract.getSettlementHoursMin() != null || contract.getSettlementHoursMax() != null)) {
            findings.add(finding("SETTLEMENT_MISMATCH", contract.getId(), locale));
        }

        return findings;
    }

    private static final Map<String, String> MESSAGE_KEYS = new HashMap<>();
    static {
        MESSAGE_KEYS.put("TIER_EXCEEDED", "compliance.finding.tierExceeded");
        MESSAGE_KEYS.put("DIRECT_COMMAND", "compliance.finding.directCommand");
        MESSAGE_KEYS.put("DOUBLE_DISPATCH", "compliance.finding.doubleDispatch");
        MESSAGE_KEYS.put("SETTLEMENT_MISMATCH", "compliance.finding.settlementMismatch");
    }

    private ComplianceFinding finding(String code, Long contractId, Locale locale, Object... args) {
        String message = messageSource.getMessage(MESSAGE_KEYS.get(code), args, code, locale);
        return new ComplianceFinding(code, SEVERITY_WARNING, message, contractId);
    }
}
