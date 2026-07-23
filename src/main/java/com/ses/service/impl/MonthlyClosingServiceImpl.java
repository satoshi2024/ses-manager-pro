package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.SysUser;
import com.ses.entity.SystemConfig;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final SystemConfigMapper systemConfigMapper;
    private final SysUserMapper sysUserMapper;

    /** 締め記録1件。 */
    public static class ClosingRecord {
        public String month;
        @JsonAlias("userId")
        public Long by;
        @JsonAlias("confirmedAt")
        public LocalDateTime at;
        public ClosingRecord() {}
        public ClosingRecord(String month, Long by, LocalDateTime at) {
            this.month = month;
            this.by = by;
            this.at = at;
        }
    }

    private void validateMonth(String month) {
        // Use common DateUtils to share same 400 error logic.
        com.ses.common.util.DateUtils.parseYearMonth(month);
    }

    private void requireCloserRole(String role) {
        if (!"管理者".equals(role) && !"マネージャー".equals(role)) {
            throw BusinessException.of(403, "error.closing.roleDenied");
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
        List<UnbilledWorkRecordDto> items = invoiceMapper.selectUnbilledWorkRecordsAll(month);
        Map<Long, MonthlyClosingSummaryDto.CustomerUnbilledDto> map = new LinkedHashMap<>();
        for (UnbilledWorkRecordDto item : items) {
            Long cid = item.getCustomerId();
            MonthlyClosingSummaryDto.CustomerUnbilledDto group = map.computeIfAbsent(cid, k -> {
                MonthlyClosingSummaryDto.CustomerUnbilledDto g = new MonthlyClosingSummaryDto.CustomerUnbilledDto();
                g.setCustomerId(cid);
                g.setCustomerName(item.getCustomerName());
                g.setSubtotal(BigDecimal.ZERO);
                g.setItems(new ArrayList<>());
                return g;
            });
            // NULL金額（旧データ等）は0として集計する（R3R-09）。
            BigDecimal amount = item.getBillingAmount() != null ? item.getBillingAmount() : BigDecimal.ZERO;
            group.setSubtotal(group.getSubtotal().add(amount));
            group.getItems().add(item);
        }
        dto.setUnbilledConfirmed(new ArrayList<>(map.values()));

        // (d) 未払BP
        dto.setUnpaidBp(bpPaymentMapper.selectListWithDetails(month, "未払"));

        // (e) 期限超過請求（残高付き）: 未回収残高一覧のうち due_date<today
        LocalDate today = LocalDate.now();
        List<InvoiceBalanceDto> overdue = new ArrayList<>();
        for (InvoiceBalanceDto b : invoiceMapper.selectOutstandingBalances()) {
            if (com.ses.service.InvoiceService.isOverdue(b.getStatus(), b.getDueDate(), today)) {
                overdue.add(b);
            }
        }
        dto.setOverdueInvoices(overdue);

        dto.setUnenteredCount(dto.getUnenteredWork().size());
        dto.setUnconfirmedCount(dto.getUnconfirmedRecords().size());
        dto.setUnbilledCount(items.size());
        dto.setUnpaidBpCount(dto.getUnpaidBp().size());
        dto.setOverdueCount(overdue.size());

        // (a)-(d) が全て0なら締め可能（(e)期限超過は締めを妨げない）
        dto.setReadyToClose(dto.getUnenteredCount() == 0 && dto.getUnconfirmedCount() == 0
                && dto.getUnbilledCount() == 0 && dto.getUnpaidBpCount() == 0);

        ClosingRecord rec = findRecord(month);
        if (rec != null) {
            dto.setClosed(true);
            dto.setClosedBy(rec.by);
            dto.setClosedAt(rec.at);
            if (rec.by != null) {
                SysUser u = sysUserMapper.selectById(rec.by);
                if (u != null) {
                    dto.setClosedByName(StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername());
                } else {
                    dto.setClosedByName("ID:" + rec.by);
                }
            } else {
                dto.setClosedByName("");
            }
        }
        return dto;
    }

    @Transactional
    @Override
    public void confirmClosing(String month, Long userId, String role) {
        validateMonth(month);
        requireCloserRole(role);
        // 先に締め設定行をロックし、confirm と保護対象更新（工数保存・請求取消）を直列化する（R3R-05）。
        SystemConfig config = systemConfigMapper.selectByIdForUpdate(CONFIG_KEY);
        List<ClosingRecord> records = loadRecordsFromJson(config == null ? "" : config.getConfigValue(), true);
        // 冪等: 既に締め済みなら実行者・締め日時を保持したまま no-op（R3R-07）。
        if (records.stream().anyMatch(r -> month.equals(r.month))) {
            return;
        }
        // ロック取得後に summary を再計算する（締め成立直前の残件を確実に検出する / R3R-05）。
        MonthlyClosingSummaryDto s = summary(month);
        if (!s.isReadyToClose()) {
            throw BusinessException.of(400, "error.closing.notReady");
        }
        records.add(new ClosingRecord(month, userId, LocalDateTime.now()));
        saveRecordsToJson(records, config);
    }

    @Transactional
    @Override
    public void reopenClosing(String month, Long userId, String role) {
        validateMonth(month);
        requireCloserRole(role);
        SystemConfig config = systemConfigMapper.selectByIdForUpdate(CONFIG_KEY);
        List<ClosingRecord> records = loadRecordsFromJson(config == null ? "" : config.getConfigValue(), true);
        boolean removed = records.removeIf(r -> month.equals(r.month));
        if (!removed) {
            throw BusinessException.of(400, "error.closing.notClosed");
        }
        saveRecordsToJson(records, config);
    }

    @Override
    public boolean isClosed(String month) {
        return findRecord(month) != null;
    }

    @Transactional
    @Override
    public void assertOpenForUpdate(String month) {
        validateMonth(month);
        // 締め設定行を FOR UPDATE でロックし、confirm と直列化する。
        SystemConfig config = systemConfigMapper.selectByIdForUpdate(CONFIG_KEY);
        // 締めJSON破損時は throwOnError=true で fail-closed（更新拒否）とする（R3R-06）。
        List<ClosingRecord> records = loadRecordsFromJson(config == null ? "" : config.getConfigValue(), true);
        if (records.stream().anyMatch(r -> month.equals(r.month))) {
            throw BusinessException.of(400, "error.closing.hardLocked");
        }
    }

    private ClosingRecord findRecord(String month) {
        SystemConfig config = systemConfigMapper.selectById(CONFIG_KEY);
        // 読取（isClosed/summary）も締めJSON破損時は fail-closed とし、締め状態を推測で解除しない（R3R-06）。
        List<ClosingRecord> records = loadRecordsFromJson(config == null ? "" : config.getConfigValue(), true);
        return records.stream().filter(r -> month.equals(r.month)).findFirst().orElse(null);
    }

    private List<ClosingRecord> loadRecordsFromJson(String json, boolean throwOnError) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<ClosingRecord>>() {});
        } catch (Exception e) {
            log.warn("締め記録JSONの解析に失敗しました。空として扱います: {}", json, e);
            if (throwOnError) {
                throw BusinessException.of(500, "error.closing.corrupted");
            }
            return new ArrayList<>();
        }
    }

    private void saveRecordsToJson(List<ClosingRecord> records, SystemConfig config) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(records);
            systemConfigService.put(CONFIG_KEY, json, "月次締め済み月の記録(JSON)");
        } catch (Exception e) {
            throw BusinessException.of(500, "error.closing.saveFailed");
        }
    }
}