package com.ses.service.accounting.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto.ReconciliationItemDto;
import com.ses.dto.accounting.canonical.CanonicalPaymentSync;
import com.ses.entity.*;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.AccountingReconciliationService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 会計・支払月次照合サービス実装 (B3 / design §5, §6.1, §6.3)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingReconciliationServiceImpl implements AccountingReconciliationService {

    private final InvoiceMapper invoiceMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final CustomerMapper customerMapper;
    private final IntegrationJobService jobService;
    private final IntegrationConnectionService connectionService;
    private final AccountingProviderFactory providerFactory;
    private final SystemConfigMapper systemConfigMapper;
    private final ObjectMapper objectMapper;

    private static final String IGNORE_CONFIG_PREFIX = "accounting.reconciliation.ignore.";

    @Override
    public AccountingReconciliationSummaryDto reconcileMonth(String month) {
        if (month == null || month.isBlank()) {
            month = YearMonth.now().toString();
        }

        List<ReconciliationItemDto> items = new ArrayList<>();
        Map<String, String> ignoreMap = loadIgnoreMap(month);

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");

        // 1. 売上請求の照合
        List<Invoice> invoices = invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getBillingMonth, month));

        for (Invoice inv : invoices) {
            Customer cust = inv.getCustomerId() != null ? customerMapper.selectById(inv.getCustomerId()) : null;
            String partnerName = cust != null ? cust.getCompanyName() : "顧客ID:" + inv.getCustomerId();

            IntegrationJob latestJob = jobService.getLatestJob("INVOICE", inv.getId(), "SALES_INVOICE_SYNC");
            String itemKey = "SALES:" + inv.getId();
            String ignoreReason = ignoreMap.get(itemKey);

            if (latestJob != null && "SUCCEEDED".equals(latestJob.getStatus())) {
                items.add(ReconciliationItemDto.builder()
                        .category("SALES")
                        .internalId(inv.getId())
                        .internalNo(inv.getInvoiceNo())
                        .partnerName(partnerName)
                        .internalAmount(inv.getTotal())
                        .externalDealId(latestJob.getExternalId())
                        .externalRefNo(inv.getInvoiceNo())
                        .externalAmount(inv.getTotal()) // 成功時は一致確認済み
                        .status(ignoreReason != null ? "IGNORED" : "MATCHED")
                        .ignoreReason(ignoreReason)
                        .build());
            } else if (latestJob != null && "FAILED".equals(latestJob.getStatus()) && "AMOUNT_MISMATCH".equals(latestJob.getErrorCode())) {
                items.add(ReconciliationItemDto.builder()
                        .category("SALES")
                        .internalId(inv.getId())
                        .internalNo(inv.getInvoiceNo())
                        .partnerName(partnerName)
                        .internalAmount(inv.getTotal())
                        .externalDealId(latestJob.getExternalId())
                        .externalRefNo(inv.getInvoiceNo())
                        .externalAmount(BigDecimal.ZERO)
                        .status(ignoreReason != null ? "IGNORED" : "AMOUNT_MISMATCH")
                        .discrepancyReason("外部取引との金額不一致")
                        .ignoreReason(ignoreReason)
                        .build());
            } else {
                items.add(ReconciliationItemDto.builder()
                        .category("SALES")
                        .internalId(inv.getId())
                        .internalNo(inv.getInvoiceNo())
                        .partnerName(partnerName)
                        .internalAmount(inv.getTotal())
                        .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                        .discrepancyReason("freee未送信または連携未完了")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // 2. BP仕入の照合
        List<BpPayment> bpPayments = bpPaymentMapper.selectList(new LambdaQueryWrapper<BpPayment>());
        for (BpPayment bp : bpPayments) {
            IntegrationJob latestJob = jobService.getLatestJob("BP_PAYMENT", bp.getId(), "BP_PURCHASE_SYNC");
            String itemKey = "PURCHASE:" + bp.getId();
            String ignoreReason = ignoreMap.get(itemKey);

            if (latestJob != null && "SUCCEEDED".equals(latestJob.getStatus())) {
                items.add(ReconciliationItemDto.builder()
                        .category("PURCHASE")
                        .internalId(bp.getId())
                        .internalNo("BP-" + bp.getId())
                        .partnerName(bp.getPayeeCompanyName())
                        .internalAmount(bp.getAmount())
                        .externalDealId(latestJob.getExternalId())
                        .externalRefNo("BP-" + bp.getId())
                        .externalAmount(bp.getAmount())
                        .status(ignoreReason != null ? "IGNORED" : "MATCHED")
                        .ignoreReason(ignoreReason)
                        .build());
            } else {
                items.add(ReconciliationItemDto.builder()
                        .category("PURCHASE")
                        .internalId(bp.getId())
                        .internalNo("BP-" + bp.getId())
                        .partnerName(bp.getPayeeCompanyName())
                        .internalAmount(bp.getAmount())
                        .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                        .discrepancyReason("freee仕入未送信")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // 3. 外部のみ存在する取引 (EXTERNAL_ONLY) - 外部API呼出
        if (conn != null && conn.getEncryptedTokens() != null) {
            try {
                AccountingProvider provider = providerFactory.getProvider(conn);
                YearMonth ym = YearMonth.parse(month);
                List<CanonicalPaymentSync> extPayments = provider.fetchPayments(
                        conn, ym.atDay(1), ym.atEndOfMonth());

                for (CanonicalPaymentSync ext : extPayments) {
                    // 内部に紐付かない外部決済を検出
                    boolean foundInInternal = items.stream()
                            .anyMatch(i -> ext.getDealId() != null && ext.getDealId().equals(i.getExternalDealId()));
                    if (!foundInInternal) {
                        String extKey = "EXTERNAL:" + ext.getDealId();
                        String ignoreReason = ignoreMap.get(extKey);
                        items.add(ReconciliationItemDto.builder()
                                .category("EXTERNAL_DEAL")
                                .partnerName(ext.getPartnerName() != null ? ext.getPartnerName() : "外部取引先")
                                .externalDealId(ext.getDealId())
                                .externalRefNo(ext.getReferenceNo())
                                .externalAmount(ext.getAmount())
                                .status(ignoreReason != null ? "IGNORED" : "EXTERNAL_ONLY")
                                .discrepancyReason("外部システムにのみ存在する取引（自動内部登録は行われません）")
                                .ignoreReason(ignoreReason)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("External payments fetch skipped or failed for month={}", month, e);
            }
        }

        int matched = 0, internalOnly = 0, externalOnly = 0, mismatch = 0, ignored = 0;
        for (ReconciliationItemDto item : items) {
            switch (item.getStatus()) {
                case "MATCHED" -> matched++;
                case "INTERNAL_ONLY" -> internalOnly++;
                case "EXTERNAL_ONLY" -> externalOnly++;
                case "AMOUNT_MISMATCH" -> mismatch++;
                case "IGNORED" -> ignored++;
            }
        }

        // 重大不一致（未解決の金額不一致や未送信売上）が0件であれば月次締め可能
        boolean readyForClosing = (mismatch == 0 && internalOnly == 0);

        return AccountingReconciliationSummaryDto.builder()
                .month(month)
                .matchedCount(matched)
                .internalOnlyCount(internalOnly)
                .externalOnlyCount(externalOnly)
                .amountMismatchCount(mismatch)
                .ignoredCount(ignored)
                .readyForClosing(readyForClosing)
                .items(items)
                .build();
    }

    @Override
    @Transactional
    public void ignoreDiscrepancy(String month, String category, String externalDealId, Long internalId, String reason, Long userId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "除外・無視理由を入力してください");
        }

        String itemKey = (internalId != null) ? category + ":" + internalId : "EXTERNAL:" + externalDealId;
        Map<String, String> ignoreMap = loadIgnoreMap(month);
        ignoreMap.put(itemKey, reason);

        saveIgnoreMap(month, ignoreMap);
        log.info("Recorded reconciliation ignore: month={}, key={}, reason={}, userId={}", month, itemKey, reason, userId);
    }

    @Override
    public void assertReconciledForClosing(String month) {
        AccountingReconciliationSummaryDto summary = reconcileMonth(month);
        if (!summary.isReadyForClosing()) {
            throw new BusinessException(400, String.format(
                    "月次締めエラー: %s に会計連携の未解消差異が存在します (金額不一致: %d件, 未送信: %d件)。照合画面で確認・解消してください。",
                    month, summary.getAmountMismatchCount(), summary.getInternalOnlyCount()));
        }
    }

    private Map<String, String> loadIgnoreMap(String month) {
        String key = IGNORE_CONFIG_PREFIX + month;
        SystemConfig config = systemConfigMapper.selectById(key);
        if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(config.getConfigValue(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse ignore map for month={}", month, e);
            return new HashMap<>();
        }
    }

    private void saveIgnoreMap(String month, Map<String, String> map) {
        String key = IGNORE_CONFIG_PREFIX + month;
        try {
            String json = objectMapper.writeValueAsString(map);
            SystemConfig config = systemConfigMapper.selectById(key);
            if (config == null) {
                config = new SystemConfig();
                config.setConfigKey(key);
                config.setConfigValue(json);
                config.setDescription("月次照合除外設定 (" + month + ")");
                systemConfigMapper.insert(config);
            } else {
                config.setConfigValue(json);
                systemConfigMapper.updateById(config);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save ignore map", e);
        }
    }

    private IntegrationConnection resolveConnection(String tenantId, Long corporateEntityId, String provider, String product) {
        IntegrationConnection direct = connectionService.getConnection(tenantId, corporateEntityId, provider, product);
        if (direct != null && direct.getEncryptedTokens() != null) {
            return direct;
        }
        List<IntegrationConnection> list = connectionService.listConnections(tenantId);
        for (IntegrationConnection c : list) {
            if (provider.equalsIgnoreCase(c.getProvider()) && product.equalsIgnoreCase(c.getProduct())
                    && c.getEncryptedTokens() != null) {
                return c;
            }
        }
        return direct != null ? direct : connectionService.getOrCreateConnection(tenantId, corporateEntityId, provider, product);
    }
}
