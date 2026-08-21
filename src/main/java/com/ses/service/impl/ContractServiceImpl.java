package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.ContractService;
import com.ses.service.EngineerStatusService;
import com.ses.service.security.ScopeChangeInvalidator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import com.ses.entity.WorkRecord;

/**
 * 契約サービス実装
 */
@Service
@RequiredArgsConstructor
public class ContractServiceImpl extends ServiceImpl<ContractMapper, Contract> implements ContractService {

    // 状態遷移の唯一の権威。フロントの STATUS_TRANSITIONS(contract.js)はこの複製であり、変更時は両方追随すること。
    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            "準備中", Set.of("稼動中", "解約"),
            "稼動中", Set.of("終了", "解約"),
            "終了", Set.of(),
            "解約", Set.of());

    private final EngineerStatusService engineerStatusService;
    private final WorkRecordMapper workRecordMapper;
    private final ProjectMapper projectMapper;
    private final com.ses.mapper.ProjectPositionMapper positionMapper;
    private final com.ses.service.EngineerSalesService engineerSalesService;
    private final com.ses.mapper.ContractPriceHistoryMapper priceHistoryMapper;
    private final com.ses.service.compliance.LaborComplianceService laborComplianceService;
    private final com.ses.service.AuditLogService auditLogService;
    private final com.ses.service.BpComplianceService bpComplianceService;
    private final com.ses.service.EngineerBpAffiliationService engineerBpAffiliationService;
    private final com.ses.service.staffing.StaffingContractSyncService staffingSync;

    /** DataScope invalidation。既存テストスライス（手動構築）互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScopeChangeInvalidator scopeChangeInvalidator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.ses.service.ai.AiOutcomeService> aiOutcomeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Contract target = this.getById(id);
        if (target == null) return false;
        if ("稼動中".equals(target.getStatus())) {
            throw BusinessException.of("error.contract.activeDelete");
        }
        Long contractId = Long.valueOf(id.toString());
        long workRecords = workRecordMapper.selectCount(new LambdaQueryWrapper<WorkRecord>().eq(WorkRecord::getContractId, contractId));
        if (workRecords > 0) {
            throw BusinessException.of("error.contract.hasWorkRecord");
        }
        boolean removed = super.removeById(id);
        // 契約削除後、要員が稼働中契約を持たなくなった場合は Bench に戻す
        // （releaseIfIdle は承認済み・稼働中以外の契約も参照するため安全に呼べる。実際の判定はメソッド内）。
        if (removed && target.getEngineerId() != null) {
            engineerStatusService.releaseIfIdle(target.getEngineerId());
        }
        // staffing-capacity-planning: actual allocationを破棄する
        staffingSync.removeActual(contractId);
        return removed;
    }

    @Override
    public String generateContractNo(LocalDate baseDate) {
        String prefix = "C-" + baseDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        String maxNo = this.baseMapper.selectMaxContractNoIncludingDeleted(prefix);
        if (maxNo == null) {
            return prefix + "0001";
        }
        String seqStr = maxNo.substring(prefix.length());
        int nextSeq = Integer.parseInt(seqStr) + 1;
        return prefix + String.format("%04d", nextSeq);
    }

    private void validate(Contract c) {
        validate(c, null);
    }

    /**
     * 業務検証。old が渡された更新経路では、担当営業(salesUserId)が変更されていない場合に限り
     * 在職チェックを免除する(帰属は成約時点の事実であり、退職後も保持する仕様。
     * `engineer-sales-commission` R3-2 と整合)。
     */
    private void validate(Contract c, Contract old) {
        // 検収不要理由（R3.3: false時は理由必須、trueへ戻す場合は理由をクリア。R09-P1-01対応）
        if (Boolean.FALSE.equals(c.getAcceptanceRequired())) {
            if (!org.springframework.util.StringUtils.hasText(c.getAcceptanceExemptionReason())) {
                throw BusinessException.of("error.contract.acceptanceExemptionReasonRequired");
            }
        } else if (Boolean.TRUE.equals(c.getAcceptanceRequired())) {
            c.setAcceptanceExemptionReason(null);
        }
        if (c.getEndDate() != null && c.getStartDate() != null && c.getEndDate().isBefore(c.getStartDate())) {
            throw BusinessException.of("error.contract.endDateInvalid");
        }
        if (c.getSettlementHoursMax() != null && c.getSettlementHoursMin() != null
                && c.getSettlementHoursMax().compareTo(c.getSettlementHoursMin()) < 0) {
            throw BusinessException.of(400, "error.contract.unitPriceInvalid");
        }
        if (c.getSettlementHoursMin() != null && c.getSettlementHoursMin().compareTo(BigDecimal.ZERO) < 0) {
            throw BusinessException.of(400, "error.contract.settlementHoursInvalid");
        }
        if (c.getCommissionRate() != null && (c.getCommissionRate().compareTo(BigDecimal.ZERO) < 0 || c.getCommissionRate().compareTo(new BigDecimal("100")) > 0)) {
            throw BusinessException.of(400, "error.contract.commissionRateInvalid");
        }

        if (c.getProjectId() != null) {
            Project project = projectMapper.selectById(c.getProjectId());
            if (project == null) {
                throw BusinessException.of("error.contract.projectNotFound");
            }
            if (!Objects.equals(project.getCustomerId(), c.getCustomerId())) {
                throw BusinessException.of("error.contract.projectCustomerMismatch");
            }
        }

        // staffing-capacity-planning: ポジション紐付けは案件配下の実在ポジションに限定する
        if (c.getPositionId() != null) {
            com.ses.entity.ProjectPosition position = positionMapper.selectById(c.getPositionId());
            if (position == null) {
                throw BusinessException.of(404, "error.staffing.positionNotFound");
            }
            if (c.getProjectId() != null && !Objects.equals(position.getProjectId(), c.getProjectId())) {
                throw BusinessException.of(400, "error.staffing.positionProjectMismatch");
            }
        }

        boolean salesUserUnchanged = old != null && Objects.equals(old.getSalesUserId(), c.getSalesUserId());
        if (c.getSalesUserId() != null && !salesUserUnchanged) {
            // 在職判定は EngineerSalesService.isActiveSalesUser に一本化（二重定義を避ける）。
            if (!engineerSalesService.isActiveSalesUser(c.getSalesUserId())) {
                throw BusinessException.of("error.contract.salesUserInvalid");
            }
        }

        if (Boolean.FALSE.equals(c.getAcceptanceRequired())) {
            if (c.getAcceptanceExemptionReason() == null || c.getAcceptanceExemptionReason().isBlank()) {
                throw BusinessException.of(400, "error.contract.exemptionReasonRequired");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<com.ses.dto.compliance.ComplianceFinding> saveWithBusinessRules(Contract contract) {
        validate(contract);
        if (!StringUtils.hasText(contract.getContractType())) {
            contract.setContractType("準委任");
        }
        contract.setStatus("準備中");

        if (contract.getContractNo() == null || contract.getContractNo().isEmpty()) {
            LocalDate baseDate = contract.getStartDate() != null ? contract.getStartDate() : LocalDate.now();

            boolean success = false;
            for (int i = 0; i < 3; i++) {
                String no = generateContractNo(baseDate);
                contract.setContractNo(no);
                try {
                    this.baseMapper.insert(contract);
                    success = true;
                    break;
                } catch (DuplicateKeyException e) {
                    // 注文明細の一意制約競合は採番競合として再試行しない。
                    // 先行txのcommit後に勝者を可視化し、呼出元で同一契約を返す。
                    if (contract.getOrderLineId() != null) {
                        Contract winner = this.baseMapper.selectOne(new LambdaQueryWrapper<Contract>()
                                .eq(Contract::getOrderLineId, contract.getOrderLineId())
                                .last("LIMIT 1 FOR UPDATE"));
                        if (winner != null) {
                            throw e;
                        }
                    }
                    // 契約番号だけの競合なら、新しい番号で再試行する。
                    contract.setId(null);
                }
            }
            if (!success) {
                throw BusinessException.of("error.contract.numberGenerateFailed");
            }
        } else {
            this.baseMapper.insert(contract);
        }

        if ("稼動中".equals(contract.getStatus())) {
            engineerStatusService.onContractActive(contract.getEngineerId());
        }
        // 新規契約の担当営業もDataScopeの母集団を変える（第十四次Review P1-3）。
        if (contract.getSalesUserId() != null) {
            invalidateScope();
        }

        // staffing-capacity-planning: 契約作成（準備中）をactual allocationへ同期する
        staffingSync.syncActual(contract.getId());

        return checkComplianceAndRecord(contract, "POST");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<com.ses.dto.compliance.ComplianceFinding> updateWithBusinessRules(Contract contract) {
        // 画面保存 DTO が運ぶ ALWAYS 列は「出現済み」とみなし明示 null でクリア可。
        // DTO に無い positionId / renewalDecision は未出現として old から回填する（CON-01）。
        return updateWithBusinessRules(contract, com.ses.dto.contract.ContractSaveDto.SAVE_PAYLOAD_ALWAYS_FIELDS);
    }

    /**
     * ALWAYS 列の部分更新安全版。{@code presentAlwaysFields} に含まれない ALWAYS フィールドは
     * 行ロック後の {@code old} から回填し、{@code updateById} による NULL 上書きを防ぐ。
     * payload で明示 null（クリア）したい列だけを {@code presentAlwaysFields} に含めること。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<com.ses.dto.compliance.ComplianceFinding> updateWithBusinessRules(
            Contract contract, Set<String> presentAlwaysFields) {
        // 行ロックで単価同期/改定と直列化する（R3R-29）。
        Contract old = this.baseMapper.selectByIdForUpdate(contract.getId());
        if (old == null) {
            throw BusinessException.of("error.contract.notFound");
        }

        restoreAbsentAlwaysFields(contract, old, presentAlwaysFields);

        java.util.List<com.ses.entity.ContractPriceHistory> histories = priceHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.ContractPriceHistory>()
                        .eq("contract_id", contract.getId()));
        // 改定履歴がある契約は単価を「単価改定」経由に一本化する。通常更新SQLからは単価列を除外し
        // （null化＝update-strategy:not_null によりUPDATE対象外）、同期後の新単価を旧値へ戻さない。
        if (!histories.isEmpty()) {
            contract.setSellingPrice(null);
            contract.setCostPrice(null);
        }

        validate(contract, old);
        this.baseMapper.updateById(contract);

        Long oldEngineerId = old.getEngineerId();
        Long newEngineerId = contract.getEngineerId() != null ? contract.getEngineerId() : oldEngineerId;
        String newStatus = contract.getStatus() != null ? contract.getStatus() : old.getStatus();
        boolean engineerChanged = !Objects.equals(oldEngineerId, newEngineerId);

        // 契約更新後に再計算し、releaseIfIdle が更新済みの関連を参照できるようにする。
        if (engineerChanged && oldEngineerId != null) {
            engineerStatusService.releaseIfIdle(oldEngineerId);
        }
        if ("稼動中".equals(newStatus)) {
            // エンジニアの所属BP会社を解決 (BP案件・発注の場合のみコンプライアンス評価を実行)
            Long bpCompanyId = null;
            if (newEngineerId != null && engineerBpAffiliationService != null) {
                com.ses.entity.EngineerBpAffiliation affiliation =
                        engineerBpAffiliationService.getActiveAffiliationAsOf(newEngineerId, LocalDate.now());
                if (affiliation != null) {
                    bpCompanyId = affiliation.getBpCompanyId();
                }
            }
            // 発注コンプライアンス必須明示項目等の判定 (ERRORがあれば確定拒否)
            if (bpComplianceService != null && bpCompanyId != null) {
                List<com.ses.dto.compliance.ProcurementComplianceFinding> findings =
                        bpComplianceService.evaluateContractCompliance(bpCompanyId, contract, null);
                boolean hasError = findings.stream().anyMatch(f -> "ERROR".equalsIgnoreCase(f.getSeverity()));
                if (hasError) {
                    String errorMsg = findings.stream()
                            .filter(f -> "ERROR".equalsIgnoreCase(f.getSeverity()))
                            .map(com.ses.dto.compliance.ProcurementComplianceFinding::getMessage)
                            .reduce((a, b) -> a + "; " + b).orElse("必須明示事項が不足しています");
                    throw new BusinessException(400, "発注コンプライアンス不合格のため確定できません: " + errorMsg);
                }
            }
            if (newEngineerId != null) {
                engineerStatusService.onContractActive(newEngineerId);
            }
        } else if (!engineerChanged && "稼動中".equals(old.getStatus()) && newEngineerId != null) {
            engineerStatusService.releaseIfIdle(newEngineerId);
        }

        // 担当営業(salesUserId)の変更はDataScope（担当契約/担当顧客の母集団）を変える。
        // 進めないと、変更直後もDashboardキャッシュのTTLが切れるまで旧担当のscopeで集計される
        // （第十四次Review P1-3）。
        if (!Objects.equals(old.getSalesUserId(), contract.getSalesUserId())) {
            invalidateScope();
        }

        // staffing-capacity-planning: 契約のperiod/position/status変化をactual allocationへ同期する
        staffingSync.syncActual(contract.getId());

        return checkComplianceAndRecord(contract, "PUT");
    }

    private void invalidateScope() {
        if (scopeChangeInvalidator != null) {
            scopeChangeInvalidator.invalidate();
        }
    }

    /**
     * payload に未出現の ALWAYS フィールドを行ロック後の旧値で回填する（CON-01）。
     * {@code presentAlwaysFields} に含まれる列は明示指定（null クリア含む）として触らない。
     */
    private void restoreAbsentAlwaysFields(Contract incoming, Contract old, Set<String> presentAlwaysFields) {
        Set<String> present = presentAlwaysFields != null ? presentAlwaysFields : Set.of();
        if (!present.contains("positionId")) {
            incoming.setPositionId(old.getPositionId());
        }
        if (!present.contains("endDate")) {
            incoming.setEndDate(old.getEndDate());
        }
        if (!present.contains("settlementHoursMin")) {
            incoming.setSettlementHoursMin(old.getSettlementHoursMin());
        }
        if (!present.contains("settlementHoursMax")) {
            incoming.setSettlementHoursMax(old.getSettlementHoursMax());
        }
        if (!present.contains("fractionRule")) {
            incoming.setFractionRule(old.getFractionRule());
        }
        if (!present.contains("salesUserId")) {
            incoming.setSalesUserId(old.getSalesUserId());
        }
        if (!present.contains("commissionBaseType")) {
            incoming.setCommissionBaseType(old.getCommissionBaseType());
        }
        if (!present.contains("commissionRate")) {
            incoming.setCommissionRate(old.getCommissionRate());
        }
        if (!present.contains("acceptanceExemptionReason")) {
            incoming.setAcceptanceExemptionReason(old.getAcceptanceExemptionReason());
        }
        if (!present.contains("renewalDecision")) {
            incoming.setRenewalDecision(old.getRenewalDecision());
        }
    }

    /**
     * 労務コンプライアンスリスクチェック（LaborComplianceService）を実行し、該当があれば
     * 既存の t_audit_log に記録する（新規テーブル不要。design.md 2章）。ブロックはしない。
     */
    private List<com.ses.dto.compliance.ComplianceFinding> checkComplianceAndRecord(Contract contract, String httpMethod) {
        List<com.ses.dto.compliance.ComplianceFinding> findings = laborComplianceService.check(contract);
        if (!findings.isEmpty()) {
            String codes = findings.stream()
                    .map(com.ses.dto.compliance.ComplianceFinding::getCode)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(","));
            String applicationCode = "compliance:" + codes;
            if (applicationCode.length() > 64) {
                applicationCode = applicationCode.substring(0, 64);
            }
            auditLogService.record(com.ses.common.util.SecurityUtils.currentUsername(), httpMethod,
                    "/api/contracts/" + contract.getId(), 200, applicationCode, false);
        }
        return findings;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long contractId, String newStatus, LocalDate cancelDate) {
        Contract contract = this.baseMapper.selectByIdForUpdate(contractId);
        if (contract == null) {
            throw BusinessException.of(404, "error.contract.notFound");
        }
        if (newStatus == null || !ALLOWED_STATUS_TRANSITIONS
                .getOrDefault(contract.getStatus(), Set.of()).contains(newStatus)) {
            throw BusinessException.of(409, "error.contract.statusTransitionInvalid",
                    contract.getStatus(), newStatus);
        }
        String oldStatus = contract.getStatus();
        LocalDate originalEndDate = contract.getEndDate();
        // 解約遷移では解約日(実質終了日)を必須とし、end_date を上書きする。
        // 解約日以降の月は集計対象から自然に外れる(R2/R3)。
        if (StatusConstants.CONTRACT_CANCELLED.equals(newStatus)) {
            if (cancelDate == null) {
                throw BusinessException.of("error.contract.cancelDateRequired");
            }
            if (contract.getStartDate() != null && cancelDate.isBefore(contract.getStartDate())) {
                throw BusinessException.of("error.contract.cancelDateInvalid");
            }
            // 解約は前倒しの打ち切りであり、契約期間を延長するものではない。
            // 元の終了日より後の解約日は矛盾のため拒否する(計上月数の無警告な増加を防ぐ)。
            if (contract.getEndDate() != null && cancelDate.isAfter(contract.getEndDate())) {
                throw BusinessException.of("error.contract.cancelDateAfterEnd");
            }
            contract.setEndDate(cancelDate);
        } else if (StatusConstants.CONTRACT_ENDED.equals(newStatus) || "終了".equals(newStatus)) {
            if (contract.getEndDate() == null) {
                contract.setEndDate(cancelDate != null ? cancelDate : LocalDate.now());
            }
        }
        contract.setStatus(newStatus);
        this.baseMapper.updateById(contract);
        if (contract.getEngineerId() != null) {
            if ("稼動中".equals(newStatus)) {
                engineerStatusService.onContractActive(contract.getEngineerId());
            } else if ("稼動中".equals(oldStatus) || "終了".equals(newStatus)
                    || "解約".equals(newStatus)) {
                engineerStatusService.releaseIfIdle(contract.getEngineerId());
            }
        }
        // staffing-capacity-planning: 状態遷移をactual allocationへ同期する（終了/解約→破棄）
        staffingSync.syncActual(contract.getId());
        if (StatusConstants.CONTRACT_CANCELLED.equals(newStatus)) {
            recordAiOutcome(svc -> svc.onContractCancelled(contract, originalEndDate, cancelDate));
        }
    }

    @Override
    public boolean hasActiveContract(Long engineerId) {
        return this.baseMapper.selectCount(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, "稼動中")) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Contract createDraftFromProposal(Proposal proposal) {
        // 冪等性: 同一提案から生成済みの契約があればそれを返す
        Contract existing = this.baseMapper.selectOne(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getProposalId, proposal.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        Project project = projectMapper.selectById(proposal.getProjectId());
        if (project == null) {
            throw BusinessException.of("error.contract.proposalProjectNotFound");
        }

        DraftSource src = new DraftSource(
                proposal.getEngineerId(),
                proposal.getProjectId(),
                project.getCustomerId(),
                // 提案単価は案件提示額の参考値であるため、0(ドラフトの初期値)とはしない
                proposal.getProposedUnitPrice(),
                null, null,
                "提案#" + proposal.getId() + "の成約による自動生成",
                proposal.getId(),
                null,
                null,
                proposal.getPositionId());
        Contract draft = buildAndSaveDraft(src);
        recordAiOutcome(svc -> svc.onContractPositionLinked(draft));
        return draft;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Contract createDraftFromQuotation(com.ses.entity.Quotation quotation) {
        // 冪等性: 同一見積から生成済みの契約があればそれを返す
        Contract existing = this.baseMapper.selectOne(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getQuotationId, quotation.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        // 見積受注からのドラフト生成は要員必須（要員なしでは契約を作れない）。
        if (quotation.getEngineerId() == null) {
            throw BusinessException.of("error.quotation.engineerRequired");
        }
        Long projectId = quotation.getProjectId();
        Long customerId = quotation.getCustomerId();
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            if (project != null) {
                customerId = project.getCustomerId();
            }
        }

        DraftSource src = new DraftSource(
                quotation.getEngineerId(),
                projectId,
                customerId,
                quotation.getUnitPrice(),
                quotation.getSettlementHoursMin(),
                quotation.getSettlementHoursMax(),
                "見積#" + quotation.getQuotationNo() + "の受注により自動生成",
                null,
                quotation.getId(),
                null,
                null);
        return buildAndSaveDraft(src);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Contract createDraftFromSalesOrderLine(com.ses.entity.SalesOrderLine line,
                                                  com.ses.entity.SalesOrder order) {
        // 冪等: 同一注文明細から生成済みの契約があればそれを返す（order_line_id UNIQUEでも防御）。
        Contract existing = this.baseMapper.selectOne(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getOrderLineId, line.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        if (line.getEngineerId() == null) {
            throw BusinessException.of("error.order.engineerRequired");
        }
        Long projectId = line.getProjectId();
        Long customerId = order.getCustomerId();
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            if (project != null) {
                customerId = project.getCustomerId();
            }
        }
        // t_contract.project_id はNOT NULLのため、案件が解決できない契約化は明確なエラーで拒否する
        if (projectId == null) {
            throw BusinessException.of(400, "error.order.projectRequired");
        }
        DraftSource src = new DraftSource(
                line.getEngineerId(),
                projectId,
                customerId != null ? customerId : order.getCustomerId(),
                line.getUnitPrice(),
                line.getSettlementMin(),
                line.getSettlementMax(),
                "注文#" + order.getOrderNo() + "明細" + line.getLineNo() + "の契約化により自動生成",
                null,
                order.getQuotationId(),
                line.getId(),
                null);
        return buildAndSaveDraft(src);
    }

    /**
     * 契約ドラフト生成の既定値規約を一箇所に集約する（提案経由・見積経由の共通合流点）。
     * 既定値: 原価0・契約形態=準委任・開始=翌月1日・ステータス=準備中・
     * 主担当営業フォールバック（退職済みなら未帰属NULL）。採番・検証は saveWithBusinessRules で再利用。
     */
    private Contract buildAndSaveDraft(DraftSource src) {
        Contract contract = new Contract();
        contract.setProposalId(src.proposalId());
        contract.setQuotationId(src.quotationId());
        contract.setOrderLineId(src.orderLineId());
        contract.setPositionId(src.positionId());
        contract.setEngineerId(src.engineerId());
        contract.setProjectId(src.projectId());
        contract.setCustomerId(src.customerId());
        contract.setContractType("準委任");
        // NOT NULL制約のため NULL 単価は0(ドラフトのため後で編集)。
        contract.setSellingPrice(src.sellingPrice() != null ? src.sellingPrice() : BigDecimal.ZERO);
        // 原価単価はドラフト段階では未確定のため0を仮置き。
        contract.setCostPrice(BigDecimal.ZERO);
        contract.setSettlementHoursMin(src.settlementMin());
        contract.setSettlementHoursMax(src.settlementMax());
        contract.setStartDate(LocalDate.now().plusMonths(1).withDayOfMonth(1));
        contract.setStatus("準備中");
        contract.setRemarks(src.remarks());
        // 主担当営業を引き継ぐ。退職済み(無効/削除)なら未帰属(NULL)でドラフト生成し後続の担当設定に委ねる。
        Long primaryId = engineerSalesService.findPrimarySalesUserId(src.engineerId());
        if (primaryId != null && !engineerSalesService.isActiveSalesUser(primaryId)) {
            primaryId = null;
        }
        contract.setSalesUserId(primaryId);

        try {
            saveWithBusinessRules(contract);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (src.orderLineId() != null) {
                Contract existing = baseMapper.selectOne(new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getOrderLineId, src.orderLineId()).last("LIMIT 1 FOR UPDATE"));
                if (existing != null) {
                    return existing;
                }
            }
            throw e;
        }
        return contract;
    }

    // ===== 契約単価の改定履歴（contract-price-history / P6） =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revisePrice(Long contractId, String applyFromMonth, BigDecimal selling,
                               BigDecimal cost, String reason) {
        // 行ロックで同月改定同士・通常更新・同期と直列化する（R3R-29）。
        Contract contract = this.baseMapper.selectByIdForUpdate(contractId);
        if (contract == null) {
            throw BusinessException.of("error.contract.notFound");
        }
        if (selling == null || selling.signum() < 0 || cost == null || cost.signum() < 0) {
            throw BusinessException.of("error.contract.priceRevision.invalidAmount");
        }
        java.time.YearMonth applyFrom;
        try {
            applyFrom = java.time.YearMonth.parse(applyFromMonth);
        } catch (Exception e) {
            throw BusinessException.of("error.contract.priceRevision.invalidMonth");
        }
        if (contract.getStartDate() != null
                && applyFrom.isBefore(java.time.YearMonth.from(contract.getStartDate()))) {
            throw BusinessException.of("error.contract.priceRevision.beforeStart");
        }

        List<com.ses.entity.ContractPriceHistory> histories = priceHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.ContractPriceHistory>()
                        .eq("contract_id", contractId));

        // 初回改定なら契約開始月・現行単価の初期履歴を自動補完（R1-3）。
        if (histories.isEmpty() && contract.getStartDate() != null) {
            String startMonth = java.time.YearMonth.from(contract.getStartDate()).toString();
            if (!startMonth.equals(applyFromMonth)) {
                com.ses.entity.ContractPriceHistory initial = new com.ses.entity.ContractPriceHistory();
                initial.setContractId(contractId);
                initial.setApplyFromMonth(startMonth);
                initial.setSellingPrice(contract.getSellingPrice());
                initial.setCostPrice(contract.getCostPrice());
                initial.setReason("初期単価(自動補完)");
                priceHistoryMapper.insert(initial);
                histories.add(initial);
            }
        }

        // upsert（contract_id + apply_from_month 一意）。
        com.ses.entity.ContractPriceHistory existing = histories.stream()
                .filter(h -> applyFromMonth.equals(h.getApplyFromMonth()))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.setSellingPrice(selling);
            existing.setCostPrice(cost);
            existing.setReason(reason);
            priceHistoryMapper.updateById(existing);
        } else {
            com.ses.entity.ContractPriceHistory rev = new com.ses.entity.ContractPriceHistory();
            rev.setContractId(contractId);
            rev.setApplyFromMonth(applyFromMonth);
            rev.setSellingPrice(selling);
            rev.setCostPrice(cost);
            rev.setReason(reason);
            priceHistoryMapper.insert(rev);
            histories.add(rev);
        }

        // t_contract の現在単価を「当月時点で有効な履歴」で再計算（新履歴そのものではなくリゾルバで解決）。
        List<com.ses.entity.ContractPriceHistory> fresh = priceHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.ContractPriceHistory>()
                        .eq("contract_id", contractId));
        com.ses.service.billing.ContractPriceResolver.ResolvedPrice current =
                com.ses.service.billing.ContractPriceResolver.resolveFrom(
                        contract, java.time.YearMonth.now(), fresh);
        // 単価列だけを部分UPDATEし、他項目を巻き戻さない（R3R-29）。
        this.baseMapper.updatePriceOnly(contractId, current.getSellingPrice(), current.getCostPrice());

        // 未確定の既存勤怠（applyFromMonth以降）の金額を再計算
        List<WorkRecord> unconfirmedRecords = workRecordMapper.selectList(
                new QueryWrapper<WorkRecord>()
                        .eq("contract_id", contractId)
                        .ge("work_month", applyFromMonth)
                        .ne("status", "確定"));
        for (WorkRecord wr : unconfirmedRecords) {
            if (wr.getActualHours() != null) {
                java.time.YearMonth ym = java.time.YearMonth.parse(wr.getWorkMonth());
                com.ses.service.billing.ContractPriceResolver.ResolvedPrice rp =
                        com.ses.service.billing.ContractPriceResolver.resolveFrom(contract, ym, fresh);
                BigDecimal bAmt = com.ses.service.billing.SettlementCalculator.calc(
                        rp.getSellingPrice(),
                        contract.getSettlementHoursMin(),
                        contract.getSettlementHoursMax(),
                        wr.getActualHours());
                BigDecimal pAmt = rp.getCostPrice() != null ? com.ses.service.billing.SettlementCalculator.calc(
                        rp.getCostPrice(),
                        contract.getSettlementHoursMin(),
                        contract.getSettlementHoursMax(),
                        wr.getActualHours()) : null;
                workRecordMapper.updateBillingAndPayment(wr.getId(), wr.getActualHours(), bAmt, pAmt);
            }
        }

        // 過去遡及かつ確定済み実績があれば警告。
        boolean retroactive = applyFrom.isBefore(java.time.YearMonth.now());
        if (retroactive) {
            long confirmed = workRecordMapper.selectCount(new QueryWrapper<WorkRecord>()
                    .eq("contract_id", contractId)
                    .eq("status", "確定")
                    .ge("work_month", applyFromMonth));
            return confirmed > 0;
        }
        return false;
    }

    @Override
    public List<com.ses.entity.ContractPriceHistory> priceHistory(Long contractId) {
        return priceHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.ContractPriceHistory>()
                        .eq("contract_id", contractId)
                        .orderByAsc("apply_from_month"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFuturePriceRevision(Long contractId, String applyFromMonth) {
        java.time.YearMonth applyFrom;
        try {
            applyFrom = java.time.YearMonth.parse(applyFromMonth);
        } catch (Exception e) {
            throw BusinessException.of("error.contract.priceRevision.invalidMonth");
        }
        // 将来予約（当月より後）のみ削除可。当月以前は精算に使われている可能性があるためロック。
        if (!applyFrom.isAfter(java.time.YearMonth.now())) {
            throw BusinessException.of("error.contract.priceRevision.pastLocked");
        }
        priceHistoryMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.ContractPriceHistory>()
                        .eq("contract_id", contractId)
                        .eq("apply_from_month", applyFromMonth));
    }

    @Override
    public void updateRenewalDecision(Long contractId, String decision) {
        if (decision != null
                && !com.ses.common.constant.RenewalState.DECISION_CONTINUE.equals(decision)
                && !com.ses.common.constant.RenewalState.DECISION_END.equals(decision)) {
            throw BusinessException.of(400, "error.contract.invalidRenewalDecision");
        }
        if (this.getById(contractId) == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // updateById(エンティティ) は使えない。Contract には renewalDecision の他にも
        // salesUserId / commissionBaseType / commissionRate が @TableField(updateStrategy = ALWAYS)
        // で定義されており、空の patch エンティティを渡すとそれらも SET 句に含まれて NULL 上書き
        // されてしまう（担当営業とインセンティブ個別設定が消える）。ALWAYS は「全項目を送る単一経路」
        // 前提の指定のため、部分更新はカラムを明示する UpdateWrapper で行う。
        this.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Contract>()
                .eq("id", contractId)
                .set("renewal_decision", decision)
                .setSql("version = version + 1"));
        if (com.ses.common.constant.RenewalState.DECISION_CONTINUE.equals(decision)) {
            Contract contract = this.getById(contractId);
            recordAiOutcome(svc -> svc.onContractRenewalContinued(contract));
        }
    }

    private void recordAiOutcome(java.util.function.Consumer<com.ses.service.ai.AiOutcomeService> action) {
        if (aiOutcomeService == null) {
            return;
        }
        com.ses.service.ai.AiOutcomeService svc = aiOutcomeService.getIfAvailable();
        if (svc != null) {
            action.accept(svc);
        }
    }

    /**
     * ドラフト生成の入力値オブジェクト（提案・見積の両方から渡される）。
     */
    private record DraftSource(
            Long engineerId,
            Long projectId,
            Long customerId,
            BigDecimal sellingPrice,
            BigDecimal settlementMin,
            BigDecimal settlementMax,
            String remarks,
            Long proposalId,
            Long quotationId,
            Long orderLineId,
            Long positionId) {
    }
}
