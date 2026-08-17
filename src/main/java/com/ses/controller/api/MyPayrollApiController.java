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

    private final EngineerAccountLinkService linkService;
    private final FreeeIntegrationService freeeService;
    private final FreeeEmployeeLinkMapper freeeEmployeeLinkMapper;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

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
            // freee従業員linkが無い場合は「未連携」として返す。金額0の明細を作らない（design §6.1）。
            resp.put("linked", false);
            resp.put("statements", List.of());
            return noStore(ApiResult.success(resp));
        }
        // freeeService.statements(year, month, type) から取得したリストに対し、
        // コントローラ層で確実に本人の engineerId のみ抽出・二重防御する（他要員やnullのデータは一切漏洩しない）。
        List<PayrollStatementDto> all = fetchStatements(engineerId, year, month, type);
        resp.put("linked", true);
        resp.put("statements", all.stream()
                .filter(s -> s != null && engineerId.equals(s.getEngineerId()))
                .map(this::toSummary)
                .toList());
        return noStore(ApiResult.success(resp));
    }

    @GetMapping("/statement")
    public ResponseEntity<ApiResult<?>> statement(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue = "salary") String type,
            HttpSession session) {
        Long engineerId = currentEngineerId();
        validatePeriod(year, month, type);
        // 金額を返す前に再認証状態を必ず検証する（未実施・期限切れ・break-glassは403、R2.2）。
        if (!reauthValid(session)) {
            throw BusinessException.of(403, "error.my.payroll.reauthRequired");
        }
        // freee従業員linkが無い要員は明細が存在しない（providerを呼ばず404）。
        if (!hasFreeeLink(engineerId)) {
            throw BusinessException.of(404, "error.my.payroll.notFound");
        }
        PayrollStatementDto mine = fetchStatements(engineerId, year, month, type).stream()
                .filter(s -> s != null && engineerId.equals(s.getEngineerId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.of(404, "error.my.payroll.notFound"));
        return noStore(ApiResult.success(mine));
    }

    @PostMapping("/reauthenticate")
    public ApiResult<Map<String, Object>> reauthenticate(@RequestBody ReauthRequest req, HttpSession session) {
        // 本人scopeが確立できない要員へ再認証時刻を与えない（未紐付けは403）。
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
    private List<PayrollStatementDto> fetchStatements(Long engineerId, int year, int month, String type) {
        try {
            return freeeService.statements(year, month, type);
        } catch (RuntimeException e) {
            log.warn("本人給与明細の取得に失敗しました: engineerId={}, year={}, month={}, type={}",
                    engineerId, year, month, type, e);
            throw BusinessException.of(503, "error.my.payroll.unavailable");
        }
    }

    /** freee従業員linkの有無（soft delete済みはglobal logic deleteで除外される）。 */
    private boolean hasFreeeLink(Long engineerId) {
        return freeeEmployeeLinkMapper.selectCount(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getEngineerId, engineerId)) > 0;
    }

    /** 再認証済みかつ10分以内かつbreak-glassでない場合だけtrue。 */
    private boolean reauthValid(HttpSession session) {
        if (session == null) {
            return false;
        }
        // break-glass（非常時管理者session）はMFA相当の本人検証を経ていないため詳細を拒否する。
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

    /** 一覧用の非機微summary。金額フィールドは一切載せない（R2.2）。 */
    private Map<String, Object> toSummary(PayrollStatementDto s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.getType());
        m.put("month", s.getMonth());
        m.put("payDate", s.getPayDate());
        m.put("calculationStatus", s.getCalculationStatus());
        return m;
    }

    private ResponseEntity<ApiResult<?>> noStore(ApiResult<?> body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }

    /** 再認証リクエスト（body: {"password": "..."}）。 */
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
