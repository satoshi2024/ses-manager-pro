package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.dto.closing.MonthlyClosingWorkRecordDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.MonthlyClosing;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.MonthlyClosingMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
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
 * 締め記録は t_monthly_closing（tenant×月）に保持し、FOR UPDATE → CAS で直列化する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyClosingServiceImpl implements MonthlyClosingService {

    private final WorkRecordMapper workRecordMapper;
    private final InvoiceMapper invoiceMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final MonthlyClosingMapper monthlyClosingMapper;
    private final SysUserMapper sysUserMapper;
    private final com.ses.service.compliance.LaborComplianceService laborComplianceService;
    /**
     * ObjectProvider 経由。MenuCacheService が無いテストスライスでも締め処理本体を壊さないため
     * （その場合はコンプライアンス欄のみ非表示＝fail-closed になる）。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.MenuCacheService> menuCacheServiceProvider;

    /** 月次snapshotは本番Beanが存在する場合だけ締め処理へ接続する（既存テストslice互換）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.MonthlyAccountingSnapshotService monthlyAccountingSnapshotService;

    /** 未検収件数（R4.2）。既存テストslice互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.AcceptanceMapper acceptanceMapper;

    /** 未検収件数のscope母集団（閲覧者のscopeで数える。design §5.2）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.DataScopeService dataScopeService;

    /** 会計月次照合サービス（R3.3/B3）。既存テストslice互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.accounting.AccountingReconciliationService accountingReconciliationService;

    /** compliance メニューを閲覧できるロールか（管理者は常に可。MenuPermissionFilter と同じ判定）。 */
    private boolean canViewCompliance() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String role = auth.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
        if ("管理者".equals(role)) {
            return true;
        }
        com.ses.service.MenuCacheService menuCacheService = menuCacheServiceProvider.getIfAvailable();
        return menuCacheService != null && role != null
                && menuCacheService.getMenuKeysByRole(role).contains("compliance");
    }

    /**
     * 対象月の締め行を確保して FOR UPDATE でロックする。
     * tenant 欠落は requireTenantContext で fail-closed。
     */
    private MonthlyClosing lockMonth(String month) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        monthlyClosingMapper.ensureRow(tenantId, month);
        MonthlyClosing row = monthlyClosingMapper.selectForUpdate(tenantId, month);
        if (row == null) {
            throw BusinessException.of(500, "error.closing.corrupted");
        }
        return row;
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

    private boolean isConfirmed(MonthlyClosing row) {
        return row != null && row.getConfirmedAt() != null;
    }

    @Override
    public MonthlyClosingSummaryDto summary(String month) {
        validateMonth(month);
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        String monthEnd = YearMonth.parse(month).atEndOfMonth().toString();

        MonthlyClosingSummaryDto dto = new MonthlyClosingSummaryDto();
        dto.setMonth(month);

        // (a) 工数未入力: 勤怠グリッドと完全同一条件（workRecordId==null）
        dto.setUnenteredWork(workRecordMapper.selectMonthlyGrid(month, monthEnd, tenantId).stream()
                .filter(g -> g.getWorkRecordId() == null)
                .toList());

        // (b) 未確定実績
        dto.setUnconfirmedRecords(workRecordMapper.selectUnconfirmedByWorkMonthForTenant(month, tenantId)
                .stream().map(this::toPublicWorkRecord).toList());

        // (c) 確定済み未請求（全顧客）
        List<UnbilledWorkRecordDto> items = invoiceMapper.selectUnbilledWorkRecordsAll(month, tenantId);
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

        // (g) 未検収件数（R4.2）: 閲覧者のscopeで数える（design §5.2。全社件数を全員へ見せない）。
        if (acceptanceMapper != null) {
            // 未検収件数は対象月時点の契約母集団で数える（R09-P1-04: 異動前後の過去月でも一致）
            List<Long> closingContractIds = scopedContractIdsForClosing(month);
            dto.setUnacceptedCount((int) acceptanceMapper.countUnacceptedForClosing(month, closingContractIds, tenantId));
        } else {
            dto.setUnacceptedCount(0);
        }

        // (d) 未払BP
        dto.setUnpaidBp(bpPaymentMapper.selectListWithDetailsForTenant(month, "未払", tenantId));

        // (e) 期限超過請求（残高付き）: 未回収残高一覧のうち due_date<today
        LocalDate today = LocalDate.now();
        List<InvoiceBalanceDto> overdue = new ArrayList<>();
        for (InvoiceBalanceDto b : invoiceMapper.selectOutstandingBalancesForTenant(tenantId)) {
            if (com.ses.service.InvoiceService.isOverdue(b.getStatus(), b.getDueDate(), today)) {
                overdue.add(b);
            }
        }
        dto.setOverdueInvoices(overdue);

        // (f) 労務コンプライアンスリスク（現在の状態を都度導出。月に紐づく記録ではないため月非依存）。
        // 月次締めメニューは HR にも開放されているが compliance メニューは管理者/マネージャー限定のため、
        // ここで権限を確認しないと HR が締め画面経由でリスク一覧を閲覧できてしまう。
        dto.setComplianceFindings(canViewCompliance()
                ? laborComplianceService.findCurrentRisks()
                : java.util.List.of());

        // (h) 会計連携の未解消差異件数（R3.3/B3）
        if (accountingReconciliationService != null) {
            try {
                var recon = accountingReconciliationService.reconcileMonth(month);
                dto.setAccountingDiscrepancyCount(recon.getAmountMismatchCount() + recon.getInternalOnlyCount());
            } catch (Exception e) {
                log.warn("Failed to check accounting reconciliation for month={}", month, e);
                dto.setAccountingDiscrepancyCount(0);
            }
        } else {
            dto.setAccountingDiscrepancyCount(0);
        }

        dto.setUnenteredCount(dto.getUnenteredWork().size());
        dto.setUnconfirmedCount(dto.getUnconfirmedRecords().size());
        dto.setUnbilledCount(items.size());
        dto.setUnpaidBpCount(dto.getUnpaidBp().size());
        dto.setOverdueCount(overdue.size());
        dto.setComplianceCount(dto.getComplianceFindings().size());

        // (a)-(d) が全て0なら締め可能（(e)期限超過は締めを妨げない）
        dto.setReadyToClose(dto.getUnenteredCount() == 0 && dto.getUnconfirmedCount() == 0
                && dto.getUnbilledCount() == 0 && dto.getUnpaidBpCount() == 0);

        MonthlyClosing rec = findRecord(tenantId, month);
        if (isConfirmed(rec)) {
            dto.setClosed(true);
            dto.setClosedBy(rec.getConfirmedBy());
            dto.setClosedAt(rec.getConfirmedAt());
            if (rec.getConfirmedBy() != null) {
                SysUser u = sysUserMapper.selectById(rec.getConfirmedBy());
                if (u != null) {
                    dto.setClosedByName(StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername());
                } else {
                    dto.setClosedByName("ID:" + rec.getConfirmedBy());
                }
            } else {
                dto.setClosedByName("");
            }
        }
        return dto;
    }

    /** 未確定勤怠は画面で必要な項目だけを公開し、永続化entityの監査項目を隠す。 */
    private MonthlyClosingWorkRecordDto toPublicWorkRecord(WorkRecord source) {
        MonthlyClosingWorkRecordDto target = new MonthlyClosingWorkRecordDto();
        target.setWorkRecordId(source.getId());
        target.setContractId(source.getContractId());
        target.setWorkMonth(source.getWorkMonth());
        target.setActualHours(source.getActualHours());
        target.setStatus(source.getStatus());
        target.setRemarks(source.getRemarks());
        target.setRejectComment(source.getRejectComment());
        target.setVersion(source.getVersion());
        return target;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void confirmClosing(String month, Long userId, String role) {
        validateMonth(month);
        requireCloserRole(role);
        // 先に締め行をロックし、confirm と保護対象更新（工数保存・請求取消）を直列化する（R3R-05）。
        MonthlyClosing locked = lockMonth(month);
        // 冪等: 既に締め済みなら実行者・締め日時を保持したまま no-op（R3R-07）。
        if (isConfirmed(locked)) {
            return;
        }
        // ロック取得後に summary を再計算する（締め成立直前の残件を確実に検出する / R3R-05）。
        MonthlyClosingSummaryDto s = summary(month);
        if (!s.isReadyToClose()) {
            throw BusinessException.of(400, "error.closing.notReady");
        }
        if (accountingReconciliationService != null) {
            accountingReconciliationService.assertReconciledForClosing(month);
        }
        if (monthlyAccountingSnapshotService != null) {
            monthlyAccountingSnapshotService.snapshotMonth(month);
        }
        int updated = monthlyClosingMapper.confirmCas(
                locked.getTenantId(), month, userId, LocalDateTime.now(), locked.getVersion());
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void reopenClosing(String month, Long userId, String role) {
        validateMonth(month);
        requireCloserRole(role);
        MonthlyClosing locked = lockMonth(month);
        if (!isConfirmed(locked)) {
            throw BusinessException.of(400, "error.closing.notClosed");
        }
        int updated = monthlyClosingMapper.reopenCas(
                locked.getTenantId(), month, locked.getVersion());
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    @Override
    public boolean isClosed(String month) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        return isConfirmed(findRecord(tenantId, month));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void assertOpenForUpdate(String month) {
        validateMonth(month);
        // 締め行を FOR UPDATE でロックし、confirm と直列化する。
        MonthlyClosing locked = lockMonth(month);
        if (isConfirmed(locked)) {
            throw BusinessException.of(400, "error.closing.hardLocked");
        }
    }

    /** 未検収件数のscope母集団（対象月時点。空集合=全件ではなく、条件を付けない＝全件の意図を呼出側へ明示）。 */
    private List<Long> scopedContractIdsForClosing(String month) {
        if (com.ses.common.util.SecurityUtils.isHrRole()) {
            return java.util.List.of(); // HRは未検収件数0
        }
        if (dataScopeService == null || !dataScopeService.isScoped()) {
            return null; // 全件（SQL側で条件を付けない）
        }
        java.time.LocalDate asOf = month == null || month.isBlank()
                ? java.time.LocalDate.now()
                : java.time.YearMonth.parse(month).atEndOfMonth();
        java.util.Set<Long> ids = dataScopeService.allowedContractIdsAsOf(asOf);
        return ids == null ? java.util.List.of() : new java.util.ArrayList<>(ids);
    }

    private MonthlyClosing findRecord(String tenantId, String month) {
        return monthlyClosingMapper.selectByTenantAndMonth(tenantId, month);
    }
}
