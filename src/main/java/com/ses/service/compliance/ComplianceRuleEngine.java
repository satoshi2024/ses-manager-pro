package com.ses.service.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.compliance.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.DocumentDelivery;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractComplianceProfileMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.DocumentDeliveryMapper;
import com.ses.mapper.WorkRecordDailyMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.WorkplaceMapper;
import com.ses.service.SystemConfigService;
import com.ses.entity.Workplace;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * F2 rule engine。全rule（既存4＋新rule）を実行し、t_compliance_findingへupsertする。
 * rule実行はread-only＋finding upsertのみで、契約や勤怠の業務状態を変更しない（design §2）。
 */
@Service
public class ComplianceRuleEngine {

    private static final List<String> ACTIVE_CONTRACT_STATUSES = List.of(
            StatusConstants.CONTRACT_ACTIVE, StatusConstants.CONTRACT_PREPARING);

    private final ContractMapper contractMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final ContractComplianceProfileMapper profileMapper;
    private final DocumentDeliveryMapper deliveryMapper;
    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordDailyMapper workRecordDailyMapper;
    private final WorkplaceMapper workplaceMapper;
    private final ComplianceFindingStore findingStore;
    private final SystemConfigService systemConfigService;
    private final MessageSource messageSource;
    private final LimitationDateCalculator limitationDateCalculator;

    public ComplianceRuleEngine(ContractMapper contractMapper, BpPaymentMapper bpPaymentMapper,
                                ContractComplianceProfileMapper profileMapper,
                                DocumentDeliveryMapper deliveryMapper,
                                WorkRecordMapper workRecordMapper, WorkRecordDailyMapper workRecordDailyMapper,
                                WorkplaceMapper workplaceMapper,
                                ComplianceFindingStore findingStore,
                                SystemConfigService systemConfigService, MessageSource messageSource,
                                LimitationDateCalculator limitationDateCalculator) {
        this.contractMapper = contractMapper;
        this.bpPaymentMapper = bpPaymentMapper;
        this.profileMapper = profileMapper;
        this.deliveryMapper = deliveryMapper;
        this.workRecordMapper = workRecordMapper;
        this.workRecordDailyMapper = workRecordDailyMapper;
        this.workplaceMapper = workplaceMapper;
        this.findingStore = findingStore;
        this.systemConfigService = systemConfigService;
        this.messageSource = messageSource;
        this.limitationDateCalculator = limitationDateCalculator;
    }

    /** 実行結果の集計（Demo: 2回実行でopenedが増えない）。 */
    public record RunResult(int contractsEvaluated, int opened, int resolved, int kept) {
    }

    /** 全active契約に対してruleを実行し、findingをupsertする。 */
    @Transactional
    public RunResult runActiveContracts() {
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .in(Contract::getStatus, ACTIVE_CONTRACT_STATUSES)
                .select(Contract::getId, Contract::getEngineerId, Contract::getContractType,
                        Contract::getStartDate, Contract::getEndDate, Contract::getCustomerId,
                        Contract::getDirectCommandFlag, Contract::getSettlementHoursMin,
                        Contract::getSettlementHoursMax));
        int opened = 0;
        int resolved = 0;
        int kept = 0;
        for (Contract contract : contracts) {
            ComplianceFindingStore.SyncResult result = runForContract(contract);
            opened += result.opened();
            resolved += result.resolved();
            kept += result.kept();
        }
        return new RunResult(contracts.size(), opened, resolved, kept);
    }

    /** 指定契約に対してruleを実行し、findingをupsertする。 */
    @Transactional
    public ComplianceFindingStore.SyncResult runForContract(Long contractId) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            return new ComplianceFindingStore.SyncResult(0, 0, 0);
        }
        return runForContract(contract);
    }

    private ComplianceFindingStore.SyncResult runForContract(Contract contract) {
        List<ComplianceFinding> evaluated = evaluate(contract);
        return findingStore.sync(contract.getId(), evaluated);
    }

    /** 対象契約のrule評価（read-only）。既存4 ruleはLaborComplianceServiceと同じ順序・出力。 */
    public List<ComplianceFinding> evaluate(Contract contract) {
        if (contract == null || contract.getId() == null) {
            return List.of();
        }
        ComplianceRuleContext context = buildContext(contract);
        List<ComplianceRule> rules = rules();
        List<ComplianceFinding> findings = new ArrayList<>();
        for (ComplianceRule rule : rules) {
            findings.addAll(rule.evaluate(contract, context));
        }
        return findings;
    }

    private ComplianceRuleContext buildContext(Contract contract) {
        Integer maxLayer = bpPaymentMapper.selectMaxLayerOrderByContractId(contract.getId());
        ContractComplianceProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<ContractComplianceProfile>()
                        .eq(ContractComplianceProfile::getContractId, contract.getId()));
        String organizationUnit = null;
        if (profile != null && profile.getWorkplaceId() != null) {
            Workplace workplace = workplaceMapper.selectById(profile.getWorkplaceId());
            organizationUnit = workplace != null ? workplace.getOrganizationUnit() : null;
        }
        List<DocumentDelivery> deliveries = deliveryMapper.selectList(
                new LambdaQueryWrapper<DocumentDelivery>()
                        .eq(DocumentDelivery::getContractId, contract.getId()));
        List<WorkRecordDaily> dailies = new ArrayList<>();
        if (contract.getEngineerId() != null) {
            List<WorkRecord> records = workRecordMapper.selectList(
                    new LambdaQueryWrapper<WorkRecord>()
                            .eq(WorkRecord::getContractId, contract.getId()));
            if (!records.isEmpty()) {
                List<Long> recordIds = records.stream().map(WorkRecord::getId).toList();
                dailies = workRecordDailyMapper.selectList(
                        new LambdaQueryWrapper<WorkRecordDaily>()
                                .in(WorkRecordDaily::getWorkRecordId, recordIds));
            }
        }
        List<LimitationDateCalculator.ChainContract> chain = List.of();
        if (contract.getEngineerId() != null) {
            List<Contract> engineerContracts = contractMapper.selectList(
                    new LambdaQueryWrapper<Contract>()
                            .eq(Contract::getEngineerId, contract.getEngineerId())
                            .select(Contract::getId, Contract::getStartDate, Contract::getEndDate,
                                    Contract::getCustomerId));
            Map<Long, ContractComplianceProfile> profilesByContract = engineerContracts.isEmpty() ? Map.of()
                    : profileMapper.selectList(new LambdaQueryWrapper<ContractComplianceProfile>()
                            .in(ContractComplianceProfile::getContractId,
                                    engineerContracts.stream().map(Contract::getId).toList())).stream()
                    .collect(Collectors.toMap(ContractComplianceProfile::getContractId, p -> p, (a, b) -> a));
            chain = engineerContracts.stream().map(c -> {
                ContractComplianceProfile p = profilesByContract.get(c.getId());
                String org = null;
                if (p != null && p.getWorkplaceId() != null) {
                    Workplace workplace = workplaceMapper.selectById(p.getWorkplaceId());
                    org = workplace != null ? workplace.getOrganizationUnit() : null;
                }
                return new LimitationDateCalculator.ChainContract(
                        c.getId(), c.getStartDate(), c.getEndDate(),
                        c.getCustomerId(), p != null ? p.getWorkplaceId() : null, org);
            }).toList();
        }
        return ComplianceRuleContext.builder()
                .maxLayer(maxLayer != null ? maxLayer : 0)
                .profile(profile)
                .deliveries(deliveries)
                .workRecordDailies(dailies)
                .contractChain(chain)
                .organizationUnit(organizationUnit)
                .build();
    }

    /** rule群（既存4 ruleを先頭に、golden fixtureの出力順を維持する）。 */
    public List<ComplianceRule> rules() {
        return List.of(
                new TierExceededRule(systemConfigService, messageSource),
                new DirectCommandRule(systemConfigService, messageSource),
                new DoubleDispatchRule(systemConfigService, messageSource),
                new SettlementMismatchRule(systemConfigService, messageSource),
                new MissingLimitationDateRule(systemConfigService, messageSource, limitationDateCalculator),
                new MissingResponsibleRule(systemConfigService, messageSource),
                new MissingInsuranceRule(systemConfigService, messageSource),
                new MissingDocumentDeliveryRule(systemConfigService, messageSource),
                new MissingInstructionRouteRule(systemConfigService, messageSource),
                new WorkOutsidePeriodRule(systemConfigService, messageSource),
                new DeliveryDeadlineRule(systemConfigService, messageSource));
    }
}
