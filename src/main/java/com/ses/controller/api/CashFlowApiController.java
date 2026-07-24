package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.service.billing.CashFlowForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/cashflow")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','マネージャー')")
public class CashFlowApiController {

    private final CashFlowForecastService cashFlowForecastService;

    @GetMapping("/forecast")
    public ApiResult<CashFlowForecastDto> forecast(
            @RequestParam(required = false) String from,
            @RequestParam(defaultValue = "6") int months,
            @RequestParam(required = false) BigDecimal openingBalance) {
        
        months = Math.max(1, Math.min(months, 36));
        YearMonth fromMonth = from != null ? YearMonth.parse(from) : YearMonth.now();
        CashFlowForecastDto result = cashFlowForecastService.forecast(fromMonth, months, openingBalance);
        return ApiResult.success(result);
    }

    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String from,
            @RequestParam(defaultValue = "6") int months,
            @RequestParam(required = false) BigDecimal openingBalance,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        
        months = Math.max(1, Math.min(months, 36));
        YearMonth fromMonth = from != null ? YearMonth.parse(from) : YearMonth.now();
        CashFlowForecastDto result = cashFlowForecastService.forecast(fromMonth, months, openingBalance);
        
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"cashflow_forecast.csv\"");
        
        java.io.PrintWriter writer = response.getWriter();
        // Add BOM for Excel
        writer.write('\uFEFF');
        writer.println("年月,入金予定,支払予定(BP),支払予定(給与),支払予定(固定費),支払予定(計),ネットキャッシュ,残高見込み");
        for (CashFlowForecastDto.CashFlowMonthDto m : result.getMonths()) {
            writer.printf("%s,%s,%s,%s,%s,%s,%s,%s\n",
                    m.getMonth(),
                    m.getInflow().toPlainString(),
                    m.getBpPaymentTotal().toPlainString(),
                    m.getPayrollTotal().toPlainString(),
                    m.getFixedCost().toPlainString(),
                    m.getOutflow().toPlainString(),
                    m.getNet().toPlainString(),
                    m.getBalance().toPlainString());
        }

        // 起点月の売上口径突合（全社KPIとの照合用）を末尾に付記する。
        CashFlowForecastDto.ReconciliationDto rec = result.getReconciliation();
        if (rec != null) {
            writer.println();
            writer.println("口径突合(起点月),全社KPI売上(税抜),当月請求(税抜),差分");
            writer.printf("%s,%s,%s,%s%n",
                    rec.getMonth(),
                    rec.getKpiSales().toPlainString(),
                    rec.getInvoicedSubtotal().toPlainString(),
                    rec.getDifference().toPlainString());
        }
        writer.flush();
    }
}
