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
    private final com.ses.service.EngineerSalesService engineerSalesService;
    private final com.ses.mapper.ContractPriceHistoryMapper priceHistoryMapper;

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
        // 削除で稼動中契約・オープン提案が無くなった場合は要員を Bench に戻す
        // （releaseIfIdle が両方を確認してから判定するため安全。稼動中契約は元々削除不可）。
        if (removed && target.getEngineerId() != null) {
            engineerStatusService.releaseIfIdle(target.getEngineerId());
        }
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

        boolean salesUserUnchanged = old != null && Objects.equals(old.getSalesUserId(), c.getSalesUserId());
        if (c.getSalesUserId() != null && !salesUserUnchanged) {
            // 在職判定は EngineerSalesService.isActiveSalesUser に一本化（二重定義を避ける）。
            if (!engineerSalesService.isActiveSalesUser(c.getSalesUserId())) {
                throw BusinessException.of("error.contract.salesUserInvalid");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveWithBusinessRules(Contract contract) {
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
                    // ignore and retry
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWithBusinessRules(Contract contract) {
        // 行ロックで単価同期/改定と直列化する（R3R-29）。
        Contract old = this.baseMapper.selectByIdForUpdate(contract.getId());
        if (old == null) {
            throw BusinessException.of("error.contract.notFound");
        }

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
        if ("稼動中".equals(newStatus) && newEngineerId != null) {
            engineerStatusService.onContractActive(newEngineerId);
        } else if (!engineerChanged && "稼動中".equals(old.getStatus()) && newEngineerId != null) {
            engineerStatusService.releaseIfIdle(newEngineerId);
        }
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
                // 売上単価は提案の提示単価を引継ぐ。NULL時は0(ドラフトのため後で編集)。
                proposal.getProposedUnitPrice(),
                null, null,
                "提案#" + proposal.getId() + "の成約により自動生成",
                proposal.getId(),
                null);
        return buildAndSaveDraft(src);
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
                quotation.getId());
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

        saveWithBusinessRules(contract);
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
            Long quotationId) {
    }
}














