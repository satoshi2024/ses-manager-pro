package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.payroll.FreeeConnectionStatusDto;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollEngineerCandidateDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.service.AuditLogService;
import com.ses.service.FreeeIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.List;

/**
 * freee給与API。HFP-01-R08/R09:
 * <ul>
 *   <li>全GETはno-store（給与・従業員・接続情報をcacheさせない）</li>
 *   <li>機微GET（従業員一覧・給与・賞与）とlink/unlinkは、監査記録が成功した場合だけdataを返す。
 *       監査URIは固定（employee/company IDを載せない）、年月/typeはapplicationCodeへencode（design §12.3）</li>
 *   <li>ApiAuditFilterは /api/payroll/** を除外済み。1 request = 1 audit row</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','HR')")
public class FreeePayrollApiController {

    private final FreeeIntegrationService service;
    private final MessageSource messageSource;
    private final AuditLogService auditLogService;

    private static final String URI_STATUS = "/api/payroll/status";
    private static final String URI_EMPLOYEES = "/api/payroll/employees";
    private static final String URI_STATEMENTS = "/api/payroll/statements";
    private static final String URI_LINKS = "/api/payroll/links";
    private static final String CODE_EMPLOYEE_VIEW = "PAYROLL_EMPLOYEE_VIEW";
    private static final String CODE_LINK = "PAYROLL_LINK";
    private static final String CODE_UNLINK = "PAYROLL_UNLINK";

    @GetMapping("/status")
    public ResponseEntity<ApiResult<FreeeConnectionStatusDto>> status(Locale locale) {
        FreeeConnectionStatusDto dto = service.connectionStatus();
        dto.setAction(messageSource.getMessage(dto.getAction(), null, dto.getAction(), locale));
        return noStore(ApiResult.success(dto));
    }
    
    @GetMapping("/employees")
    public ResponseEntity<ApiResult<List<FreeeEmployeeDto>>> employees() {
        List<FreeeEmployeeDto> data = service.employees();
        audit("GET", CODE_EMPLOYEE_VIEW, URI_EMPLOYEES);
        return noStore(ApiResult.success(data));
    }

    @GetMapping("/engineer-candidates")
    public ResponseEntity<ApiResult<List<PayrollEngineerCandidateDto>>> engineerCandidates() {
        return noStore(ApiResult.success(service.engineerCandidates()));
    }
    
    @PutMapping("/links/{engineerId}")
    public ResponseEntity<ApiResult<Boolean>> link(
            @PathVariable Long engineerId,
            @RequestParam String employeeId) {
        service.link(engineerId, employeeId, SecurityUtils.currentUserId());
        audit("PUT", CODE_LINK, URI_LINKS);
        return noStore(ApiResult.success(true));
    }
    
    @DeleteMapping("/links/{engineerId}")
    public ResponseEntity<ApiResult<Boolean>> unlink(@PathVariable Long engineerId) {
        service.unlink(engineerId);
        audit("DELETE", CODE_UNLINK, URI_LINKS);
        return noStore(ApiResult.success(true));
    }
    
    @GetMapping("/statements")
    public ResponseEntity<ApiResult<List<PayrollStatementDto>>> statements(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue="salary") String type) {
        List<PayrollStatementDto> data = service.statements(year, month, type);
        // 機微GET: 監査記録が成功した場合だけdataを返す。年月/typeはcodeへ、URIは固定。
        String code = ("bonus".equals(type) ? "PAYROLL_BONUS_VIEW_" : "PAYROLL_SALARY_VIEW_")
                + String.format("%04d%02d", year, month);
        audit("GET", code, URI_STATEMENTS);
        return noStore(ApiResult.success(data));
    }

    private void audit(String method, String applicationCode, String fixedUri) {
        auditLogService.recordRequired(SecurityUtils.currentUsername(), method, fixedUri, 200,
                applicationCode, true);
    }

    private <T> ResponseEntity<ApiResult<T>> noStore(ApiResult<T> body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }
}
