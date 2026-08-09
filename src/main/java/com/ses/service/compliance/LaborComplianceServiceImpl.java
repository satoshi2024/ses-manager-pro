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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 派遣・準委任コンプライアンス検査サービス（既存 / FR-10系）。
 * F2で既存4 rule（TIER_EXCEEDED / DIRECT_COMMAND / DOUBLE_DISPATCH / SETTLEMENT_MISMATCH）の
 * 判定ロジックを ComplianceRule 群へ分解したが、check()/findCurrentRisks()の出力はgolden fixtureどおり不変。
 * 永続化（t_compliance_finding upsert）は ComplianceRuleEngine が行う。
 */
@Service
@RequiredArgsConstructor
public class LaborComplianceServiceImpl implements LaborComplianceService {

    private static final List<String> ACTIVE_CONTRACT_STATUSES = List.of(
            com.ses.common.constant.StatusConstants.CONTRACT_ACTIVE,
            com.ses.common.constant.StatusConstants.CONTRACT_PREPARING);

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
        // 現在の「リスク一覧」はdesign §2のとおり、常時実行で算出する表示用のため、契約や勤怠の業務状態は変更しない。
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .in(Contract::getStatus, ACTIVE_CONTRACT_STATUSES));
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

    /**
     * 既存4 ruleをgolden fixtureの出力順で実行する。
     * rule有効化・message解決は各rule（AbstractComplianceRule）へ移管済みで、出力は従来と同一。
     */
    private List<ComplianceFinding> evaluate(Contract contract, int maxLayer) {
        ComplianceRuleContext context = ComplianceRuleContext.builder()
                .maxLayer(maxLayer)
                .build();
        List<ComplianceFinding> findings = new ArrayList<>();
        for (ComplianceRule rule : legacyRules()) {
            findings.addAll(rule.evaluate(contract, context));
        }
        return findings;
    }

    private List<ComplianceRule> legacyRules() {
        return List.of(
                new TierExceededRule(systemConfigService, messageSource),
                new DirectCommandRule(systemConfigService, messageSource),
                new DoubleDispatchRule(systemConfigService, messageSource),
                new SettlementMismatchRule(systemConfigService, messageSource));
    }
}
