package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 月次締めチェックリストサービス実装。
 * 締め記録は m_system_config の "closing.confirmed-months"(JSON配列) に保持し、
 * JSON の直接操作は本クラスの isClosed/confirm/reopen 経由のみに限定する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyClosingServiceImpl implements MonthlyClosingService {

    private static final String CONFIG_KEY = "closing.confirmed-months";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final WorkRecordMapper workRecordMapper;
    private final InvoiceMapper invoiceMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final SystemConfigService systemConfigService;

    /** 締め記録1件。 */
    public static class ClosingRecord {
        public String month;
        public Long userId;
        public LocalDateTime confirmedAt;
        public ClosingRecord() {}
        public ClosingRecord(String month, Long userId, LocalDateTime confirmedAt) {
            this.month = month;
            this.userId = userId;
            this.confirmedAt = confirmedAt;
        }
    }

    private void validateMonth(String month) {
        try {
            YearMonth.parse(month);
        } catch (Exception e) {
            throw BusinessException.of("error.closing.invalidMonth");
        }
    }

    private void requireCloserRole(String role) {
        if (!"管理者".equals(role) && !"マネージャー".equals(role)) {
            throw BusinessException.of("error.closing.roleDenied");
        }
    }

    @Override
    public MonthlyClosingSummaryDto summary(String month) {
        validateMonth(month);
        String monthEnd = YearMonth.parse(month).atEndOfMonth().toString();

        MonthlyClosingSummaryDto dto = new MonthlyClosingSummaryDto();
        dto.setMonth(month);

        // (a) 工数未入力: 勤怠グリッドと完全同一条件（workRecordId==null）
        dto.setUnenteredWork(workRecordMapper.selectMonthlyGrid(month, monthEnd).stream()
                .filter(g -> g.getWorkRecordId() == null)
                .toList());

        // (b) 未確定実績
        dto.setUnconfirmedRecords(workRecordMapper.selectList(new QueryWrapper<WorkRecord>()
                .eq("work_month", month)
                .ne("status", "確定")));

        // (c) 確定済み未請求（全顧客）
        dto.setUnbilledConfirmed(invoiceMapper.selectUnbilledWorkRecordsAll(month));

        // (d) 未払BP
        dto.setUnpaidBp(bpPaymentMapper.selectListWithDetails(month, "未払"));

        // (e) 期限超過請求（残高付き）: 未回収残高一覧のうち due_date<today
        LocalDate today = LocalDate.now();
        List<InvoiceBalanceDto> overdue = new ArrayList<>();
        for (InvoiceBalanceDto b : invoiceMapper.selectOutstandingBalances()) {
            if (b.getDueDate() != null && b.getDueDate().isBefore(today)) {
                overdue.add(b);
            }
        }
        dto.setOverdueInvoices(overdue);

        dto.setUnenteredCount(dto.getUnenteredWork().size());
        dto.setUnconfirmedCount(dto.getUnconfirmedRecords().size());
        dto.setUnbilledCount(dto.getUnbilledConfirmed().size());
        dto.setUnpaidBpCount(dto.getUnpaidBp().size());
        dto.setOverdueCount(overdue.size());

        // (a)-(d) が全て0なら締め可能（(e)期限超過は締めを妨げない）
        dto.setReadyToClose(dto.getUnenteredCount() == 0 && dto.getUnconfirmedCount() == 0
                && dto.getUnbilledCount() == 0 && dto.getUnpaidBpCount() == 0);

        ClosingRecord rec = findRecord(month);
        if (rec != null) {
            dto.setClosed(true);
            dto.setClosedBy(rec.userId);
            dto.setClosedAt(rec.confirmedAt);
        }
        return dto;
    }

    @Override
    public void confirmClosing(String month, Long userId, String role) {
        validateMonth(month);
        requireCloserRole(role);
        MonthlyClosingSummaryDto s = summary(month);
        if (!s.isReadyToClose()) {
            throw BusinessException.of("error.closing.notReady");
        }
        List<ClosingRecord> records = loadRecords();
        records.removeIf(r -> month.equals(r.month));
        records.add(new ClosingRecord(month, userId, LocalDateTime.now()));
        saveRecords(records);
    }

    @Override
    public void reopenClosing(String month, Long userId, String role) {
        validateMonth(month);
        requireCloserRole(role);
        List<ClosingRecord> records = loadRecords();
        boolean removed = records.removeIf(r -> month.equals(r.month));
        if (!removed) {
            throw BusinessException.of("error.closing.notClosed");
        }
        saveRecords(records);
    }

    @Override
    public boolean isClosed(String month) {
        return findRecord(month) != null;
    }

    private ClosingRecord findRecord(String month) {
        return loadRecords().stream().filter(r -> month.equals(r.month)).findFirst().orElse(null);
    }

    private List<ClosingRecord> loadRecords() {
        String json = systemConfigService.getString(CONFIG_KEY, "");
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<ClosingRecord>>() {});
        } catch (Exception e) {
            log.warn("締め記録JSONの解析に失敗しました。空として扱います: {}", json, e);
            return new ArrayList<>();
        }
    }

    private void saveRecords(List<ClosingRecord> records) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(records);
            systemConfigService.put(CONFIG_KEY, json, "月次締め済み月の記録(JSON)");
        } catch (Exception e) {
            throw new BusinessException("締め記録の保存に失敗しました");
        }
    }
}
