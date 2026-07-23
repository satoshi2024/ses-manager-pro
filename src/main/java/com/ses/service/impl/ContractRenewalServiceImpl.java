package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.ContractRenewalService;
import com.ses.service.ContractService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 契約自動更新ドラフト生成サービス実装。
 *
 * auto_renew=1 かつ status=稼動中 の契約のうち、終了日が
 * notice.contract-end-days（既定30日）以内に迫っているものについて、
 * 終了日の翌日を開始日とする「準備中」の後続契約ドラフトを自動生成する。
 * 生成済みかどうかは renewed_from_contract_id で判定し、日次実行しても
 * 重複生成しない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractRenewalServiceImpl implements ContractRenewalService {

    private final ContractMapper contractMapper;
    private final ContractService contractService;
    private final SystemConfigService systemConfigService;
    private final ObjectProvider<NotificationService> notificationServiceProvider;
    private final com.ses.service.EngineerSalesService engineerSalesService;
    private final org.springframework.context.ApplicationContext applicationContext;

    @Override
    public int generateRenewalDrafts() {
        int days = systemConfigService.getInt("notice.contract-end-days", 30);
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(days);

        List<Contract> candidates = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getAutoRenew, 1)
                .eq(Contract::getStatus, "稼動中")
                .isNotNull(Contract::getEndDate)
                .ge(Contract::getEndDate, today)
                .le(Contract::getEndDate, horizon));

        int created = 0;
        ContractRenewalService self = applicationContext.getBean(ContractRenewalService.class);
        for (Contract original : candidates) {
            try {
                if (self.processSingleRenewal(original)) {
                    created++;
                }
            } catch (Exception e) {
                log.warn("契約更新ドラフト作成失敗: {}", original.getContractNo(), e);
                notificationServiceProvider.ifAvailable(ns -> ns.publish(
                        "SYSTEM",
                        "契約更新ドラフト作成エラー",
                        "契約 " + original.getContractNo() + " の自動更新でエラーが発生しました: " + e.getMessage(),
                        null, null));
            }
        }

        if (created > 0) {
            log.info("契約自動更新ドラフトを{}件生成しました", created);
        }
        return created;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean processSingleRenewal(Contract original) {
        if (hasExistingDraft(original.getId())) {
            return false;
        }
        Contract draft = buildDraft(original);
        contractService.saveWithBusinessRules(draft);

        notificationServiceProvider.ifAvailable(ns -> ns.publish(
                "CONTRACT_RENEWAL_DRAFT",
                "契約更新ドラフトを作成しました",
                "契約 " + original.getContractNo() + " の自動更新ドラフトを作成しました（開始日: "
                        + draft.getStartDate() + "）",
                com.ses.common.constant.NotificationLinks.CONTRACT_LIST,
                "CONTRACT_RENEWAL_DRAFT:" + original.getId()));
        
        return true;
    }

    private boolean hasExistingDraft(Long originalContractId) {
        return contractMapper.countRenewedDraftsIncludingDeleted(originalContractId) > 0;
    }

    private Contract buildDraft(Contract original) {
        Contract draft = new Contract();
        draft.setEngineerId(original.getEngineerId());
        draft.setProjectId(original.getProjectId());
        draft.setCustomerId(original.getCustomerId());
        draft.setContractType(original.getContractType());
        draft.setStartDate(original.getEndDate().plusDays(1));
        draft.setEndDate(null);
        draft.setSellingPrice(original.getSellingPrice());
        draft.setCostPrice(original.getCostPrice());
        draft.setSettlementHoursMin(original.getSettlementHoursMin());
        draft.setSettlementHoursMax(original.getSettlementHoursMax());
        draft.setFractionRule(original.getFractionRule());
        draft.setAutoRenew(original.getAutoRenew());
        
        Long primaryId = original.getSalesUserId();
        if (primaryId != null && !engineerSalesService.isActiveSalesUser(primaryId)) {
            primaryId = null;
        }
        draft.setSalesUserId(primaryId);
        
        draft.setCommissionBaseType(original.getCommissionBaseType());
        draft.setCommissionRate(original.getCommissionRate());
        draft.setStatus("準備中");
        draft.setRemarks("契約 " + original.getContractNo() + " の自動更新ドラフト");
        draft.setRenewedFromContractId(original.getId());
        return draft;
    }
}
