package com.ses.service.billing;

import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.dto.invoice.BpPaymentListDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.entity.Contract;
import com.ses.entity.Invoice;
import com.ses.entity.InvoicePayment;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.InvoicePaymentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import com.ses.service.impl.CashFlowForecastServiceImpl;
import com.ses.service.impl.MonthlyRevenueCalcServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class CashFlowForecastServiceTest {

    @Mock
    private InvoiceMapper invoiceMapper;
    @Mock
    private InvoicePaymentMapper invoicePaymentMapper;
    @Mock
    private BpPaymentMapper bpPaymentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private FreeeIntegrationService freeeIntegrationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private ContractMapper contractMapper;
    @Mock
    private WorkRecordMapper workRecordMapper;

    /**
     * 口径突合の検証が目的なので、モックのエコーではなく全社KPIの実ロジックを注入する。
     * 純粋ロジック（単価リゾルバは任意依存）のため no-arg 生成できる。
     */
    @Spy
    private MonthlyRevenueCalcService monthlyRevenueCalcService = new MonthlyRevenueCalcServiceImpl();

    @InjectMocks
    private CashFlowForecastServiceImpl service;

    @Test
    void testForecast() {
        // Setup SystemConfig
        when(systemConfigService.getDecimal("cashflow.opening-balance", BigDecimal.ZERO)).thenReturn(new BigDecimal("1000000"));
        when(systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO)).thenReturn(new BigDecimal("200000"));
        when(systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO)).thenReturn(new BigDecimal("500000"));
        when(systemConfigService.getInt("cashflow.bp-payment-site-months", 1)).thenReturn(1);

        // Setup Freee
        when(freeeIntegrationService.connected()).thenReturn(true);
        when(systemConfigService.getDecimal("cashflow.payroll-employer-burden-rate", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        PayrollStatementDto payroll = new PayrollStatementDto();
        payroll.setGrossAmount(new BigDecimal("300000"));
        when(freeeIntegrationService.statements(anyInt(), anyInt(), eq("salary"))).thenReturn(List.of(payroll));

        // Setup Invoices
        Invoice inv1 = new Invoice();
        inv1.setId(1L);
        inv1.setTotal(new BigDecimal("800000"));
        inv1.setDueDate(LocalDate.of(2026, 8, 31));
        inv1.setStatus("送付済"); // Not 入金済

        Invoice inv2 = new Invoice();
        inv2.setId(2L);
        inv2.setTotal(new BigDecimal("500000"));
        inv2.setDueDate(LocalDate.of(2026, 9, 30));
        inv2.setStatus("未送付");

        Invoice invOverdue = new Invoice();
        invOverdue.setId(3L);
        invOverdue.setTotal(new BigDecimal("200000"));
        invOverdue.setDueDate(LocalDate.of(2026, 7, 31)); // Overdue (before fromMonth=8)
        invOverdue.setStatus("未送付");

        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv1, inv2, invOverdue));

        // Setup InvoicePayments
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(1L);
        payment.setAmount(new BigDecimal("100000"));
        payment.setFee(new BigDecimal("500"));
        when(invoicePaymentMapper.selectList(any())).thenReturn(List.of(payment));

        // Setup BpPayments
        BpPaymentListDto bp1 = new BpPaymentListDto();
        bp1.setId(1L);
        bp1.setWorkMonth("2026-07"); // site = 1 => payment month is 2026-08
        bp1.setAmount(new BigDecimal("150000"));

        BpPaymentListDto bpOverdue = new BpPaymentListDto();
        bpOverdue.setId(2L);
        bpOverdue.setWorkMonth("2026-06"); // site = 1 => payment month is 2026-07, should be merged into 2026-08 (first month)
        bpOverdue.setAmount(new BigDecimal("100000"));

        when(bpPaymentMapper.selectListWithDetails(isNull(), eq("未払"))).thenReturn(List.of(bp1, bpOverdue));

        // Run
        YearMonth from = YearMonth.of(2026, 8);
        CashFlowForecastDto result = service.forecast(from, 2, null);

        // Verify Months
        assertEquals(2, result.getMonths().size());
        
        CashFlowForecastDto.CashFlowMonthDto m1 = result.getMonths().get(0);
        assertEquals("2026-08", m1.getMonth());
        // Inflow: 800000 (inv1) - 100500 (paid) + 200000 (overdue) = 899500
        assertEquals(new BigDecimal("899500"), m1.getInflow());
        // Outflow: 150000 (BP 08) + 100000 (BP overdue) + 300000 (Payroll) + 200000 (Fixed) = 750000
        assertEquals(new BigDecimal("750000"), m1.getOutflow());
        // Net: 899500 - 750000 = 149500
        assertEquals(new BigDecimal("149500"), m1.getNet());
        // Balance: 1000000 + 149500 = 1149500
        assertEquals(new BigDecimal("1149500"), m1.getBalance());

        CashFlowForecastDto.CashFlowMonthDto m2 = result.getMonths().get(1);
        assertEquals("2026-09", m2.getMonth());
        // Inflow: 500000 (inv2)
        assertEquals(new BigDecimal("500000"), m2.getInflow());
        // Outflow: 0 (BP) + 300000 (Payroll) + 200000 (Fixed) = 500000
        assertEquals(new BigDecimal("500000"), m2.getOutflow());
        // Net: 0
        assertEquals(new BigDecimal("0"), m2.getNet());
        // Balance: 1149500 - 0 = 1149500
        assertEquals(new BigDecimal("1149500"), m2.getBalance());
        
        verify(notificationService, times(0)).publishToUser(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testAlertThreshold() {
        when(systemConfigService.getDecimal("cashflow.opening-balance", BigDecimal.ZERO)).thenReturn(new BigDecimal("100000"));
        when(systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO)).thenReturn(new BigDecimal("500000"));
        when(systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO)).thenReturn(new BigDecimal("0"));
        when(systemConfigService.getInt("cashflow.bp-payment-site-months", 1)).thenReturn(1);
        when(systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        
        when(freeeIntegrationService.connected()).thenReturn(false);
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        when(bpPaymentMapper.selectListWithDetails(any(), any())).thenReturn(List.of());

        SysUser admin = new SysUser();
        admin.setId(99L);
        admin.setRole("管理者");
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));

        YearMonth from = YearMonth.of(2026, 8);
        CashFlowForecastDto result = service.forecast(from, 1, null);

        // Balance will be 100000 - 500000 = -400000 < 0
        assertEquals(new BigDecimal("-400000"), result.getMonths().get(0).getBalance());
        
        verify(notificationService, times(1)).publishToUser(
                eq(99L),
                eq("CASHFLOW_ALERT"),
                anyString(),
                anyString(),
                eq("/#cashflow"),
                eq("CASHFLOW_ALERT_2026-08")
        );
    }
    
    @Test
    void testSimulationSuppressesAlerts() {
        when(systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO)).thenReturn(new BigDecimal("500000"));
        when(systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO)).thenReturn(new BigDecimal("0"));
        when(systemConfigService.getInt("cashflow.bp-payment-site-months", 1)).thenReturn(1);
        when(systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        
        when(freeeIntegrationService.connected()).thenReturn(false);
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        when(bpPaymentMapper.selectListWithDetails(any(), any())).thenReturn(List.of());

        // Do not mock sysUserMapper because it should not be called

        YearMonth from = YearMonth.of(2026, 8);
        // Simulation mode with custom opening balance
        CashFlowForecastDto result = service.forecast(from, 1, new BigDecimal("100000"));

        // Balance will be 100000 - 500000 = -400000 < 0
        assertEquals(new BigDecimal("-400000"), result.getMonths().get(0).getBalance());
        
        // Ensure NO notification
        verify(notificationService, times(0)).publishToUser(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 多重下請けのBP支払は、子レイヤの金額が親の内訳（親 amount − Σ子 amount = マージン）に過ぎない。
     * 自社の実キャッシュアウトはルート行のみなので、子レイヤを二重計上しないこと。
     */
    @Test
    void testMultiLayerBpPaymentOnlyCountsRootLayer() {
        when(systemConfigService.getDecimal("cashflow.opening-balance", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO)).thenReturn(new BigDecimal("-99999999"));
        when(systemConfigService.getInt("cashflow.bp-payment-site-months", 1)).thenReturn(1);
        when(systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(freeeIntegrationService.connected()).thenReturn(false);
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        // 1次請(ルート)への支払 500,000。これが自社の実支出。
        BpPaymentListDto root = new BpPaymentListDto();
        root.setId(1L);
        root.setWorkMonth("2026-07");
        root.setAmount(new BigDecimal("500000"));
        root.setLayerOrder(1);

        // 2次請への支払 400,000。1次請が払うものなので自社のCFには乗らない。
        BpPaymentListDto child = new BpPaymentListDto();
        child.setId(2L);
        child.setWorkMonth("2026-07");
        child.setAmount(new BigDecimal("400000"));
        child.setLayerOrder(2);
        child.setParentPaymentId(1L);

        when(bpPaymentMapper.selectListWithDetails(isNull(), eq("未払"))).thenReturn(List.of(root, child));

        CashFlowForecastDto result = service.forecast(YearMonth.of(2026, 8), 1, null);

        // 900,000 ではなくルートの 500,000 のみ
        assertEquals(new BigDecimal("500000"), result.getMonths().get(0).getBpPaymentTotal());
        assertEquals(new BigDecimal("500000"), result.getMonths().get(0).getOutflow());
    }

    /**
     * 要件1.4: 起点月の売上口径が全社KPI（MonthlyRevenueCalcService）と突合すること。
     * 請求書は確定実績の billing_amount から生成されるため、当月請求分の税抜合計と
     * KPI売上（確定実績優先・準備中除外）は一致し、差分0になる。
     */
    @Test
    void testReconciliationMatchesDashboardKpi() {
        when(systemConfigService.getDecimal("cashflow.opening-balance", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO)).thenReturn(new BigDecimal("-99999999"));
        when(systemConfigService.getInt("cashflow.bp-payment-site-months", 1)).thenReturn(1);
        when(systemConfigService.getDecimal("cashflow.payroll-estimate", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(freeeIntegrationService.connected()).thenReturn(false);
        when(bpPaymentMapper.selectListWithDetails(any(), any())).thenReturn(List.of());

        YearMonth from = YearMonth.of(2026, 8);

        // 稼動中契約 + 当月の確定実績（billing_amount 800,000）→ KPI売上 800,000
        Contract contract = new Contract();
        contract.setId(10L);
        contract.setStatus("稼動中");
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setSellingPrice(new BigDecimal("700000")); // 確定実績があるので使われない
        when(contractMapper.selectList(any())).thenReturn(List.of(contract));

        WorkRecord confirmed = new WorkRecord();
        confirmed.setContractId(10L);
        confirmed.setWorkMonth("2026-08");
        confirmed.setStatus("確定");
        confirmed.setBillingAmount(new BigDecimal("800000"));
        confirmed.setPaymentAmount(new BigDecimal("600000"));
        when(workRecordMapper.selectList(any())).thenReturn(List.of(confirmed));

        // 上記実績から生成された請求書（税抜 800,000 / 税込 880,000、支払期限は翌月末）
        Invoice invoice = new Invoice();
        invoice.setId(1L);
        invoice.setBillingMonth("2026-08");
        invoice.setSubtotal(new BigDecimal("800000"));
        invoice.setTotal(new BigDecimal("880000"));
        invoice.setDueDate(LocalDate.of(2026, 9, 30));
        invoice.setStatus("送付済");
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice));

        CashFlowForecastDto result = service.forecast(from, 2, null);

        CashFlowForecastDto.ReconciliationDto rec = result.getReconciliation();
        assertEquals("2026-08", rec.getMonth());
        assertEquals(new BigDecimal("800000"), rec.getKpiSales());
        assertEquals(new BigDecimal("800000"), rec.getInvoicedSubtotal());
        assertEquals(0, rec.getDifference().signum(), "全社KPI売上と当月請求(税抜)は一致すべき");

        // 発生は8月でも入金予定は支払期限の9月（税込）に立つ、という時間差がCFの本質。
        assertEquals(0, result.getMonths().get(0).getInflow().signum());
        assertEquals(new BigDecimal("880000"), result.getMonths().get(1).getInflow());
    }

    /** 給与は総支給ベース＋事業主負担率の上乗せで計上すること。 */
    @Test
    void testPayrollUsesGrossPlusEmployerBurden() {
        when(systemConfigService.getDecimal("cashflow.opening-balance", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(systemConfigService.getDecimal("cashflow.fixed-cost", BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        when(systemConfigService.getDecimal("cashflow.alert-threshold", BigDecimal.ZERO)).thenReturn(new BigDecimal("-99999999"));
        when(systemConfigService.getInt("cashflow.bp-payment-site-months", 1)).thenReturn(1);
        when(systemConfigService.getDecimal("cashflow.payroll-employer-burden-rate", BigDecimal.ZERO)).thenReturn(new BigDecimal("15"));
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        when(bpPaymentMapper.selectListWithDetails(any(), any())).thenReturn(List.of());

        when(freeeIntegrationService.connected()).thenReturn(true);
        PayrollStatementDto statement = new PayrollStatementDto();
        statement.setGrossAmount(new BigDecimal("1000000"));
        statement.setNetAmount(new BigDecimal("800000")); // 手取りではなく総支給を使うこと
        when(freeeIntegrationService.statements(anyInt(), anyInt(), eq("salary"))).thenReturn(List.of(statement));

        CashFlowForecastDto result = service.forecast(YearMonth.of(2026, 8), 1, null);

        // 1,000,000 + 15% = 1,150,000
        assertEquals(new BigDecimal("1150000"), result.getMonths().get(0).getPayrollTotal());
    }
}
