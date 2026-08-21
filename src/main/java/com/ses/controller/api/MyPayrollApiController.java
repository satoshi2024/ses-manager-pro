package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.entity.SysUser;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AuditLogService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.security.BreakGlassService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 要員本人の給与・賞与明細API（engineer-self-service-portal-v2 A2 / R2.1〜R2.3）。
 *
 * <ul>
 *   <li>本人scopeはengineer-account linkから解決し、リクエストにengineerIdを受け取らない（design §3）</li>
 *   <li>一覧は金額を一切返さず、詳細はsessionの再認証（payrollReauthAt）＋非break-glassのみ金額を返す（R2.2）</li>
 *   <li>全GETはCache-Control: no-store（FreeePayrollApiControllerと同様）</li>
 *   <li>freee従業員linkが無い要員は「未連携」として返す。0円と表示しない（design §6.1）</li>
 *   <li>金額GET（/statement）はHFP-01 R09どおり固定URIで監査する（成否1 row、金額・氏名・IDを載せない）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/my/payroll")
@RequiredArgsConstructor
public class MyPayrollApiController {

    /** 再認証完了時刻（epoch millis）を保持するsession attribute。 */
    static final String SESSION_REAUTH_AT = "payrollReauthAt";
    /** 再認証の有効時間（分）。 */
    static final int REAUTH_WINDOW_MINUTES = 10;

    private static final String URI_STATEMENT = "/api/my/payroll/statement";

    private final EngineerAccountLinkService linkService;
    private final FreeeIntegrationService freeeService;
    private final FreeeEmployeeLinkMapper freeeEmployeeLinkMapper;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    /** 本人のengineerIdをlinkから解決する（未紐付けは403。MyTimesheetApiControllerと同じ）。 */
    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    @GetMapping("/statements")
    public ResponseEntity<ApiResult<?>> statements(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue = "salary") String type) {
        Long engineerId = currentEngineerId();
        validatePeriod(year, month, type);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!hasFreeeLink(engineerId)) {
            resp.put("linked", false);
            resp.put("statements", List.of());
            return noStore(ApiResult.success(resp));
        }
        PayrollStatementDto mine = fetchStatement(engineerId, year, month, type);
        resp.put("linked", true);
        if (mine != null) {
            resp.put("statements", List.of(toSummary(mine)));
        } else {
            resp.put("statements", List.of());
        }
        return noStore(ApiResult.success(resp));
    }

    @GetMapping("/statement")
    public ResponseEntity<ApiResult<?>> statement(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue = "salary") String type,
            HttpSession session) {
        // 機微GET: 監査記録が成功した場合だけ金額dataを返す。年月/typeはcodeへ、URIは固定。
        String code = ("bonus".equals(type) ? "MY_PAYROLL_BONUS_VIEW_" : "MY_PAYROLL_SALARY_VIEW_")
                + String.format("%04d%02d", year, month);
        try {
            Long engineerId = currentEngineerId();
            validatePeriod(year, month, type);
            if (!reauthValid(session)) {
                throw BusinessException.of(403, "error.my.payroll.reauthRequired");
            }
            if (!hasFreeeLink(engineerId)) {
                throw BusinessException.of(404, "error.my.payroll.notFound");
            }
            PayrollStatementDto mine = fetchStatement(engineerId, year, month, type);
            if (mine == null) {
                throw BusinessException.of(404, "error.my.payroll.notFound");
            }
            audit("GET", code, URI_STATEMENT, true, 200);
            return noStore(ApiResult.success(mine));
        } catch (BusinessException e) {
            audit("GET", code, URI_STATEMENT, false, e.getCode());
            throw e;
        } catch (Exception e) {
            audit("GET", code, URI_STATEMENT, false, 500);
            throw e;
        }
    }

    @PostMapping("/reauthenticate")
    public ApiResult<Map<String, Object>> reauthenticate(@RequestBody ReauthRequest req, HttpSession session) {
        currentEngineerId();
        Long userId = SecurityUtils.currentUserId();
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        String input = (req == null || req.getPassword() == null) ? "" : req.getPassword();
        if (user == null || user.getPassword() == null || !passwordEncoder.matches(input, user.getPassword())) {
            throw BusinessException.of(400, "error.my.payroll.badPassword");
        }
        session.setAttribute(SESSION_REAUTH_AT, System.currentTimeMillis());
        return ApiResult.success(Map.of("expiresInMinutes", REAUTH_WINDOW_MINUTES));
    }

    /** provider障害・接続状態不良は503系（body code=503）へ変換し、画面を壊さない。 */
    private PayrollStatementDto fetchStatement(Long engineerId, int year, int month, String type) {
        try {
            return freeeService.statementForEngineer(engineerId, year, month, type);
        } catch (RuntimeException e) {
            // engineerId・金額・氏名はログへ載せない（HFP-01-BUG-06 / R09）
            log.warn("本人給与明細の取得に失敗しました: year={}, month={}, type={}",
                    year, month, type, e);
            throw BusinessException.of(503, "error.my.payroll.unavailable");
        }
    }

    private boolean hasFreeeLink(Long engineerId) {
        return freeeEmployeeLinkMapper.selectCount(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getEngineerId, engineerId)) > 0;
    }

    private boolean reauthValid(HttpSession session) {
        if (session == null) {
            return false;
        }
        if (session.getAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE) != null) {
            return false;
        }
        Object value = session.getAttribute(SESSION_REAUTH_AT);
        if (!(value instanceof Long reauthAt)) {
            return false;
        }
        return System.currentTimeMillis() - reauthAt <= TimeUnit.MINUTES.toMillis(REAUTH_WINDOW_MINUTES);
    }

    private void validatePeriod(int year, int month, String type) {
        if (year < 2000 || month < 1 || month > 12) {
            throw BusinessException.of(400, "error.my.payroll.invalidPeriod");
        }
        if (!"salary".equals(type) && !"bonus".equals(type)) {
            throw BusinessException.of(400, "error.payroll.invalidType");
        }
    }

    private Map<String, Object> toSummary(PayrollStatementDto s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.getType());
        m.put("month", s.getMonth());
        m.put("payDate", s.getPayDate());
        m.put("calculationStatus", s.getCalculationStatus());
        return m;
    }

    private void audit(String method, String applicationCode, String fixedUri, boolean success, int status) {
        String username = SecurityUtils.currentUsername();
        if (success) {
            auditLogService.recordRequired(username, method, fixedUri, status, applicationCode, true);
        } else {
            auditLogService.record(username, method, fixedUri, status, applicationCode, false);
        }
    }

    private ResponseEntity<ApiResult<?>> noStore(ApiResult<?> body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }

    public static class ReauthRequest {
        private String password;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
