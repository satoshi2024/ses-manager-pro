package com.ses.service.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.CsvUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.AttendanceSyncResultDto;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.Engineer;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.entity.OvertimeFollowup;
import com.ses.entity.SysUser;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.mapper.OvertimeFollowupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 雇用勤怠の外部provider同期（S11 B1）。
 *
 * <p>G6決定により本システムを正とし、外部データはread-only照合に使う。DBへの書込みは
 * 承認/締め済みデータの外部送信（push）を除いて行わない。締め済み・承認済み月への
 * 外部更新は拒否し、t_overtime_followupへwarning_code='EXT_OVERWRITE_REJECTED'で
 * findingを記録してHR/管理者へ通知する（黙って上書きも、黙って無視もしない）。</p>
 *
 * <p>cursorと直近の実行結果はm_system_configのJSONキー（SYSTEM_MANAGED）へ保存する。
 * timezoneは{@code attendance.sync.timezone}（既定Asia/Tokyo）をtenant設定として読む。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceSyncServiceImpl implements AttendanceSyncService {

    static final String APPROVED = "承認済";
    static final String CLOSED = "締め済";
    /** 外部上書き拒否のfinding warning_code（t_overtime_followup）。 */
    static final String WARNING_CODE_EXT_OVERWRITE_REJECTED = "EXT_OVERWRITE_REJECTED";
    static final String CONFIG_PROVIDER = "attendance.sync.provider";
    static final String CONFIG_TIMEZONE = "attendance.sync.timezone";
    static final String CONFIG_CURSOR = "attendance.sync.freee.cursor";
    static final String CONFIG_CURSOR_LE_PREFIX = "attendance.sync.freee.cursor.le.";
    static final String CONFIG_LAST_RESULT = "attendance.sync.last-result";

    private final List<AttendanceProvider> providers;
    private final AttendanceMonthMapper attendanceMonthMapper;
    private final EmployeeAttendanceMapper employeeAttendanceMapper;
    private final EngineerMapper engineerMapper;
    private final FreeeEmployeeLinkMapper freeeEmployeeLinkMapper;
    private final OvertimeFollowupMapper overtimeFollowupMapper;
    private final SysUserMapper sysUserMapper;
    private final AttendanceScopeResolver attendanceScopeResolver;
    private final OrganizationScopeService organizationScopeService;
    private final com.ses.mapper.OrganizationUnitMapper organizationUnitMapper;
    private final SystemConfigService systemConfigService;
    private final com.ses.mapper.SystemConfigMapper systemConfigMapper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** CSV出力の最大行数（黙って切り捨てず、超過時は BusinessException）。 */
    @Value("${app.export.max-rows:50000}")
    private int configuredMaxRows;

    @Override
    public AttendanceSyncResultDto syncPush(String month) {
        YearMonth target = parseMonth(month);
        AttendanceProvider provider = resolveProvider();
        AttendanceSyncResultDto result = newResult(provider.source(), "push", target);
        List<AttendanceMonth> months = monthsForScope(target);
        for (AttendanceMonth monthRow : months) {
            if (!APPROVED.equals(monthRow.getStatus()) && !CLOSED.equals(monthRow.getStatus())) {
                continue;
            }
            try {
                AttendanceMonthlyPayload payload = buildPayload(monthRow, target);
                String idempotencyKey = payloadHash(payload);
                boolean accepted = provider.pushMonthly(payload, idempotencyKey, result.getCorrelationId());
                if (accepted) {
                    result.setPushedCount(value(result.getPushedCount()) + 1);
                } else {
                    result.setDuplicateSkippedCount(value(result.getDuplicateSkippedCount()) + 1);
                }
            } catch (Exception e) {
                result.getErrors().add("engineer=" + monthRow.getEngineerId() + ": " + safeMessage(e));
            }
        }
        finish(result);
        return result;
    }

    @Override
    public AttendanceSyncResultDto syncPull(String month) {
        YearMonth target = parseMonth(month);
        AttendanceProvider provider = resolveProvider();
        AttendanceSyncResultDto result = newResult(provider.source(), "pull", target);
        // R5-P1-01: caller scopeを解決する（管理者=全件(null)、HR=担当法人の要員、マネージャー=組織scope）。
        // R5-P2-03: cursorはlegal entity別（attendance.sync.freee.cursor.le.<id>）に保持し、
        // fetchは対象法人のcursorの最小値で行い、cursor前進は処理したレコードの法人ごとに行う。
        // これにより、法人A担当HRが先にpullしても法人Bのcursorは進まず、法人Bの後続pullで
        // 締め済み拒否・照合が漏れなく実行される（黙って無視しない）。
        Set<Long> scopedEngineerIds = pullScopeEngineerIds(target);
        Set<Long> scopeLegalEntityIds = pullScopeLegalEntityIds(target);
        try {
            // 対象法人のcursorを読み、最小値をfetch起点にする（管理者=全法人、HR=担当法人、マネージャー=組織scope要員の法人）
            Map<Long, String> cursorByLegalEntity = new HashMap<>();
            String fetchCursor = null;
            for (Long legalEntityId : scopeLegalEntityIds) {
                String c = readCursor(legalEntityId);
                cursorByLegalEntity.put(legalEntityId, c);
                if (c != null && (fetchCursor == null || c.compareTo(fetchCursor) < 0)) {
                    fetchCursor = c;
                }
            }
            List<ExternalAttendanceRecord> records = provider.fetchUpdatedSince(fetchCursor);
            result.setPulledCount(records.size());
            // R5-P2-01: cursorはtenant timezone（attendance.sync.timezone）で正規化して比較・保存する
            ZoneId zone = tenantZone();
            Map<Long, Map<LocalDate, EmployeeAttendance>> internalByEngineer = new HashMap<>();
            Map<Long, String> nextCursorByLegalEntity = new HashMap<>(cursorByLegalEntity);
            for (ExternalAttendanceRecord record : records) {
                if (record.getWorkDate() == null || !YearMonth.from(record.getWorkDate()).equals(target)) {
                    continue;
                }
                if (record.getEngineerId() == null) {
                    resolveEngineer(record);
                }
                // R5-P1-01: 要員に解決できない外部レコード、およびcaller scope外のレコードは
                // 処理も照合も行わない（他法人要員のPII・finding書込を防ぐ）。
                if (record.getEngineerId() == null
                        || (scopedEngineerIds != null && !scopedEngineerIds.contains(record.getEngineerId()))) {
                    continue;
                }
                // R5-P2-03: レコードの所属法人を解決し、caller scope外の法人ならskip（cursorも進めない）
                Long legalEntityId = legalEntityIdOf(record, target);
                if (legalEntityId == null || !scopeLegalEntityIds.contains(legalEntityId)) {
                    continue;
                }
                String normalizedUpdated = normalizeUpdatedAt(record.getUpdatedAt(), zone);
                // その法人のcursor以前のレコードは既に処理済み（前回分）
                String legalEntityCursor = nextCursorByLegalEntity.get(legalEntityId);
                if (normalizedUpdated != null && legalEntityCursor != null
                        && normalizedUpdated.compareTo(legalEntityCursor) <= 0) {
                    continue;
                }
                try {
                    if (isClosedOrApproved(record)) {
                        rejectExternalUpdate(record, target);
                        result.setRejectedCount(value(result.getRejectedCount()) + 1);
                    } else {
                        // R5-P2-02: 外部レコードを本システムの日次と照合（read-only。DB登録はしない）
                        reconcile(record, target, internalByEngineer, result);
                    }
                    // R5-P2-03: 処理したレコードの法人だけcursorを前進させる
                    if (normalizedUpdated != null
                            && (nextCursorByLegalEntity.get(legalEntityId) == null
                            || normalizedUpdated.compareTo(nextCursorByLegalEntity.get(legalEntityId)) > 0)) {
                        nextCursorByLegalEntity.put(legalEntityId, normalizedUpdated);
                    }
                } catch (Exception e) {
                    result.getErrors().add("externalId=" + record.getSourceExternalId() + ": " + safeMessage(e));
                }
            }
            // 法人別cursorを保存（R5-P2-03: 進んだ法人だけ書き込み、他法人は不変）
            for (Map.Entry<Long, String> entry : nextCursorByLegalEntity.entrySet()) {
                String before = cursorByLegalEntity.get(entry.getKey());
                String after = entry.getValue();
                if (!java.util.Objects.equals(before, after)) {
                    saveCursor(entry.getKey(), after);
                }
            }
            result.setCursor(nextCursorByLegalEntity.values().stream()
                    .filter(java.util.Objects::nonNull)
                    .max(String::compareTo).orElse(null));
        } catch (Exception e) {
            result.getErrors().add("pull: " + safeMessage(e));
            result.setSuccess(false);
        }
        finish(result);
        return result;
    }

    /**
     * R5-P2-03: pullのcaller scope法人集合（design §5.3）。
     * 管理者=全法人、HR=担当法人、マネージャー=組織scope要員の属する法人。
     */
    private Set<Long> pullScopeLegalEntityIds(YearMonth target) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return attendanceScopeResolver.allLegalEntityIds();
        }
        if ("HR".equals(role)) {
            Set<Long> ids = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
            return ids == null ? Set.of() : ids;
        }
        if ("マネージャー".equals(role)) {
            Set<Long> engineerIds = organizationScopeService.hasFullAccess()
                    ? null : organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
            if (engineerIds == null) {
                return attendanceScopeResolver.allLegalEntityIds();
            }
            if (engineerIds.isEmpty()) {
                return Set.of();
            }
            List<AttendanceMonth> months = attendanceMonthMapper.selectList(
                    new LambdaQueryWrapper<AttendanceMonth>()
                            .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                            .in(AttendanceMonth::getEngineerId, engineerIds)
                            .isNotNull(AttendanceMonth::getLegalEntityId));
            Set<Long> legalEntityIds = new HashSet<>();
            for (AttendanceMonth m : months) {
                legalEntityIds.add(m.getLegalEntityId());
            }
            return legalEntityIds;
        }
        throw BusinessException.of(403, "error.attendance.roleDenied");
    }

    /** レコードの所属法人を解決する（対象月のmonth行→要員の現在所属組織→不明）。 */
    private Long legalEntityIdOf(ExternalAttendanceRecord record, YearMonth target) {
        AttendanceMonth month = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, record.getEngineerId())
                .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                .last("LIMIT 1"));
        if (month != null && month.getLegalEntityId() != null) {
            return month.getLegalEntityId();
        }
        Engineer engineer = engineerMapper.selectById(record.getEngineerId());
        if (engineer != null && engineer.getOrganizationId() != null) {
            Long legalEntityId = organizationLegalEntityId(engineer.getOrganizationId());
            if (legalEntityId != null) {
                return legalEntityId;
            }
        }
        return null;
    }

    private Long organizationLegalEntityId(Long organizationId) {
        com.ses.entity.OrganizationUnit org = organizationUnitMapper.selectById(organizationId);
        return org == null ? null : org.getLegalEntityId();
    }

    /**
     * R5-P1-01: pullのcaller scope要員集合（design §5.3）。
     * 管理者=null（全件）、HR=担当法人の要員（対象月末asOf）、マネージャー=組織scope
     * （hasFullAccess先判定＋対象月末asOf）。営業・要員は403。
     */
    private Set<Long> pullScopeEngineerIds(YearMonth target) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return null;
        }
        if ("HR".equals(role)) {
            return attendanceScopeResolver.allowedHrEngineerIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
        }
        if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) {
                return null;
            }
            return organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
        }
        throw BusinessException.of(403, "error.attendance.roleDenied");
    }

    /**
     * R5-P2-02: 外部レコードと本システムの該当日次（source=manual/system）を比較し、
     * 一致/差異/対応なしを結果へ集計する。DBへは登録しない（read-only照合）。
     */
    private void reconcile(ExternalAttendanceRecord record, YearMonth target,
                           Map<Long, Map<LocalDate, EmployeeAttendance>> internalByEngineer,
                           AttendanceSyncResultDto result) {
        if (record.getEngineerId() == null) {
            resolveEngineer(record);
        }
        if (record.getEngineerId() == null || record.getWorkDate() == null) {
            result.setUnmatchedCount(value(result.getUnmatchedCount()) + 1);
            return;
        }
        Map<LocalDate, EmployeeAttendance> days = internalByEngineer.computeIfAbsent(
                record.getEngineerId(), id -> loadInternalDays(id, target));
        EmployeeAttendance internal = days.get(record.getWorkDate());
        String externalValue = externalValue(record);
        if (internal == null) {
            result.setUnmatchedCount(value(result.getUnmatchedCount()) + 1);
            addDifference(result, record, externalValue, "（該当日次なし）");
            return;
        }
        String internalValue = internalValue(internal);
        if (externalValue.equals(internalValue)) {
            result.setMatchedCount(value(result.getMatchedCount()) + 1);
        } else {
            result.setDiffCount(value(result.getDiffCount()) + 1);
            addDifference(result, record, externalValue, internalValue);
        }
    }

    private Map<LocalDate, EmployeeAttendance> loadInternalDays(Long engineerId, YearMonth target) {
        Map<LocalDate, EmployeeAttendance> result = new HashMap<>();
        List<EmployeeAttendance> days = employeeAttendanceMapper.selectList(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, engineerId)
                        .ge(EmployeeAttendance::getWorkDate, target.atDay(1))
                        .le(EmployeeAttendance::getWorkDate, target.atEndOfMonth())
                        .in(EmployeeAttendance::getSource, "manual", "system"));
        for (EmployeeAttendance day : days) {
            result.put(day.getWorkDate(), day);
        }
        return result;
    }

    /** 外部レコードと本システム日次の比較文字列（R5-P2-02）。nullは0として扱う。 */
    private String externalValue(ExternalAttendanceRecord record) {
        return "in=" + time(record.getClockIn()) + " out=" + time(record.getClockOut())
                + " break=" + value(record.getBreakMinutes())
                + " reg=" + value(record.getRegularMinutes())
                + " ot=" + value(record.getOvertimeMinutes())
                + " hol=" + value(record.getHolidayMinutes())
                + " ln=" + value(record.getLateNightMinutes());
    }

    private String internalValue(EmployeeAttendance day) {
        return "in=" + time(day.getClockIn()) + " out=" + time(day.getClockOut())
                + " break=" + value(day.getBreakMinutes())
                + " reg=" + value(day.getRegularMinutes())
                + " ot=" + value(day.getOvertimeMinutes())
                + " hol=" + value(day.getHolidayMinutes())
                + " ln=" + value(day.getLateNightMinutes());
    }

    private String time(java.time.LocalTime t) {
        return t == null ? "" : t.toString();
    }

    private void addDifference(AttendanceSyncResultDto result, ExternalAttendanceRecord record,
                               String externalValue, String internalValue) {
        if (result.getDifferences() == null) {
            result.setDifferences(new ArrayList<>());
        }
        if (result.getDifferences().size() >= 20) {
            return;
        }
        result.getDifferences().add(AttendanceSyncResultDto.ReconciliationItem.builder()
                .sourceExternalId(record.getSourceExternalId())
                .engineerId(record.getEngineerId())
                .workDate(record.getWorkDate() == null ? null : record.getWorkDate().toString())
                .externalValue(externalValue)
                .internalValue(internalValue)
                .build());
    }

    /**
     * R5-P2-01: tenant timezone（attendance.sync.timezone、既定Asia/Tokyo）を返す。
     * design §3「timezoneはAsia/Tokyo固定ではなくtenant設定」の実体。
     * SystemConfigServiceのキャッシュはafterCommit更新のため、同一tx内のreadはmapper直読みにする。
     */
    private ZoneId tenantZone() {
        com.ses.entity.SystemConfig config = systemConfigMapper.selectById(CONFIG_TIMEZONE);
        String configured = config == null || config.getConfigValue() == null
                ? "Asia/Tokyo" : config.getConfigValue();
        try {
            return ZoneId.of(configured);
        } catch (Exception e) {
            log.warn("attendance.sync.timezoneの値が不正なためAsia/Tokyoを使います: {}", configured);
            return ZoneId.of("Asia/Tokyo");
        }
    }

    /**
     * R5-P2-01: 外部updated_at（ISO-8601）をtenant timezoneで正規化したISO文字列へ変換する。
     * zone無し文字列はtenant timezoneで解釈し、zone付きはそのまま正規化する。
     */
    private String normalizeUpdatedAt(String updatedAt, ZoneId zone) {
        if (updatedAt == null || updatedAt.isBlank()) {
            return null;
        }
        try {
            if (updatedAt.endsWith("Z") || updatedAt.indexOf('+') > 10 && updatedAt.contains(":")) {
                return java.time.OffsetDateTime.parse(updatedAt).toInstant().toString();
            }
            return java.time.LocalDateTime.parse(updatedAt).atZone(zone).toInstant().toString();
        } catch (DateTimeParseException e) {
            return updatedAt;
        }
    }

    @Override
    public AttendanceSyncResultDto syncAll(String month) {
        AttendanceSyncResultDto push = syncPush(month);
        AttendanceSyncResultDto pull = syncPull(month);
        AttendanceSyncResultDto combined = newResult(push.getProvider(), "all", parseMonth(month));
        combined.setPushedCount(push.getPushedCount());
        combined.setDuplicateSkippedCount(push.getDuplicateSkippedCount());
        combined.setPulledCount(pull.getPulledCount());
        combined.setRegisteredCount(pull.getRegisteredCount());
        combined.setRejectedCount(pull.getRejectedCount());
        combined.setMatchedCount(pull.getMatchedCount());
        combined.setDiffCount(pull.getDiffCount());
        combined.setUnmatchedCount(pull.getUnmatchedCount());
        combined.setDifferences(pull.getDifferences());
        combined.setCursor(pull.getCursor());
        combined.getErrors().addAll(push.getErrors());
        combined.getErrors().addAll(pull.getErrors());
        combined.setSuccess(push.isSuccess() && pull.isSuccess());
        combined.setFinishedAt(LocalDateTime.now());
        saveLastResult(combined);
        return combined;
    }

    @Override
    public AttendanceSyncResultDto lastResult() {
        // SystemConfigServiceのキャッシュはafterCommit更新のため、同一tx内のreadはmapper直読みにする
        com.ses.entity.SystemConfig config = systemConfigMapper.selectById(CONFIG_LAST_RESULT);
        if (config == null || config.getConfigValue() == null) {
            return AttendanceSyncResultDto.empty();
        }
        try {
            return objectMapper.readValue(config.getConfigValue(), AttendanceSyncResultDto.class);
        } catch (Exception e) {
            log.warn("attendance sync last result is unreadable, returning empty");
            return AttendanceSyncResultDto.empty();
        }
    }

    @Override
    public boolean providerAvailable() {
        return resolveProvider().available();
    }

    @Override
    public String providerSource() {
        return resolveProvider().source();
    }

    @Override
    public void exportCsv(String month, OutputStream out) {
        YearMonth target = parseMonth(month);
        List<AttendanceMonth> months = monthsForScope(target);
        Map<Long, Engineer> engineers = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (AttendanceMonth monthRow : months) {
            if (!APPROVED.equals(monthRow.getStatus()) && !CLOSED.equals(monthRow.getStatus())) {
                continue;
            }
            ids.add(monthRow.getEngineerId());
        }
        if (!ids.isEmpty()) {
            engineerMapper.selectBatchIds(ids).forEach(e -> engineers.put(e.getId(), e));
        }
        List<EmployeeAttendance> days = ids.isEmpty() ? List.of()
                : employeeAttendanceMapper.selectList(new LambdaQueryWrapper<EmployeeAttendance>()
                        .in(EmployeeAttendance::getEngineerId, ids)
                        .ge(EmployeeAttendance::getWorkDate, target.atDay(1))
                        .le(EmployeeAttendance::getWorkDate, target.atEndOfMonth())
                        .orderByAsc(EmployeeAttendance::getEngineerId)
                        .orderByAsc(EmployeeAttendance::getWorkDate));
        int maxRows = configuredMaxRows > 0 ? configuredMaxRows : 50000;
        if (days.size() > maxRows) {
            throw BusinessException.of("error.export.maxRows", maxRows);
        }
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(CsvUtils.UTF8_BOM);
            CsvUtils.appendLine(writer, "要員ID", "要員名", "日付", "出勤", "退勤", "休憩(分)",
                    "法定内(分)", "時間外(分)", "休日(分)", "深夜(分)", "勤務区分");
            for (EmployeeAttendance day : days) {
                Engineer engineer = engineers.get(day.getEngineerId());
                CsvUtils.appendLine(writer,
                        String.valueOf(day.getEngineerId()),
                        engineer == null ? "" : engineer.getFullName(),
                        day.getWorkDate() == null ? "" : day.getWorkDate().toString(),
                        day.getClockIn() == null ? "" : day.getClockIn().toString(),
                        day.getClockOut() == null ? "" : day.getClockOut().toString(),
                        String.valueOf(value(day.getBreakMinutes())),
                        String.valueOf(value(day.getRegularMinutes())),
                        String.valueOf(value(day.getOvertimeMinutes())),
                        String.valueOf(value(day.getHolidayMinutes())),
                        String.valueOf(value(day.getLateNightMinutes())),
                        day.getWorkType() == null ? "" : day.getWorkType());
            }
            writer.flush();
        } catch (IOException e) {
            throw BusinessException.of(500, "error.attendance.sync.csvFailed");
        }
    }

    private boolean isClosedOrApproved(ExternalAttendanceRecord record) {
        if (record.getEngineerId() == null) {
            resolveEngineer(record);
        }
        if (record.getEngineerId() == null || record.getWorkDate() == null) {
            return false;
        }
        AttendanceMonth monthRow = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, record.getEngineerId())
                .eq(AttendanceMonth::getWorkMonth, YearMonth.from(record.getWorkDate()).atDay(1))
                .last("LIMIT 1"));
        return monthRow != null && (APPROVED.equals(monthRow.getStatus()) || CLOSED.equals(monthRow.getStatus()));
    }

    private void resolveEngineer(ExternalAttendanceRecord record) {
        if (record.getExternalEngineerId() == null) {
            return;
        }
        FreeeEmployeeLink link = freeeEmployeeLinkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getFreeeEmployeeId, record.getExternalEngineerId())
                .last("LIMIT 1"));
        if (link != null) {
            record.setEngineerId(link.getEngineerId());
        }
    }

    /**
     * 締め済み・承認済み月への外部更新を拒否し、finding（t_overtime_followup）をUPSERTして
     * HR/管理者へ通知する。「黙って上書きも、黙って無視もしない」（design §5.4 / R1.3）。
     */
    private void rejectExternalUpdate(ExternalAttendanceRecord record, YearMonth target) {
        Long engineerId = record.getEngineerId();
        OvertimeFollowup existing = overtimeFollowupMapper.selectOne(new LambdaQueryWrapper<OvertimeFollowup>()
                .eq(OvertimeFollowup::getEngineerId, engineerId)
                .eq(OvertimeFollowup::getPeriodMonth, target.atDay(1))
                .eq(OvertimeFollowup::getWarningCode, WARNING_CODE_EXT_OVERWRITE_REJECTED)
                .last("LIMIT 1"));
        if (existing == null) {
            try {
                overtimeFollowupMapper.insert(OvertimeFollowup.builder()
                        .engineerId(engineerId)
                        .periodMonth(target.atDay(1))
                        .warningCode(WARNING_CODE_EXT_OVERWRITE_REJECTED)
                        .status("未対応")
                        .notifiedAt(LocalDateTime.now())
                        .build());
            } catch (DuplicateKeyException duplicate) {
                // UNIQUE(engineer_id, period_month, warning_code)競合は冪等として無視
            }
        }
        notifyRejection(engineerId, target);
    }

    private void notifyRejection(Long engineerId, YearMonth target) {
        String dedupeKey = "ATT_SYNC_REJECTED:" + engineerId + ":" + target;
        List<SysUser> recipients = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getRole, "管理者", "HR")
                .eq(SysUser::getStatus, 1));
        String message = "[\"notification.msg.ATT_SYNC_REJECTED\", \"" + engineerId + "\", \""
                + target + "\"]";
        for (SysUser user : recipients) {
            notificationService.publishToUser(user.getId(), "ATT_SYNC_REJECTED",
                    "外部勤怠更新の拒否", message, "/work-record/attendance",
                    dedupeKey, "work-record");
        }
    }

    private AttendanceMonthlyPayload buildPayload(AttendanceMonth monthRow, YearMonth target) {
        Engineer engineer = engineerMapper.selectById(monthRow.getEngineerId());
        List<EmployeeAttendance> days = employeeAttendanceMapper.selectList(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, monthRow.getEngineerId())
                        .ge(EmployeeAttendance::getWorkDate, target.atDay(1))
                        .le(EmployeeAttendance::getWorkDate, target.atEndOfMonth())
                        .orderByAsc(EmployeeAttendance::getWorkDate));
        List<AttendanceMonthlyPayload.Day> payloadDays = new ArrayList<>();
        for (EmployeeAttendance day : days) {
            payloadDays.add(AttendanceMonthlyPayload.Day.builder()
                    .workDate(day.getWorkDate() == null ? null : day.getWorkDate().toString())
                    .clockIn(day.getClockIn() == null ? null : day.getClockIn().toString())
                    .clockOut(day.getClockOut() == null ? null : day.getClockOut().toString())
                    .breakMinutes(value(day.getBreakMinutes()))
                    .regularMinutes(value(day.getRegularMinutes()))
                    .overtimeMinutes(value(day.getOvertimeMinutes()))
                    .holidayMinutes(value(day.getHolidayMinutes()))
                    .lateNightMinutes(value(day.getLateNightMinutes()))
                    .workType(day.getWorkType())
                    .build());
        }
        return AttendanceMonthlyPayload.builder()
                .engineerId(monthRow.getEngineerId())
                .engineerName(engineer == null ? null : engineer.getFullName())
                .workMonth(target.toString())
                .status(monthRow.getStatus())
                .scheduledMinutes(value(monthRow.getScheduledMinutes()))
                .workedMinutes(value(monthRow.getWorkedMinutes()))
                .regularMinutes(value(monthRow.getRegularMinutes()))
                .overtimeMinutes(value(monthRow.getOvertimeMinutes()))
                .holidayMinutes(value(monthRow.getHolidayMinutes()))
                .lateNightMinutes(value(monthRow.getLateNightMinutes()))
                .leaveMinutes(value(monthRow.getLeaveMinutes()))
                .days(payloadDays)
                .build();
    }

    /**
     * 対象月の月次勤怠をscope（design §5.3: 管理者=全件、HR=法人scope、マネージャー=組織scope）で返す。
     */
    private List<AttendanceMonth> monthsForScope(YearMonth target) {
        String role = SecurityUtils.currentRole();
        LambdaQueryWrapper<AttendanceMonth> query = new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                .orderByAsc(AttendanceMonth::getEngineerId);
        if ("管理者".equals(role)) {
            // 全件
        } else if ("HR".equals(role)) {
            Set<Long> legalEntityIds = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
            if (legalEntityIds.isEmpty()) {
                return List.of();
            }
            query.in(AttendanceMonth::getLegalEntityId, legalEntityIds);
        } else if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) {
                // 組織条件なし
            } else {
                Set<Long> engineerIds = organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
                if (engineerIds.isEmpty()) {
                    return List.of();
                }
                query.in(AttendanceMonth::getEngineerId, engineerIds);
            }
        } else {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
        return attendanceMonthMapper.selectList(query);
    }

    private AttendanceProvider resolveProvider() {
        String configured = systemConfigService.getString(CONFIG_PROVIDER, "mock");
        for (AttendanceProvider provider : providers) {
            if (configured.equalsIgnoreCase(provider.source())) {
                return provider;
            }
        }
        throw BusinessException.of(400, "error.attendance.sync.providerUnknown");
    }

    private String readCursor(Long legalEntityId) {
        // SystemConfigServiceのキャッシュはafterCommit更新のため、同一tx内のreadはmapper直読みにする
        String key = cursorKey(legalEntityId);
        com.ses.entity.SystemConfig config = systemConfigMapper.selectById(key);
        return config == null ? null : config.getConfigValue();
    }

    private void saveCursor(Long legalEntityId, String cursor) {
        if (cursor == null) {
            return;
        }
        // 法人別cursorキーは動的（SCHEMAS未登録）のためSystemConfigService.putは使えない。
        // cursorはmapper直読（readCursor）のため、キャッシュ整合は問題にならない。
        String key = cursorKey(legalEntityId);
        com.ses.entity.SystemConfig config = systemConfigMapper.selectById(key);
        if (config == null) {
            systemConfigMapper.insert(new com.ses.entity.SystemConfig(key, cursor, "外部勤怠同期のupdated_at cursor（法人別）"));
        } else {
            config.setConfigValue(cursor);
            systemConfigMapper.updateById(config);
        }
    }

    /** R5-P2-03: cursor keyは法人別（管理者用は従来のグローバルkey）。 */
    private String cursorKey(Long legalEntityId) {
        return legalEntityId == null ? CONFIG_CURSOR : CONFIG_CURSOR_LE_PREFIX + legalEntityId;
    }

    private void saveLastResult(AttendanceSyncResultDto result) {
        try {
            systemConfigService.put(CONFIG_LAST_RESULT, objectMapper.writeValueAsString(result),
                    "直近の外部勤怠同期実行結果");
        } catch (Exception e) {
            log.warn("attendance sync result save failed: {}", e.getMessage());
        }
    }

    private AttendanceSyncResultDto newResult(String provider, String direction, YearMonth target) {
        AttendanceSyncResultDto result = AttendanceSyncResultDto.empty();
        result.setProvider(provider);
        result.setDirection(direction);
        result.setWorkMonth(target.toString());
        result.setCorrelationId(UUID.randomUUID().toString());
        result.setStartedAt(LocalDateTime.now());
        return result;
    }

    private void finish(AttendanceSyncResultDto result) {
        result.setFinishedAt(LocalDateTime.now());
        result.setSuccess(result.getErrors().isEmpty());
        saveLastResult(result);
    }

    private String payloadHash(AttendanceMonthlyPayload payload) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            return "att-sync-" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw BusinessException.of(500, "error.attendance.sync.hashFailed");
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.of(400, "error.attendance.invalidMonth");
        }
    }

    private int value(Integer v) {
        return v == null ? 0 : v;
    }
}
