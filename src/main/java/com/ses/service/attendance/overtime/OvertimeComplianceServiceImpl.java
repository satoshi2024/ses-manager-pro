package com.ses.service.attendance.overtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.attendance.overtime.OvertimeAgreementSnapshot;
import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;
import com.ses.dto.attendance.overtime.OvertimeComplianceInput;
import com.ses.dto.attendance.overtime.OvertimeComplianceSeverity;
import com.ses.dto.attendance.overtime.OvertimeMonthMinutes;
import com.ses.dto.attendance.overtime.OvertimeRule;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OvertimeAgreement;
import com.ses.entity.OvertimeFollowup;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OvertimeFollowupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 月次締め／schedulerから36協定判定を起動し、followup永続化＋段階通知する実装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OvertimeComplianceServiceImpl implements OvertimeComplianceService {

    private static final String STATUS_OPEN = "未対応";
    private static final String APPROVED = "承認済";
    private static final String CLOSED = "締め済";
    private static final String LINK = "/work-record/attendance";
    private static final String MENU_KEY = "work-record";

    private final OvertimeComplianceCalculator calculator;
    private final OvertimeAgreementResolver agreementResolver;
    private final AttendanceMonthMapper attendanceMonthMapper;
    private final EngineerMapper engineerMapper;
    private final OvertimeFollowupMapper overtimeFollowupMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final ObjectProvider<OvertimeComplianceService> selfProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<OvertimeComplianceFinding> evaluateAndPersist(Long engineerId, YearMonth targetMonth) {
        Objects.requireNonNull(engineerId, "engineerId");
        Objects.requireNonNull(targetMonth, "targetMonth");

        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            return List.of();
        }

        AttendanceMonth currentRow = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .eq(AttendanceMonth::getWorkMonth, targetMonth.atDay(1))
                .last("LIMIT 1"));
        if (currentRow == null) {
            return List.of();
        }

        Long legalEntityId = currentRow.getLegalEntityId();
        OvertimeAgreement agreementEntity = agreementResolver.findActive(legalEntityId, targetMonth);
        OvertimeAgreementSnapshot agreement = OvertimeAgreementSnapshot.from(agreementEntity);

        OvertimeMonthMinutes current = toMinutes(targetMonth, currentRow);
        List<OvertimeMonthMinutes> yearMonths = buildAgreementYearMonths(
                engineerId, targetMonth, agreementEntity);
        List<OvertimeMonthMinutes> rolling = buildRollingWindow(engineerId, targetMonth);

        Boolean exemption = toExemption(engineer.getOvertimeExemptFlag());
        OvertimeComplianceInput input = new OvertimeComplianceInput(
                legalEntityId, targetMonth, exemption, current, yearMonths, rolling, agreement);

        List<OvertimeComplianceFinding> findings = calculator.evaluate(input);
        for (OvertimeComplianceFinding finding : findings) {
            upsertFollowup(engineerId, targetMonth, finding);
        }

        if (!findings.isEmpty()) {
            OvertimeComplianceService self = selfProvider.getIfAvailable();
            if (self != null) {
                self.notifyFindingsAsync(engineerId, targetMonth, findings);
            } else {
                notifyFindingsSync(engineerId, targetMonth, findings);
            }
        }
        return findings;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int evaluateApprovedOrClosedMonths(YearMonth targetMonth) {
        Objects.requireNonNull(targetMonth, "targetMonth");
        List<AttendanceMonth> months = attendanceMonthMapper.selectList(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getWorkMonth, targetMonth.atDay(1))
                .in(AttendanceMonth::getStatus, List.of(APPROVED, CLOSED))
                .orderByAsc(AttendanceMonth::getEngineerId));
        int count = 0;
        for (AttendanceMonth month : months) {
            evaluateAndPersist(month.getEngineerId(), targetMonth);
            count++;
        }
        return count;
    }

    @Override
    @Async("taskExecutor")
    public void notifyFindingsAsync(Long engineerId, YearMonth targetMonth,
                                    List<OvertimeComplianceFinding> findings) {
        notifyFindingsSync(engineerId, targetMonth, findings);
    }

    private void notifyFindingsSync(Long engineerId, YearMonth targetMonth,
                                    List<OvertimeComplianceFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        for (OvertimeComplianceFinding finding : findings) {
            Set<Long> recipients = resolveRecipients(engineerId, targetMonth, finding);
            String warningCode = warningCode(finding);
            String dedupeKey = "OVERTIME_COMPLIANCE:" + engineerId + ":" + targetMonth + ":" + warningCode;
            String detail = finding.rule().name()
                    + (finding.severity() == OvertimeComplianceSeverity.INDETERMINATE ? "(判定不能)" : "(超過)")
                    + (finding.actualMinutes() == null ? "" : " actual=" + finding.actualMinutes())
                    + (finding.limitMinutes() == null ? "" : " limit=" + finding.limitMinutes());
            String message = "[\"notification.msg.FOLLOW_UP\", \"" + escapeJson(detail) + "\"]";
            for (Long userId : recipients) {
                notificationService.publishToUser(userId, "OVERTIME_COMPLIANCE",
                        "時間外コンプライアンス", message, LINK, dedupeKey, MENU_KEY);
            }
            markNotified(engineerId, targetMonth, warningCode);
        }
    }

    private Set<Long> resolveRecipients(Long engineerId, YearMonth targetMonth,
                                        OvertimeComplianceFinding finding) {
        Set<Long> recipients = new LinkedHashSet<>();
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        if (link != null && link.getSysUserId() != null) {
            recipients.add(link.getSysUserId());
        }

        boolean violation = finding.severity() == OvertimeComplianceSeverity.VIOLATION;
        boolean rule5 = finding.rule() == OvertimeRule.RULE5_MULTI_MONTH_AVERAGE;
        boolean indeterminate = finding.severity() == OvertimeComplianceSeverity.INDETERMINATE;

        if (violation) {
            Long managerId = resolveManagerUserId(link == null ? null : link.getSysUserId(), targetMonth);
            if (managerId != null) {
                recipients.add(managerId);
            }
        }
        // 複数月平均超過 / 判定不能 / 本人宛先が無い超過 → HR・管理者へ（運用フォールバック含む）
        if (rule5 || indeterminate || (violation && recipients.isEmpty())) {
            for (SysUser hr : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getRole, "管理者", "HR")
                    .eq(SysUser::getStatus, 1))) {
                recipients.add(hr.getId());
            }
        }
        return recipients;
    }

    private Long resolveManagerUserId(Long applicantUserId, YearMonth targetMonth) {
        if (applicantUserId == null) {
            return null;
        }
        LocalDate asOf = targetMonth.atEndOfMonth();
        List<UserOrganization> rows = userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>()
                        .eq(UserOrganization::getUserId, applicantUserId)
                        .eq(UserOrganization::getPrimaryFlag, 1)
                        .le(UserOrganization::getValidFrom, asOf)
                        .and(w -> w.isNull(UserOrganization::getValidTo)
                                .or().ge(UserOrganization::getValidTo, asOf)));
        return rows.stream()
                .map(UserOrganization::getManagerUserId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void upsertFollowup(Long engineerId, YearMonth targetMonth, OvertimeComplianceFinding finding) {
        String code = warningCode(finding);
        OvertimeFollowup existing = overtimeFollowupMapper.selectOne(new LambdaQueryWrapper<OvertimeFollowup>()
                .eq(OvertimeFollowup::getEngineerId, engineerId)
                .eq(OvertimeFollowup::getPeriodMonth, targetMonth.atDay(1))
                .eq(OvertimeFollowup::getWarningCode, code)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        try {
            overtimeFollowupMapper.insert(OvertimeFollowup.builder()
                    .engineerId(engineerId)
                    .periodMonth(targetMonth.atDay(1))
                    .warningCode(code)
                    .status(STATUS_OPEN)
                    .build());
        } catch (DuplicateKeyException duplicate) {
            // UNIQUE(engineer_id, period_month, warning_code) — 冪等
        }
    }

    private void markNotified(Long engineerId, YearMonth targetMonth, String warningCode) {
        OvertimeFollowup existing = overtimeFollowupMapper.selectOne(new LambdaQueryWrapper<OvertimeFollowup>()
                .eq(OvertimeFollowup::getEngineerId, engineerId)
                .eq(OvertimeFollowup::getPeriodMonth, targetMonth.atDay(1))
                .eq(OvertimeFollowup::getWarningCode, warningCode)
                .last("LIMIT 1"));
        if (existing == null || existing.getNotifiedAt() != null) {
            return;
        }
        existing.setNotifiedAt(LocalDateTime.now());
        overtimeFollowupMapper.updateById(existing);
    }

    private List<OvertimeMonthMinutes> buildAgreementYearMonths(Long engineerId, YearMonth target,
                                                                OvertimeAgreement agreement) {
        if (agreement == null || agreement.getValidFrom() == null) {
            // 協定なしでもcurrent月だけ渡せばAGREEMENT_MISSINGになる（HISTORY不足を避ける）
            AttendanceMonth current = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                    .eq(AttendanceMonth::getEngineerId, engineerId)
                    .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                    .last("LIMIT 1"));
            return current == null ? List.of() : List.of(toMinutes(target, current));
        }
        YearMonth yearStart = agreementYearStart(YearMonth.from(agreement.getValidFrom()), target);
        LocalDate from = yearStart.atDay(1);
        LocalDate to = target.atDay(1);
        List<AttendanceMonth> rows = attendanceMonthMapper.selectList(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .ge(AttendanceMonth::getWorkMonth, from)
                .le(AttendanceMonth::getWorkMonth, to)
                .orderByAsc(AttendanceMonth::getWorkMonth));
        return rows.stream()
                .map(row -> toMinutes(YearMonth.from(row.getWorkMonth()), row))
                .collect(Collectors.toList());
    }

    private List<OvertimeMonthMinutes> buildRollingWindow(Long engineerId, YearMonth target) {
        LocalDate from = target.minusMonths(5).atDay(1);
        LocalDate to = target.atDay(1);
        List<AttendanceMonth> rows = attendanceMonthMapper.selectList(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .ge(AttendanceMonth::getWorkMonth, from)
                .le(AttendanceMonth::getWorkMonth, to)
                .orderByAsc(AttendanceMonth::getWorkMonth));
        Map<YearMonth, AttendanceMonth> byMonth = rows.stream()
                .collect(Collectors.toMap(r -> YearMonth.from(r.getWorkMonth()), r -> r, (a, b) -> a));

        // 対象月から遡り、欠けた月で打ち切る（連続suffixのみ。不足月を0埋めしない）
        List<OvertimeMonthMinutes> window = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth month = target.minusMonths(i);
            AttendanceMonth row = byMonth.get(month);
            if (row == null) {
                break;
            }
            window.add(0, toMinutes(month, row));
        }
        return window;
    }

    /**
     * valid_from月を起算とする12か月ブロックのうち、対象月が属する年度の開始月。
     */
    static YearMonth agreementYearStart(YearMonth agreementValidFrom, YearMonth target) {
        long monthsBetween = ChronoUnit.MONTHS.between(agreementValidFrom, target);
        if (monthsBetween < 0) {
            return agreementValidFrom;
        }
        long yearIndex = monthsBetween / 12;
        return agreementValidFrom.plusMonths(yearIndex * 12);
    }

    private static OvertimeMonthMinutes toMinutes(YearMonth month, AttendanceMonth row) {
        int overtime = value(row.getOvertimeMinutes());
        int holiday = value(row.getHolidayMinutes());
        return new OvertimeMonthMinutes(month, overtime, overtime + holiday);
    }

    private static Boolean toExemption(Integer flag) {
        if (flag == null) {
            return null;
        }
        return Integer.valueOf(1).equals(flag);
    }

    private static String warningCode(OvertimeComplianceFinding finding) {
        if (finding.windowMonths() != null) {
            return finding.rule().name() + "_N" + finding.windowMonths();
        }
        return finding.rule().name();
    }

    private static int value(Integer v) {
        return v == null ? 0 : v;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
