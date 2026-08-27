package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.dto.invoice.BpPaymentListDto;
import com.ses.entity.Contract;
import com.ses.entity.Invoice;
import com.ses.entity.InvoicePayment;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.InvoicePaymentMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.SystemConfigService;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.dto.payroll.PayrollStatementDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashFlowForecastServiceImpl implements CashFlowForecastService {

    private final InvoiceMapper invoiceMapper;
    private final InvoicePaymentMapper invoicePaymentMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final FreeeIntegrationService freeeIntegrationService;
    private final SystemConfigService systemConfigService;
    private final ContractMapper contractMapper;
    private final WorkRecordMapper workRecordMapper;
    private final MonthlyRevenueCalcService monthlyRevenueCalcService;

    @Override
    public CashFlowForecastDto forecast(YearMonth from, int months, BigDecimal openingBalance) {
        return forecast(from, months, openingBalance, null);
    }

    @Override
    public CashFlowForecastDto forecast(YearMonth from, int months, BigDecimal openingBalance,
                                       com.ses.service.billing.CashFlowForecastScope scope) {
        return forecastInternal(from, months, openingBalance, scope);
    }

    private CashFlowForecastDto forecastInternal(YearMonth from, int months, BigDecimal openingBalance,
                                                  com.ses.service.billing.CashFlowForecastScope scope) {
        boolean scoped = scope != null && !scope.companyWide();
        if (scoped) {
            // manager向けreportへ全社の期首残高・固定費・閾値を混ぜない。
            // 組織scope内の実データだけを表示し、会社全体の設定値は管理者reportに限定する。
            openingBalance = BigDecimal.ZERO;
        } else if (openingBalance == null) {
            openingBalance = systemConfigService.getDecimal("cashflow.opening-balance", BigDecimal.ZERO);
        }
        // 参照(GET)は副作用を持たない。資金ショート警告の発行は
        // NotificationGenerateService.cashflowAlert() の日次バッチが担う。
        BigDecimal fixedCost = scoped ? BigDecimal.ZERO
                : systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO);
        BigDecimal alertThreshold = scoped ? BigDecimal.ZERO
                : systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO);
        int bpSiteMonths = scoped ? 1
                : systemConfigService.getInt("cashflow.bp-payment-site-months", 1);

        BigDecimal estimatedPayroll = getEstimatedPayroll(scope);

        List<CashFlowForecastDto.CashFlowMonthDto> monthDtos = new ArrayList<>();
        BigDecimal currentBalance = openingBalance;

        // Fetch all unpaid invoices upfront
        LambdaQueryWrapper<Invoice> unpaidInvoiceQuery = new LambdaQueryWrapper<Invoice>()
                .ne(Invoice::getStatus, "入金済");
        if (scope != null && !scope.companyWide()) {
            unpaidInvoiceQuery.in(Invoice::getId,
                    scope.invoiceIds().isEmpty() ? List.of(-1L) : scope.invoiceIds());
        }
        List<Invoice> unpaidInvoices = invoiceMapper.selectList(unpaidInvoiceQuery);
        
        List<Long> unpaidInvoiceIds = unpaidInvoices.stream().map(Invoice::getId).toList();
        // 請求書ごとの入金合計を先に畳んでおく。月ループの内側で毎回 allPayments を走査すると
        // 「月数×請求書数×入金数」になり、件数が増えるほど参照が重くなる。
        Map<Long, BigDecimal> paidByInvoiceId = Map.of();
        if (!unpaidInvoiceIds.isEmpty()) {
            paidByInvoiceId = invoicePaymentMapper.selectList(new LambdaQueryWrapper<InvoicePayment>()
                            .in(InvoicePayment::getInvoiceId, unpaidInvoiceIds))
                    .stream()
                    .collect(Collectors.toMap(
                            InvoicePayment::getInvoiceId,
                            p -> (p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                                    .add(p.getFee() != null ? p.getFee() : BigDecimal.ZERO),
                            BigDecimal::add));
        }

        // Fetch all unpaid BP payments upfront
        List<BpPaymentListDto> unpaidBpPayments = scope == null || scope.companyWide()
                ? bpPaymentMapper.selectListWithDetails(null, "未払")
                : bpPaymentMapper.selectListWithDetailsScoped(null, "未払",
                scope.contractIds().isEmpty() ? List.of(-1L) : scope.contractIds(),
                nullIfEmpty(scope.organizationIds()), nullIfEmpty(scope.directUserIds()), scope.asOf());

        for (int i = 0; i < months; i++) {
            YearMonth ym = from.plusMonths(i);
            CashFlowForecastDto.CashFlowMonthDto dto = new CashFlowForecastDto.CashFlowMonthDto();
            dto.setMonth(ym.toString());
            dto.setFixedCost(fixedCost);
            dto.setPayrollTotal(estimatedPayroll);

            // Inflow calculation
            BigDecimal unpaidInvoiceTotal = BigDecimal.ZERO;
            for (Invoice inv : unpaidInvoices) {
                if (inv.getDueDate() != null) {
                    YearMonth invYm = YearMonth.from(inv.getDueDate());
                    // 予測窓に入るもの、または初月の場合で過去の滞留債権も合算する
                    boolean shouldInclude = invYm.equals(ym) || (i == 0 && invYm.isBefore(ym));
                    
                    if (shouldInclude) {
                        BigDecimal paid = paidByInvoiceId.getOrDefault(inv.getId(), BigDecimal.ZERO);
                        BigDecimal remaining = inv.getTotal() != null ? inv.getTotal().subtract(paid) : BigDecimal.ZERO;
                        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                            unpaidInvoiceTotal = unpaidInvoiceTotal.add(remaining);
                        }
                    }
                }
            }
            dto.setUnpaidInvoiceTotal(unpaidInvoiceTotal);
            dto.setInflow(unpaidInvoiceTotal);

            // Outflow calculation
            BigDecimal bpPaymentTotal = BigDecimal.ZERO;
            for (BpPaymentListDto bp : unpaidBpPayments) {
                // 親の支払行のみを実キャッシュアウトとして集計（子レイヤは親の内訳に過ぎないため）
                if (bp.getParentPaymentId() == null) {
                    if (bp.getWorkMonth() != null && !bp.getWorkMonth().isBlank()) {
                        try {
                            YearMonth workYm = YearMonth.parse(bp.getWorkMonth());
                            YearMonth targetPaymentYm = workYm.plusMonths(bpSiteMonths);
                            // 予測窓に入るもの、または初月の場合で過去分も合算する
                            boolean shouldInclude = targetPaymentYm.equals(ym) || (i == 0 && targetPaymentYm.isBefore(ym));
                            
                            if (shouldInclude && bp.getAmount() != null) {
                                bpPaymentTotal = bpPaymentTotal.add(bp.getAmount());
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse workMonth for BpPayment {}: {}", bp.getId(), bp.getWorkMonth());
                        }
                    }
                }
            }
            dto.setBpPaymentTotal(bpPaymentTotal);

            BigDecimal outflow = bpPaymentTotal.add(estimatedPayroll).add(fixedCost);
            dto.setOutflow(outflow);

            BigDecimal net = dto.getInflow().subtract(outflow);
            dto.setNet(net);

            currentBalance = currentBalance.add(net);
            dto.setBalance(currentBalance);

            monthDtos.add(dto);
        }

        CashFlowForecastDto result = new CashFlowForecastDto();
        result.setMonths(monthDtos);
        result.setAlertThreshold(alertThreshold);
        result.setReconciliation(buildReconciliation(from, scope));
        return result;
    }

    /**
     * 起点月の売上口径を全社KPI（{@link MonthlyRevenueCalcService}）と突合する（FR-05 要件1.4）。
     * ダッシュボードと同じ対象契約・確定実績の絞り込みを用いることで、CFの入金予定の元になっている
     * 請求額が全社KPIの売上と同じ母集団から来ていることを確認できるようにする。
     */
    private CashFlowForecastDto.ReconciliationDto buildReconciliation(YearMonth month,
                                                                       com.ses.service.billing.CashFlowForecastScope scope) {
        String monthStr = month.toString();

        // 当月の確定実績（contract_id -> record）。DashboardServiceImpl と同一の絞り込み。
        LambdaQueryWrapper<WorkRecord> workRecordQuery = new LambdaQueryWrapper<WorkRecord>()
                .eq(WorkRecord::getWorkMonth, monthStr)
                .eq(WorkRecord::getStatus, "確定");
        if (scope != null && !scope.companyWide()) {
            workRecordQuery.in(WorkRecord::getContractId,
                    scope.contractIds().isEmpty() ? List.of(-1L) : scope.contractIds());
        }
        Map<Long, WorkRecord> confirmedByContractId = workRecordMapper.selectList(workRecordQuery)
                .stream()
                .filter(w -> w.getContractId() != null)
                .collect(Collectors.toMap(WorkRecord::getContractId, w -> w, (w1, w2) -> w1));

        LambdaQueryWrapper<Contract> contractQuery = new LambdaQueryWrapper<Contract>()
                .in(Contract::getStatus, "稼動中", "終了", "解約")
                .le(Contract::getStartDate, month.atEndOfMonth());
        if (scope != null && !scope.companyWide()) {
            contractQuery.in(Contract::getId,
                    scope.contractIds().isEmpty() ? List.of(-1L) : scope.contractIds());
        }
        List<Contract> contracts = contractMapper.selectList(contractQuery);

        MonthlyRevenueCalcService.MonthlyAmount amount =
                monthlyRevenueCalcService.calc(month, contracts, confirmedByContractId);
        BigDecimal kpiSales = BigDecimal.valueOf(amount.getSales());

        // 当月請求分の税抜合計（請求書は確定実績の billing_amount から生成されるため kpiSales と一致するはず）。
        LambdaQueryWrapper<Invoice> invoiceQuery = new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getBillingMonth, monthStr);
        if (scope != null && !scope.companyWide()) {
            invoiceQuery.in(Invoice::getId,
                    scope.invoiceIds().isEmpty() ? List.of(-1L) : scope.invoiceIds());
        }
        BigDecimal invoicedSubtotal = invoiceMapper.selectList(invoiceQuery)
                .stream()
                .map(inv -> inv.getSubtotal() != null ? inv.getSubtotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CashFlowForecastDto.ReconciliationDto dto = new CashFlowForecastDto.ReconciliationDto();
        dto.setMonth(monthStr);
        dto.setKpiSales(kpiSales);
        dto.setInvoicedSubtotal(invoicedSubtotal);
        dto.setDifference(invoicedSubtotal.subtract(kpiSales));
        return dto;
    }

    /**
     * 給与キャッシュアウト推定（design §14の優先順位）。
     * <ol>
     *   <li>直近月の対応付け済み内部要員の利用可能なgrossAmount合計（計算中nullは0へ変換しない）</li>
     *   <li>会社負担はその月全体で公式実額（employerShareAmount）が完全な場合だけ実額を使う</li>
     *   <li>実額が不完全なら既存設定率（cashflow.payroll-employer-burden-rate）をgrossへ適用</li>
     *   <li>直近月0件/利用不可なら2か月前を試す。それも不可/外部障害なら設定値（payroll-estimate）へfallback</li>
     * </ol>
     * 給与0円が正式値である月は0円のまま返す（fallbackしない）。
     */
    private BigDecimal getEstimatedPayroll(com.ses.service.billing.CashFlowForecastScope scope) {
        if (!freeeIntegrationService.connected()) {
            return scope != null && !scope.companyWide()
                    ? BigDecimal.ZERO
                    : systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO);
        }
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        for (int attempt = 0; attempt < 2; attempt++) {
            YearMonth ym = lastMonth.minusMonths(attempt);
            try {
                List<PayrollStatementDto> statements = freeeIntegrationService.statements(
                        ym.getYear(), ym.getMonthValue(), "salary");
                if (scope != null && !scope.companyWide()) {
                    statements = statements == null ? List.of() : statements.stream()
                            .filter(statement -> statement.getEngineerId() != null
                                    && scope.engineerIds().contains(statement.getEngineerId()))
                            .toList();
                }
                if (statements == null || statements.isEmpty()) {
                    continue; // 利用可能金額0件 → 前月→2か月前の順に試す
                }
                // その月全体でgrossが利用可能な場合だけ実額を使う（計算中nullの混在は月単位で除外）
                boolean allGrossAvailable = true;
                BigDecimal gross = BigDecimal.ZERO;
                for (PayrollStatementDto s : statements) {
                    if (s.getGrossAmount() == null) {
                        allGrossAvailable = false;
                        break;
                    }
                    gross = gross.add(s.getGrossAmount());
                }
                if (!allGrossAvailable) {
                    continue; // 計算中（または混在）の月は次の候補月を試す
                }
                // 会社負担実額が全件揃う月だけ実額を使い、不完全なら既存率で推定する（design §14-2/3）
                boolean employerShareComplete = statements.stream()
                        .allMatch(s -> s.getEmployerShareAmount() != null);
                if (employerShareComplete) {
                    BigDecimal employerShare = statements.stream()
                            .map(PayrollStatementDto::getEmployerShareAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return gross.add(employerShare);
                }
                return gross.add(employerBurden(gross, scope));
            } catch (Exception e) {
                // 外部障害はこれ以上試行せず、既存設定値へfallback（design §14-5）
                log.warn("Failed to fetch freee payroll for cashflow forecast ({}): {}", ym, e.getMessage());
                return scope != null && !scope.companyWide()
                        ? BigDecimal.ZERO
                        : systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO);
            }
        }
        return scope != null && !scope.companyWide()
                ? BigDecimal.ZERO
                : systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO);
    }

    /**
     * 社会保険料等の事業主負担分を総支給に対する率(%)で上乗せする。
     * 総支給には本人負担分しか含まれないため、率を設定しないと会社の実支出を過小評価する。既定0%。
     */
    private BigDecimal employerBurden(BigDecimal gross,
                                      com.ses.service.billing.CashFlowForecastScope scope) {
        if (scope != null && !scope.companyWide()) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = systemConfigService.getDecimal("cashflow.payroll-employer-burden-rate", BigDecimal.ZERO);
        if (rate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    private List<Long> nullIfEmpty(List<Long> values) {
        return values == null || values.isEmpty() ? null : values;
    }
}
