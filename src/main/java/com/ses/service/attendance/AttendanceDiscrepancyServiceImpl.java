package com.ses.service.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.attendance.discrepancy.AttendanceDiscrepancyDto;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.SystemConfig;
import com.ses.entity.WorkRecord;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客先工数差異（S11 B2 / R4.1・R4.2）。
 *
 * <p>雇用勤怠合計（t_attendance_month.worked_minutes、分）と契約工数（t_work_record.actual_hours×60）を
 * 月次で比較する。read-only DTOであり、{@code WorkRecordServiceImpl}の金額計算・請求ロジックへ
 * 一切接続しない。差異を確認・理由保存しても請求金額は変わらない（R4.2 / design §5.4）。</p>
 *
 * <p>理由の保存先はm_system_configのJSON（closing.confirmed-months前例）である。
 * 新規テーブルは予約外migration禁止（NOTE-R4-04）のため作れず、work record系テーブルへの
 * 書込みは「請求ロジックへの接続」と見なされるため避ける（逸脱と根拠をledgerへ記録）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceDiscrepancyServiceImpl implements AttendanceDiscrepancyService {

    static final String CONFIG_THRESHOLD = "attendance.discrepancy.threshold-minutes";
    static final String CONFIG_CONFIRMED = "attendance.discrepancy.confirmed";

    private final AttendanceMonthMapper attendanceMonthMapper;
    private final WorkRecordMapper workRecordMapper;
    private final ContractMapper contractMapper;
    private final EngineerMapper engineerMapper;
    private final SystemConfigMapper systemConfigMapper;
    private final AttendanceScopeResolver attendanceScopeResolver;
    private final OrganizationScopeService organizationScopeService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public AttendanceDiscrepancyDto list(String month) {
        YearMonth target = parseMonth(month);
        int threshold = systemConfigServiceThreshold();
        Set<Long> scopedEngineerIds = scopedEngineerIds(target);
        List<AttendanceMonth> months = attendanceMonthMapper.selectList(
                new LambdaQueryWrapper<AttendanceMonth>()
                        .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                        .orderByAsc(AttendanceMonth::getEngineerId));
        if (scopedEngineerIds != null) {
            months = months.stream()
                    .filter(m -> scopedEngineerIds.contains(m.getEngineerId()))
                    .toList();
        }
        if (months.isEmpty()) {
            return AttendanceDiscrepancyDto.empty(month, threshold);
        }
        Set<Long> engineerIds = months.stream().map(AttendanceMonth::getEngineerId).collect(Collectors.toSet());
        Map<Long, Engineer> engineers = engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId, e -> e));

        // 契約工数（分）: engineer_idで稼働中契約を引き、work_recordのactual_hours×60を合算
        Map<Long, Integer> contractMinutesByEngineer = contractMinutesByEngineer(target, engineerIds);
        Map<String, ConfirmedEntry> confirmed = readConfirmed();

        AttendanceDiscrepancyDto dto = AttendanceDiscrepancyDto.empty(month, threshold);
        for (AttendanceMonth m : months) {
            int attendanceMinutes = value(m.getWorkedMinutes());
            int contractMinutes = contractMinutesByEngineer.getOrDefault(m.getEngineerId(), 0);
            int diffMinutes = attendanceMinutes - contractMinutes;
            boolean overThreshold = Math.abs(diffMinutes) >= threshold;
            ConfirmedEntry c = confirmed.get(key(m.getEngineerId(), target));
            Engineer engineer = engineers.get(m.getEngineerId());
            dto.getItems().add(AttendanceDiscrepancyDto.Item.builder()
                    .engineerId(m.getEngineerId())
                    .engineerName(engineer == null ? null : engineer.getFullName())
                    .legalEntityId(m.getLegalEntityId())
                    .organizationId(m.getOrganizationId())
                    .attendanceMinutes(attendanceMinutes)
                    .contractMinutes(contractMinutes)
                    .diffMinutes(diffMinutes)
                    .overThreshold(overThreshold)
                    .confirmed(c != null)
                    .reason(c == null ? null : c.getReason())
                    .confirmedAt(c == null ? null : c.getConfirmedAt())
                    .confirmedBy(c == null ? null : c.getConfirmedBy())
                    .build());
        }
        return dto;
    }

    @Override
    public AttendanceDiscrepancyDto pendingWarnings(String month) {
        YearMonth target = parseMonth(month);
        int threshold = systemConfigServiceThreshold();
        List<AttendanceMonth> months = attendanceMonthMapper.selectList(
                new LambdaQueryWrapper<AttendanceMonth>()
                        .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                        .orderByAsc(AttendanceMonth::getEngineerId));
        if (months.isEmpty()) {
            return AttendanceDiscrepancyDto.empty(month, threshold);
        }
        Set<Long> engineerIds = months.stream().map(AttendanceMonth::getEngineerId).collect(Collectors.toSet());
        Map<Long, Engineer> engineers = engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId, e -> e));
        Map<Long, Integer> contractMinutesByEngineer = contractMinutesByEngineer(target, engineerIds);
        Map<String, ConfirmedEntry> confirmed = readConfirmed();

        AttendanceDiscrepancyDto dto = AttendanceDiscrepancyDto.empty(month, threshold);
        for (AttendanceMonth m : months) {
            int attendanceMinutes = value(m.getWorkedMinutes());
            int contractMinutes = contractMinutesByEngineer.getOrDefault(m.getEngineerId(), 0);
            int diffMinutes = attendanceMinutes - contractMinutes;
            boolean overThreshold = Math.abs(diffMinutes) >= threshold;
            if (!overThreshold || confirmed.containsKey(key(m.getEngineerId(), target))) {
                continue; // 閾値以内・確認済みはwarning対象外
            }
            Engineer engineer = engineers.get(m.getEngineerId());
            dto.getItems().add(AttendanceDiscrepancyDto.Item.builder()
                    .engineerId(m.getEngineerId())
                    .engineerName(engineer == null ? null : engineer.getFullName())
                    .legalEntityId(m.getLegalEntityId())
                    .organizationId(m.getOrganizationId())
                    .attendanceMinutes(attendanceMinutes)
                    .contractMinutes(contractMinutes)
                    .diffMinutes(diffMinutes)
                    .overThreshold(true)
                    .confirmed(false)
                    .build());
        }
        return dto;
    }

    @Override
    @Transactional
    public void confirm(Long engineerId, String month, String reason) {
        YearMonth target = parseMonth(month);
        if (engineerId == null) {
            throw BusinessException.of(400, "error.attendance.discrepancy.engineerRequired");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw BusinessException.of(400, "error.attendance.discrepancy.reasonRequired");
        }
        // scope確認: 対象エンジニアがcallerのscope内か（管理者=全件、HR=法人、マネージャー=組織）
        AttendanceMonth monthRow = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                .last("LIMIT 1"));
        if (monthRow == null) {
            throw BusinessException.of(404, "error.attendance.monthNotFound");
        }
        assertScope(monthRow, target);

        Map<String, ConfirmedEntry> confirmed = readConfirmed();
        Long userId = SecurityUtils.currentUserId();
        confirmed.put(key(engineerId, target), ConfirmedEntry.builder()
                .engineerId(engineerId)
                .workMonth(target.toString())
                .reason(reason.trim())
                .confirmedAt(LocalDateTime.now().toString())
                .confirmedBy(userId == null ? null : String.valueOf(userId))
                .build());
        writeConfirmed(confirmed);
    }

    private void assertScope(AttendanceMonth monthRow, YearMonth target) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return;
        }
        if ("HR".equals(role)) {
            Set<Long> legalEntityIds = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
            if (monthRow.getLegalEntityId() == null || !legalEntityIds.contains(monthRow.getLegalEntityId())) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
            return;
        }
        if ("マネージャー".equals(role)) {
            if (!organizationScopeService.hasFullAccess()
                    && !organizationScopeService.allowedEngineerIds(target.atEndOfMonth())
                    .contains(monthRow.getEngineerId())) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
            return;
        }
        throw BusinessException.of(403, "error.attendance.roleDenied");
    }

    private Set<Long> scopedEngineerIds(YearMonth target) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return null;
        }
        if ("HR".equals(role)) {
            Set<Long> ids = attendanceScopeResolver.allowedHrEngineerIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
            return ids == null ? Set.of() : ids;
        }
        if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) {
                return null;
            }
            Set<Long> ids = organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
            return ids == null ? Set.of() : ids;
        }
        throw BusinessException.of(403, "error.attendance.roleDenied");
    }

    /** 対象月に稼働中の契約（engineer_id）のactual_hours合計を分へ換算して返す。 */
    private Map<Long, Integer> contractMinutesByEngineer(YearMonth target, Set<Long> engineerIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (engineerIds.isEmpty()) {
            return result;
        }
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .in(Contract::getEngineerId, engineerIds)
                .le(Contract::getStartDate, target.atEndOfMonth())
                .and(w -> w.isNull(Contract::getEndDate)
                        .or().ge(Contract::getEndDate, target.atDay(1)))
                .in(Contract::getStatus, "稼動中", "終了"));
        if (contracts.isEmpty()) {
            return result;
        }
        List<Long> contractIds = contracts.stream().map(Contract::getId).toList();
        List<WorkRecord> records = workRecordMapper.selectList(new LambdaQueryWrapper<WorkRecord>()
                .in(WorkRecord::getContractId, contractIds)
                .eq(WorkRecord::getWorkMonth, target.toString()));
        Map<Long, Long> engineerByContract = contracts.stream()
                .collect(Collectors.toMap(Contract::getId, Contract::getEngineerId, (a, b) -> a));
        for (WorkRecord record : records) {
            Long engineerId = engineerByContract.get(record.getContractId());
            if (engineerId == null || record.getActualHours() == null) {
                continue;
            }
            int minutes = record.getActualHours().multiply(BigDecimal.valueOf(60)).intValue();
            result.merge(engineerId, minutes, Integer::sum);
        }
        return result;
    }

    private int systemConfigServiceThreshold() {
        SystemConfig config = systemConfigMapper.selectById(CONFIG_THRESHOLD);
        if (config == null || config.getConfigValue() == null) {
            return 480; // 既定8時間（tasks.md B2 Demo）
        }
        try {
            int value = Integer.parseInt(config.getConfigValue().trim());
            return Math.max(0, value);
        } catch (NumberFormatException e) {
            log.warn("attendance.discrepancy.threshold-minutesの値が不正なため480を使います: {}", config.getConfigValue());
            return 480;
        }
    }

    private Map<String, ConfirmedEntry> readConfirmed() {
        SystemConfig config = systemConfigMapper.selectById(CONFIG_CONFIRMED);
        if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
            return new HashMap<>();
        }
        try {
            List<ConfirmedEntry> entries = objectMapper.readValue(config.getConfigValue(),
                    new TypeReference<List<ConfirmedEntry>>() {});
            Map<String, ConfirmedEntry> result = new HashMap<>();
            if (entries != null) {
                for (ConfirmedEntry e : entries) {
                    result.put(key(e.getEngineerId(), YearMonth.parse(e.getWorkMonth())), e);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("attendance.discrepancy.confirmedが読み取れないため空で扱います");
            return new HashMap<>();
        }
    }

    private void writeConfirmed(Map<String, ConfirmedEntry> confirmed) {
        try {
            SystemConfig config = systemConfigMapper.selectById(CONFIG_CONFIRMED);
            String json = objectMapper.writeValueAsString(new ArrayList<>(confirmed.values()));
            if (config == null) {
                systemConfigMapper.insert(new SystemConfig(CONFIG_CONFIRMED, json,
                        "客先工数差異の確認理由（システム管理）"));
            } else {
                config.setConfigValue(json);
                systemConfigMapper.updateById(config);
            }
        } catch (Exception e) {
            throw BusinessException.of(500, "error.attendance.discrepancy.saveFailed");
        }
    }

    private String key(Long engineerId, YearMonth target) {
        return engineerId + ":" + target;
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

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ConfirmedEntry {
        private Long engineerId;
        private String workMonth;
        private String reason;
        private String confirmedAt;
        private String confirmedBy;
    }
}
