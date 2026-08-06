package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.common.exception.BusinessException;
import com.ses.entity.WorkRecordDaily;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.WorkRecordDailyMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.service.MonthlyClosingService;
import com.ses.service.NotificationService;
import com.ses.service.WorkRecordService;
import com.ses.service.billing.SettlementCalculator;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import com.ses.common.constant.StatusConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkRecordServiceImpl extends ServiceImpl<WorkRecordMapper, WorkRecord> implements WorkRecordService {

    private static final Logger log = LoggerFactory.getLogger(WorkRecordServiceImpl.class);

    // 勤怠状態遷移の唯一の権威。差戻しは入力可否では入力中と同扱い（saveHours/日次入力で許可）。
    private static final Map<String, Set<String>> ALLOWED_STATUS = Map.of(
            "入力中", Set.of("提出済"),
            "差戻し", Set.of("提出済"),
            "提出済", Set.of("確定", "差戻し"),
            "確定", Set.of());

    private final ContractMapper contractMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final InvoiceItemMapper invoiceItemMapper;
    private final NotificationService notificationService;
    private final WorkRecordDailyMapper workRecordDailyMapper;
    private final MonthlyClosingService monthlyClosingService;
    private final com.ses.mapper.EngineerAccountLinkMapper engineerAccountLinkMapper;

    /** 確定時点の組織・原価部門を勤怠へ凍結するための任意依存。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.EngineerMapper engineerMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.UserOrganizationMapper userOrganizationMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.DataScopeService dataScopeService;

    /** 検収済work recordの再open/金額変更ガード（R3.4）。任意依存。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.AcceptanceMapper acceptanceMapper;

    /**
     * 単価改定履歴のリゾルバ（任意依存）。本番では {@code ContractPriceResolverImpl}(@Service)が
     * 常に配線される。未配線は既存 {@code @InjectMocks} テストの全緑維持のための緩和であり、
     * その場合は契約の現在単価へフォールバックする（＝改定履歴が精算に反映されない）。
     * 誤って Bean 定義が外れた事故に気づけるよう起動時に1回 warn する（下記 {@link #warnIfResolverMissing()}）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.billing.ContractPriceResolver priceResolver;

    @jakarta.annotation.PostConstruct
    void warnIfResolverMissing() {
        if (priceResolver == null) {
            log.warn("ContractPriceResolver が未配線です。精算は契約の現在単価にフォールバックします"
                    + "（テスト専用の緩和。本番では配線されているべき）。");
        }
    }

    private void checkClosing(String month) {
        // 締め設定行をロックして直列化し、締め成立後の工数変更を防ぐ（R3R-05）。
        // 呼び出し元はいずれも @Transactional のため FOR UPDATE ロックが呼び出し側commitまで保持される。
        monthlyClosingService.assertOpenForUpdate(month);
    }

    /** 対象月の末日文字列(yyyy-MM-dd)。方言依存の CONCAT(...,'-31') を避けるため Java 側で確定する。 */
    private static String monthEndOf(String workMonth) {
        return com.ses.common.util.DateUtils.parseYearMonth(workMonth).atEndOfMonth().toString();
    }

    @Override
    public List<WorkRecordGridDto> monthlyGrid(String workMonth) {
        if (organizationScopeService == null || organizationScopeService.hasFullAccess()) {
            return baseMapper.selectMonthlyGrid(workMonth, monthEndOf(workMonth));
        }
        LocalDate asOf = com.ses.common.util.DateUtils.parseYearMonth(workMonth).atDay(1);
        List<Long> dataScopeIds = isSalesDataScoped()
                ? new java.util.ArrayList<>(dataScopeService.allowedContractIds()) : null;
        return baseMapper.selectMonthlyGridScoped(workMonth, monthEndOf(workMonth), asOf, false,
                new java.util.ArrayList<>(organizationScopeService.allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(organizationScopeService.allowedDirectUserIds(asOf)), dataScopeIds);
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkRecordGridDto> monthlyGridPage(String workMonth, Long current, Long size, String keyword, String status) {
        List<WorkRecordGridDto> all = monthlyGrid(workMonth);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase().trim();
            all = all.stream()
                    .filter(g -> (g.getEngineerName() != null && g.getEngineerName().toLowerCase().contains(kw)) ||
                            (g.getProjectName() != null && g.getProjectName().toLowerCase().contains(kw)) ||
                            (g.getContractNo() != null && g.getContractNo().toLowerCase().contains(kw)))
                    .collect(Collectors.toList());
        }
        if (status != null && !status.isBlank()) {
            all = all.stream()
                    .filter(g -> status.equals(g.getStatus()) || (g.getStatus() == null && "未入力".equals(status)))
                    .collect(Collectors.toList());
        }
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkRecordGridDto> page = com.ses.common.util.PageUtils.safePage(current == null ? 1L : current, size == null ? 50L : size, 100L);
        int total = all.size();
        page.setTotal(total);
        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), total);
        int to = (int) Math.min(from + page.getSize(), total);
        page.setRecords(all.subList(from, to));
        return page;
    }

    /** テストおよび保守ツールからリフレクション経由で利用する互換エントリーポイント。 */
    @SuppressWarnings("unused")
    private void assertContractScope(Contract contract, String workMonth) {
        assertContractScope(contract, workMonth, null);
    }

    private void assertContractScope(Contract contract, String workMonth, WorkRecord record) {
        if (contract == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        if (isSalesDataScoped()) {
            dataScopeService.assertAllowedContract(contract.getId());
        }
        if (organizationScopeService == null || organizationScopeService.hasFullAccess()) {
            return;
        }
        LocalDate asOf = com.ses.common.util.DateUtils.parseYearMonth(workMonth).atDay(1);
        Set<Long> allowedOrganizationIds = organizationScopeService.allowedOrganizationIds(asOf);

        if (record != null && Integer.valueOf(1).equals(record.getAccountingDimensionFrozen())) {
            if (record.getOrganizationId() != null && allowedOrganizationIds.contains(record.getOrganizationId())) {
                return;
            }
            if (isDirectUserAllowed(contract, asOf)) {
                return;
            }
            throw BusinessException.of(404, "error.workRecord.notFound2");
        }

        com.ses.entity.EngineerAccountingHistory history = engineerAccountingHistoryMapper == null
                ? null : engineerAccountingHistoryMapper.selectAt(contract.getEngineerId(), asOf);
        if (history != null) {
            // 対象月の要員履歴に組織がある場合、account-linkの所属で上書きしない。
            // 直属上長だけは組織scopeへの追加許可として扱う。
            if (history.getOrganizationId() != null) {
                if (allowedOrganizationIds.contains(history.getOrganizationId())
                        || isDirectUserAllowed(contract, asOf)) {
                    return;
                }
                throw BusinessException.of(404, "error.workRecord.notFound2");
            }
            // 履歴行が存在する場合は、NULL/UNKNOWNを現在のaccount-linkへ補完しない。
            // 明示NULLも履歴値そのものとして扱い、推測による過去月の可視化を防ぐ。
            throw BusinessException.of(404, "error.workRecord.notFound2");
        }

        // 履歴が未作成の過去月へ現在の Engineer 所属を補完しない。
        // 現在値は当月の新規入力に限って利用し、過去月は明示された履歴または
        // platform-invariantsで定めた既知の直属account-linkだけを参照する。
        if (YearMonth.from(asOf).equals(YearMonth.now())) {
            com.ses.entity.Engineer engineer = engineerMapper == null ? null
                    : engineerMapper.selectById(contract.getEngineerId());
            if (engineer != null && engineer.getOrganizationId() != null) {
                if (allowedOrganizationIds.contains(engineer.getOrganizationId())) {
                    return;
                }
                throw BusinessException.of(404, "error.workRecord.notFound2");
            }
            if (isDirectUserAllowed(contract, asOf)) {
                return;
            }
        }

        throw BusinessException.of(404, "error.workRecord.notFound2");
    }

    private boolean isDirectUserAllowed(Contract contract, LocalDate asOf) {
        if (engineerAccountLinkMapper == null) {
            return false;
        }
        com.ses.entity.EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerId(contract.getEngineerId());
        Set<Long> directUserIds = organizationScopeService.allowedDirectUserIds(asOf);
        return link != null && directUserIds != null && directUserIds.contains(link.getSysUserId());
    }

    @Override
    public void assertAllowed(Long workRecordId) {
        if (organizationScopeService == null && !isSalesDataScoped()) {
            return;
        }
        String workMonth = baseMapper.selectWorkMonthById(workRecordId);
        if (workMonth == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        LocalDate asOf = com.ses.common.util.DateUtils.parseYearMonth(workMonth).atDay(1);
        boolean fullAccess = organizationScopeService == null || organizationScopeService.hasFullAccess();
        List<Long> organizationIds = organizationScopeService == null ? List.of()
                : new java.util.ArrayList<>(organizationScopeService.allowedOrganizationIds(asOf));
        List<Long> directUserIds = organizationScopeService == null ? List.of()
                : new java.util.ArrayList<>(organizationScopeService.allowedDirectUserIds(asOf));
        List<Long> dataScopeIds = isSalesDataScoped()
                ? new java.util.ArrayList<>(dataScopeService.allowedContractIds()) : null;
        if (baseMapper.selectByIdScoped(workRecordId, asOf, fullAccess, organizationIds,
                directUserIds, dataScopeIds) == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
    }

    /** 営業固有のDataScopeだけを勤怠へ渡す。マネージャーは対象月の組織scopeを正とする。 */
    private boolean isSalesDataScoped() {
        return dataScopeService != null && dataScopeService.isSalesDataScoped();
    }

    @Override
    @Transactional
    public WorkRecord saveHours(Long contractId, String workMonth, BigDecimal actualHours, String remarks) {
        checkClosing(workMonth);
        return saveHoursInternal(contractId, workMonth, actualHours, remarks, false);
    }

    /**
     * 月次合計の保存本体。fromDaily=false（手動入力）で日次行が存在する月は拒否する（R2-5）。
     * 日次入力（fromDaily=true）は合計の生成元が日次のため許可する。
     */
    private WorkRecord saveHoursInternal(Long contractId, String workMonth, BigDecimal actualHours,
                                         String remarks, boolean fromDaily) {
        Contract contract = contractMapper.selectByIdForUpdate(contractId);
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract");
        }
        WorkRecord record = baseMapper.selectByContractIdAndMonthForUpdate(contractId, workMonth);
        assertContractScope(contract, workMonth, record);
        assertAcceptanceNotAccepted(contractId, workMonth);


        // 保存許可状態は「入力中」「差戻し」のみ（提出済・確定は編集不可）。
        if (record != null && "確定".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.confirmedEdit2");
        }
        if (record != null && "提出済".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.submittedEdit");
        }

        // 日次管理されている月は手動合計入力を禁止する。
        if (!fromDaily && record != null) {
            long dailyCount = workRecordDailyMapper.selectCount(new QueryWrapper<WorkRecordDaily>()
                    .eq("work_record_id", record.getId()));
            if (dailyCount > 0) {
                throw BusinessException.of("error.workRecord.dailyManaged");
            }
        }

        if (record != null) {
            List<String> nos = invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(List.of(record.getId()));
            if (!nos.isEmpty()) {
                throw BusinessException.of("error.workRecord.invoicedEdit2", nos.get(0));
            }
        }

        // 縦深防御: グリッド外からのAPI直叩きで契約期間外・非稼動契約に実績を作られないよう検証する。
        // 判定条件は勤怠グリッド(selectMonthlyGrid の WHERE)と同一に揃える。
        YearMonth ym = com.ses.common.util.DateUtils.parseYearMonth(workMonth);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        boolean inPeriod = contract.getStartDate() != null && !contract.getStartDate().isAfter(monthEnd)
                && (contract.getEndDate() == null || !contract.getEndDate().isBefore(monthStart));
        boolean statusOk = StatusConstants.CONTRACT_ACTIVE.equals(contract.getStatus())
                || StatusConstants.CONTRACT_ENDED.equals(contract.getStatus());
        // ガードは「新規に」期間外・非稼動の実績を作らせないための縦深防御。既存レコードの更新は
        // 免除する（解約で end_date が短縮された契約の既存実績が編集不可・自動再確定の三すくみに
        // なるのを防ぐ。review-fixes G2）。
        if (record == null && (!inPeriod || !statusOk)) {
            throw BusinessException.of("error.workRecord.contractNotBillable");
        }

        // 対象月に有効な単価を解決する（履歴があれば期間別単価、無ければ契約の現在単価）。
        BigDecimal sellingPrice = contract.getSellingPrice();
        BigDecimal costPrice = contract.getCostPrice();
        if (priceResolver != null) {
            com.ses.service.billing.ContractPriceResolver.ResolvedPrice rp = priceResolver.resolve(contract, ym);
            sellingPrice = rp.getSellingPrice();
            costPrice = rp.getCostPrice();
        }

        BigDecimal billingAmount = SettlementCalculator.calc(
                sellingPrice,
                contract.getSettlementHoursMin(),
                contract.getSettlementHoursMax(),
                actualHours
        );

        BigDecimal paymentAmount = null;
        if (costPrice != null) {
            paymentAmount = SettlementCalculator.calc(
                    costPrice,
                    contract.getSettlementHoursMin(),
                    contract.getSettlementHoursMax(),
                    actualHours
            );
        }

        boolean isNew = (record == null || record.getId() == null);
        if (isNew) {
            record = new WorkRecord();
            record.setContractId(contractId);
            record.setWorkMonth(workMonth);
            record.setStatus("入力中");
        }

        record.setActualHours(actualHours);
        record.setBillingAmount(billingAmount);
        record.setPaymentAmount(paymentAmount);
        record.setRemarks(remarks);
        // 入力レコード作成時点で帰属を固定する。後日の異動後に過去月を遅延承認しても
        // 当時の帰属を維持し、明示的にNULLだった帰属もfallbackで再解決しない。
        freezeAccountingDimension(record, contract);

        try {
            this.saveOrUpdate(record);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of("error.workRecord.userNotFound2");
        }

        if (!isNew && record.getPaymentAmount() != null) {
            String employmentType = baseMapper.selectEmploymentTypeByContractId(record.getContractId());
            if ("BP".equals(employmentType)) {
                syncRootBpAmount(record);
            }
        }

        return record;
    }

    @Override
    @Transactional
    public void confirmMonth(String workMonth) {
        com.ses.common.util.DateUtils.parseYearMonth(workMonth);
        checkClosing(workMonth);
        // 入力中・提出済 を一括確定対象とする。差戻し（＝数値誤りの明示フラグ）は黙って確定させない。
        List<WorkRecord> records = baseMapper.selectList(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth)
                .in("status", "入力中", "提出済"));

        if (records.isEmpty()) {
            return;
        }

        // ロック順序の統一: Contract -> WorkRecord。デッドロック防止のため contractId 順でロック
        List<Long> contractIds = records.stream().map(WorkRecord::getContractId).distinct().sorted().collect(Collectors.toList());
        Map<Long, Contract> lockedContracts = new java.util.HashMap<>();
        for (Long cid : contractIds) {
            Contract lockedContract = contractMapper.selectByIdForUpdate(cid);
            if (lockedContract != null) {
                lockedContracts.put(cid, lockedContract);
            }
        }

        // ロック後に再取得し、並行する revisePrice や saveHours の最新単価・状態を反映する。
        // InnoDB REPEATABLE READ では最初の SELECT で snapshot が固定されるため、
        // FOR UPDATE による current read が必要（ロック待ち中に commit された値を確実に読む）。
        List<Long> recordIds = records.stream().map(WorkRecord::getId).distinct().sorted().collect(Collectors.toList());
        records = recordIds.stream()
                .map(baseMapper::selectByIdForUpdate)
                .filter(r -> r != null && ("入力中".equals(r.getStatus()) || "提出済".equals(r.getStatus())))
                .collect(Collectors.toList());

        for (WorkRecord record : records) {
            // 勤怠を確定した瞬間に組織・原価部門を凍結する。月次締めが翌月に行われても、
            // その間の異動・契約単価/原価部門変更で過去月の帰属が変わらないようにする。
            freezeAccountingDimension(record, lockedContracts.get(record.getContractId()));
            // updateById ではなく、ステータスのみを条件付き(CAS)で安全に更新する。
            // 組織・原価部門は明示的にSETし、NULLも「未配賦」として凍結する。
            UpdateWrapper<WorkRecord> statusUpdate = new UpdateWrapper<WorkRecord>()
                    .eq("id", record.getId())
                    .in("status", "入力中", "提出済")
                    .set("status", "確定")
                    .set("organization_id", record.getOrganizationId())
                    .set("cost_center_id", record.getCostCenterId())
                    .set("accounting_dimension_frozen", 1);
            int updated = baseMapper.update(null, statusUpdate);
            if (updated == 1) {
                record.setStatus("確定");
            }
        }

        // BP支払を生成(雇用形態がBPの要員に紐づく契約の確定実績について)
        for (WorkRecord record : records) {
            if ("確定".equals(record.getStatus())) {
                String employmentType = baseMapper.selectEmploymentTypeByContractId(record.getContractId());
                if ("BP".equals(employmentType)) {
                    generateOrSyncBpFor(record);
                }
            }
        }
    }

    /** 確定時点の帰属を勤怠レコードへ保存する。旧データはsnapshot側のfallbackを許容する。 */
    private void freezeAccountingDimension(WorkRecord record, Contract contract) {
        if (record == null || contract == null || Integer.valueOf(1).equals(record.getAccountingDimensionFrozen())) {
            return;
        }
        com.ses.entity.Engineer engineer = engineerMapper == null || contract.getEngineerId() == null
                ? null : engineerMapper.selectById(contract.getEngineerId());
        LocalDate asOf = record.getWorkMonth() == null || record.getWorkMonth().isBlank()
                ? LocalDate.now().withDayOfMonth(1)
                : com.ses.common.util.DateUtils.parseYearMonth(record.getWorkMonth()).atDay(1);
        // 履歴所属を先に解決する。要員マスタのorganization_idは現在値なので、
        // 過去月の遅延確認で先に読むと異動後の組織へ過去実績を移してしまう。
        Long organizationId = null;
        if (engineerAccountLinkMapper != null && userOrganizationMapper != null
                && contract.getEngineerId() != null) {
            com.ses.entity.EngineerAccountLink link = engineerAccountLinkMapper
                    .selectByEngineerId(contract.getEngineerId());
            if (link != null && link.getSysUserId() != null) {
                organizationId = userOrganizationMapper.selectPrimaryOrganizationId(link.getSysUserId(), asOf);
            }
        }
        // 直接所属しか持たない要員は当月分だけ現在マスタを使う。過去月に履歴が
        // 無い場合は誤った現組織へ付け替えず「未配賦」のまま凍結する。
        if (organizationId == null && engineer != null
                && !YearMonth.from(asOf).isBefore(YearMonth.now())) {
            organizationId = engineer.getOrganizationId();
        }
        Long costCenterId = !YearMonth.from(asOf).isBefore(YearMonth.now())
                ? contract.getCostCenterId() : null;
        if (costCenterId == null && engineer != null
                && !YearMonth.from(asOf).isBefore(YearMonth.now())) {
            costCenterId = engineer.getCostCenterId();
        }
        record.setOrganizationId(organizationId);
        record.setCostCenterId(costCenterId);
        record.setAccountingDimensionFrozen(1);
    }

    /**
     * BP要員の確定実績1件についてBP支払を生成または同期する（confirmMonth と approve の共通合流点）。
     * 未生成なら1階層目を作成、既存があれば syncRootBpAmount で金額同期する（二重実装禁止）。
     */
    private void generateOrSyncBpFor(WorkRecord record) {
        if (record.getPaymentAmount() == null) {
            return;
        }
        Long count = bpPaymentMapper.selectCount(new QueryWrapper<BpPayment>()
                .eq("work_record_id", record.getId()));
        if (count == 0) {
            BpPayment bp = new BpPayment();
            bp.setWorkRecordId(record.getId());
            bp.setAmount(record.getPaymentAmount());
            bp.setStatus("未払");
            bpPaymentMapper.insert(bp);
        } else {
            // 既存のBP支払がある(入力中段階で手動登録済み等)。1階層目(parent NULL)の金額が
            // 最新の payment_amount とずれていれば、未払なら追従更新し、支払済なら更新せず通知する。
            syncRootBpAmount(record);
        }
    }

    /**
     * 自動生成1階層目(parent_payment_id IS NULL)のBP支払金額を最新の payment_amount に同期する。
     * 未払の1階層目のみ更新し、支払済で不一致の場合は更新せず warn ログ + 通知に留める(確定済み金額を保護)。
     */
    private void syncRootBpAmount(WorkRecord record) {
        List<BpPayment> roots = bpPaymentMapper.selectList(new QueryWrapper<BpPayment>()
                .eq("work_record_id", record.getId())
                .isNull("parent_payment_id")
                .eq("layer_order", 1));
        for (BpPayment root : roots) {
            if (root.getAmount() == null || root.getAmount().compareTo(record.getPaymentAmount()) == 0) {
                continue;
            }
            if ("未払".equals(root.getStatus())) {
                int updated = bpPaymentMapper.update(null, new UpdateWrapper<BpPayment>()
                        .eq("id", root.getId())
                        .eq("status", "未払")
                        .set("amount", record.getPaymentAmount())
                        .setSql("version = version + 1"));
                if (updated == 0) {
                    throw BusinessException.of("error.common.optimisticLock");
                }
            } else {
                log.warn("支払済BP支払(id={})の金額 {} が最新の支払額 {} と不一致ですが、確定済みのため更新しません",
                        root.getId(), root.getAmount(), record.getPaymentAmount());
                publishToRecordOrganizations(record,
                        "BP_AMOUNT_MISMATCH",
                        "BP支払金額の不一致",
                        "[\"notification.msg.BP_AMOUNT_MISMATCH\", \"" + root.getId() + "\"]",
                        com.ses.common.constant.NotificationLinks.INVOICE,
                        "bp-amount-mismatch-" + root.getId());
            }
        }
    }

    @Override
    @Transactional
    public void reopenMonth(String workMonth) {
        com.ses.common.util.DateUtils.parseYearMonth(workMonth);
        checkClosing(workMonth);
        List<WorkRecord> initialRecords = this.list(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth)
                .eq("status", "確定"));

        if (initialRecords.isEmpty()) {
            return;
        }

        List<Long> contractIds = initialRecords.stream().map(WorkRecord::getContractId).distinct().sorted().collect(Collectors.toList());
        for (Long cid : contractIds) {
            contractMapper.selectByIdForUpdate(cid);
        }

        List<Long> recordIds = initialRecords.stream().map(WorkRecord::getId).sorted().collect(Collectors.toList());
        List<WorkRecord> records = recordIds.stream()
                .map(baseMapper::selectByIdForUpdate)
                .filter(r -> r != null && "確定".equals(r.getStatus()))
                .collect(Collectors.toList());

        if (records.isEmpty()) {
            return;
        }

        // 検収済みwork recordの再openには検収取消承認が必要（R3.4）。
        for (WorkRecord record : records) {
            assertAcceptanceNotAccepted(record.getContractId(), record.getWorkMonth());
        }

        List<Long> ids = records.stream().map(WorkRecord::getId).collect(Collectors.toList());

        List<String> nos = invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(ids);
        if (!nos.isEmpty()) {
            throw BusinessException.of("error.workRecord.invoicedDelete2", String.join(", ", nos));
        }

        Long paidCount = bpPaymentMapper.selectCount(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "支払済"));
        if (paidCount > 0) {
            throw BusinessException.of("error.workRecord.paidBpDelete", paidCount);
        }

        // 手動登録された多段BP階層(2次請以降=layer_order>1、または親を持つ行)は自動再生成で復元できないため、
        // 未払であっても黙って物理削除せず、存在すれば解除を拒否する(手動データの破壊防止)。
        List<BpPayment> manualTiers = bpPaymentMapper.selectList(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "未払")
                .and(w -> w.gt("layer_order", 1).or().isNotNull("parent_payment_id")));
        if (!manualTiers.isEmpty()) {
            throw BusinessException.of("error.workRecord.manualBpDelete", manualTiers.size());
        }

        for (WorkRecord record : records) {
            record.setStatus("入力中");
        }
        this.updateBatchById(records);

        bpPaymentMapper.delete(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "未払"));
    }

    /**
     * 検収済みwork recordの再open/金額変更を拒否する（R3.4）。
     * 検収取消は承認経由（acceptance.cancel）で行い、承認適用後に差戻し状態となってから編集可能。
     */
    private void assertAcceptanceNotAccepted(Long contractId, String workMonth) {
        if (acceptanceMapper == null || contractId == null || workMonth == null) {
            return;
        }
        com.ses.entity.Acceptance acceptance = acceptanceMapper.selectByContractAndMonth(contractId, workMonth);
        if (acceptance != null && "検収済".equals(acceptance.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.approvalRequiredForEdit");
        }
    }

    // ===== 要員セルフサービス勤怠（engineer-self-service-timesheet / P1） =====

    @Override
    @Transactional
    public WorkRecord saveDaily(Long contractId, String workMonth, WorkRecordDaily daily) {
        checkClosing(workMonth);
        if (!YearMonth.from(daily.getWorkDate()).equals(com.ses.common.util.DateUtils.parseYearMonth(workMonth))) {
            throw BusinessException.of("error.workRecord.invalidMonth");
        }
        
        Contract contract = contractMapper.selectByIdForUpdate(contractId);
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract");
        }
        WorkRecord record = baseMapper.selectByContractIdAndMonthForUpdate(contractId, workMonth);
        assertContractScope(contract, workMonth, record);
        assertAcceptanceNotAccepted(contractId, workMonth);


        if (contract.getStartDate() != null && daily.getWorkDate().isBefore(contract.getStartDate())) {
            throw BusinessException.of("error.workRecord.contractNotBillable");
        }
        if (contract.getEndDate() != null && daily.getWorkDate().isAfter(contract.getEndDate())) {
            throw BusinessException.of("error.workRecord.contractNotBillable");
        }
        if (daily.getStartTime() == null || daily.getEndTime() == null) {
            throw BusinessException.of("error.workRecord.dailyInvalidTime");
        }
        if (daily.getBreakMinutes() == null) {
            daily.setBreakMinutes(0);
        }
        if (daily.getBreakMinutes() < 0 || daily.getBreakMinutes() > 1440) {
            throw BusinessException.of("error.workRecord.dailyInvalidTime");
        }



        if (record != null && "確定".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.confirmedEdit2");
        }
        if (record != null && "提出済".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.submittedEdit");
        }

        BigDecimal worked = computeWorkedHours(daily);

        // レコードが無ければ日次由来で作成（合計0で採番・期間検証を通す）。
        if (record == null) {
            record = saveHoursInternal(contractId, workMonth, BigDecimal.ZERO, null, true);
        }

        WorkRecordDaily existing = workRecordDailyMapper.selectOne(new QueryWrapper<WorkRecordDaily>()
                .eq("work_record_id", record.getId())
                .eq("work_date", daily.getWorkDate()));
        if (existing == null) {
            daily.setId(null);
            daily.setWorkRecordId(record.getId());
            daily.setWorkedHours(worked);
            daily.setBreakMinutes(daily.getBreakMinutes() != null ? daily.getBreakMinutes() : 0);
            workRecordDailyMapper.insert(daily);
        } else {
            existing.setStartTime(daily.getStartTime());
            existing.setEndTime(daily.getEndTime());
            existing.setBreakMinutes(daily.getBreakMinutes() != null ? daily.getBreakMinutes() : 0);
            existing.setWorkedHours(worked);
            existing.setRemarks(daily.getRemarks());
            workRecordDailyMapper.updateById(existing);
        }

        // 合計を再計算し既存精算ロジックへ連動する。
        BigDecimal total = sumDaily(record.getId());
        return saveHoursInternal(contractId, workMonth, total, record.getRemarks(), true);
    }

    @Override
    @Transactional
    public void deleteDaily(Long contractId, String workMonth, LocalDate workDate) {
        checkClosing(workMonth);
        Contract contract = contractMapper.selectByIdForUpdate(contractId);
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract");
        }
        WorkRecord record = baseMapper.selectByContractIdAndMonthForUpdate(contractId, workMonth);
        if (record == null) {
            return;
        }
        if ("確定".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.confirmedEdit2");
        }
        if ("提出済".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.submittedEdit");
        }
        workRecordDailyMapper.delete(new QueryWrapper<WorkRecordDaily>()
                .eq("work_record_id", record.getId())
                .eq("work_date", workDate));
        BigDecimal total = sumDaily(record.getId());
        saveHoursInternal(contractId, workMonth, total, record.getRemarks(), true);
    }

    @Override
    public List<WorkRecordDaily> listDaily(Long workRecordId) {
        assertAllowed(workRecordId);
        return workRecordDailyMapper.selectList(new QueryWrapper<WorkRecordDaily>()
                .eq("work_record_id", workRecordId)
                .orderByAsc("work_date"));
    }

    private BigDecimal sumDaily(Long workRecordId) {
        BigDecimal total = BigDecimal.ZERO;
        for (WorkRecordDaily d : listDaily(workRecordId)) {
            if (d.getWorkedHours() != null) {
                total = total.add(d.getWorkedHours());
            }
        }
        return total;
    }

    /** 開始/終了/休憩、または直接入力の稼働時間から worked_hours を確定・検証する。 */
    private BigDecimal computeWorkedHours(WorkRecordDaily daily) {
        BigDecimal hours;
        if (daily.getStartTime() != null && daily.getEndTime() != null) {
            long minutes = Duration.between(daily.getStartTime(), daily.getEndTime()).toMinutes();
            if (minutes < 0) {
                minutes += 24 * 60; // 翌日跨ぎ対応
            }
            minutes -= (daily.getBreakMinutes() != null ? daily.getBreakMinutes() : 0);
            if (minutes < 0) {
                throw BusinessException.of("error.workRecord.dailyInvalidTime");
            }
            hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        } else if (daily.getWorkedHours() != null) {
            hours = daily.getWorkedHours();
        } else {
            throw BusinessException.of("error.workRecord.dailyInvalidTime");
        }
        if (hours.signum() < 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0) {
            throw BusinessException.of("error.workRecord.dailyInvalidTime");
        }
        return hours;
    }

    private void requireTransition(WorkRecord record, String newStatus) {
        if (!ALLOWED_STATUS.getOrDefault(record.getStatus(), Set.of()).contains(newStatus)) {
            throw BusinessException.of("error.workRecord.statusTransitionInvalid",
                    record.getStatus(), newStatus);
        }
    }

    @Override
    @Transactional
    public void submitByMonth(Long contractId, String workMonth) {
        checkClosing(workMonth);
        WorkRecord w = baseMapper.selectOne(new QueryWrapper<WorkRecord>()
                .eq("contract_id", contractId)
                .eq("work_month", workMonth), false);
        if (w == null) {
            // 0h提出も契約期間・状態検証済みの内部保存経路を通す（R3R-15）。
            w = saveHoursInternal(contractId, workMonth, BigDecimal.ZERO, null, false);
        }
        submit(w.getId());
    }

    @Override
    @Transactional
    public void submit(Long workRecordId) {
        assertAllowed(workRecordId);
        WorkRecord record = this.getById(workRecordId);
        if (record == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        checkClosing(record.getWorkMonth());

        Contract contract = contractMapper.selectByIdForUpdate(record.getContractId());
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract");
        }
        record = this.getById(workRecordId); // 再取得して最新状態を反映

        requireTransition(record, "提出済");
        // 条件付きUPDATE（CAS）。再提出で差戻しコメントをクリアする（R3R-10/R3R-12）。
        int updated = baseMapper.update(null, new UpdateWrapper<WorkRecord>()
                .eq("id", workRecordId)
                .in("status", "入力中", "差戻し")
                .set("status", "提出済")
                .set("reject_comment", null));
        if (updated != 1) {
            throw BusinessException.of(409, "error.workRecord.concurrentModified");
        }
        publishToRecordOrganizations(record,
                "TIMESHEET_SUBMITTED",
                "勤怠が提出されました",
                "[\"notification.msg.TIMESHEET_SUBMITTED\", \"" + record.getWorkMonth() + "\"]",
                NotificationLinks.WORK_RECORD,
                "timesheet-submitted-" + record.getId() + "-" + System.currentTimeMillis());
    }

    private void publishToRecordOrganizations(WorkRecord record, String type, String title, String message,
                                              String linkUrl, String dedupeKey) {
        if (record == null || record.getContractId() == null) {
            return;
        }
        List<Long> organizationIds = contractMapper.selectOrganizationIdsByContractIds(
                List.of(record.getContractId()));
        if (organizationIds == null) {
            return;
        }
        for (Long organizationId : organizationIds) {
            notificationService.publishToOrganization(organizationId, type, title, message, linkUrl,
                    dedupeKey + "#o" + organizationId);
        }
    }

    @Override
    @Transactional
    public void approve(Long workRecordId) {
        assertAllowed(workRecordId);
        WorkRecord record = this.getById(workRecordId);
        if (record == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        checkClosing(record.getWorkMonth());

        Contract contract = contractMapper.selectByIdForUpdate(record.getContractId());
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract");
        }
        // Contractロック後に再度行ロックを取得して、月次締めや他者との競合を防ぐ（A7-06/R7-03）
        record = baseMapper.selectByIdForUpdate(workRecordId);

        requireTransition(record, "確定");
        // 単件承認も月次一括確定と同じ帰属凍結を行い、NULLを含む次元を明示的に保存する。
        freezeAccountingDimension(record, contract);
        int updated = baseMapper.update(null, new UpdateWrapper<WorkRecord>()
                .eq("id", workRecordId)
                .eq("status", "提出済")
                .set("status", "確定")
                .set("organization_id", record.getOrganizationId())
                .set("cost_center_id", record.getCostCenterId())
                .set("accounting_dimension_frozen", 1));
        if (updated != 1) {
            // 再試行。状態が変わったか、他のトランザクションが確定した
            throw BusinessException.of(409, "error.workRecord.concurrentModified");
        }
        record.setStatus("確定");

        // confirmMonth と同じBP生成後続処理を単契約分行う（BP要員のみ）。
        String employmentType = baseMapper.selectEmploymentTypeByContractId(record.getContractId());
        if ("BP".equals(employmentType)) {
            generateOrSyncBpFor(record);
        }
    }

    @Override
    @Transactional
    public void reject(Long workRecordId, String comment) {
        assertAllowed(workRecordId);
        WorkRecord record = this.getById(workRecordId);
        if (record == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        checkClosing(record.getWorkMonth());
        
        Contract contract = contractMapper.selectByIdForUpdate(record.getContractId());
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract");
        }
        // Contractロック後に再度行ロックを取得して、月次締めや他者との競合を防ぐ（A7-06/R7-03）
        record = baseMapper.selectByIdForUpdate(workRecordId);
        
        // 差戻しコメントはtrim後必須・最大500文字（R3R-12）。
        String trimmed = comment == null ? "" : comment.trim();
        if (trimmed.isEmpty()) {
            throw BusinessException.of(400, "error.workRecord.rejectCommentRequired");
        }
        if (trimmed.length() > 500) {
            throw BusinessException.of(400, "error.workRecord.rejectCommentTooLong");
        }
        requireTransition(record, "差戻し");
        // 条件付きUPDATE（CAS）で差戻しコメントを保存する（R3R-10/R3R-12）。
        int updated = baseMapper.update(null, new UpdateWrapper<WorkRecord>()
                .eq("id", workRecordId)
                .eq("status", "提出済")
                .set("status", "差戻し")
                .set("reject_comment", trimmed));
        if (updated != 1) {
            throw BusinessException.of(409, "error.workRecord.concurrentModified");
        }
        // 差戻し通知は対象要員本人だけに配信する（R3R-11）。
        Long engineerUserId = resolveEngineerUserId(record.getContractId());
        if (engineerUserId != null) {
            notificationService.publishToUser(
                    engineerUserId,
                    "TIMESHEET_REJECTED",
                    "勤怠が差し戻されました",
                    "[\"notification.msg.TIMESHEET_REJECTED\", \"" + record.getWorkMonth() + "\"]",
                    NotificationLinks.MY_TIMESHEET,
                    "timesheet-rejected-" + record.getId() + "-" + System.currentTimeMillis(),
                    "my-timesheet");
        } else {
            // 紐付け不明の場合は全体配信せず警告ログに留める（他要員への漏洩防止）。
            log.warn("勤怠差戻し通知の宛先要員アカウントが解決できません: workRecordId={}", workRecordId);
        }
    }

    /** 契約IDから稼働要員に紐付くログインユーザーIDを解決する（未紐付けは null）。 */
    private Long resolveEngineerUserId(Long contractId) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null || contract.getEngineerId() == null) {
            return null;
        }
        com.ses.entity.EngineerAccountLink link =
                engineerAccountLinkMapper.selectByEngineerId(contract.getEngineerId());
        return link == null ? null : link.getSysUserId();
    }
}
