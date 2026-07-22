package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.WorkRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkRecordServiceImplTest {

    @Mock
    private WorkRecordMapper workRecordMapper;

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private BpPaymentMapper bpPaymentMapper;

    @Mock
    private InvoiceItemMapper invoiceItemMapper;

    @Mock
    private com.ses.service.NotificationService notificationService;

    @Mock
    private com.ses.mapper.WorkRecordDailyMapper workRecordDailyMapper;

    @Mock
    private com.ses.service.MonthlyClosingService monthlyClosingService;

    @Mock
    private com.ses.mapper.EngineerAccountLinkMapper engineerAccountLinkMapper;

    @InjectMocks
    private WorkRecordServiceImpl workRecordService;

    @BeforeEach
    void setUp() {
        // ServiceImpl の baseMapper フィールドを手動で注入
        ReflectionTestUtils.setField(workRecordService, "baseMapper", workRecordMapper);
    }

    /**
     * 精算計算が正しく行われること
     */
    @Test
    void testSaveHours_正常に保存できる() {
        Long contractId = 1L;
        String workMonth = "2026-07";

        // まだ実績なし
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        // 契約データを準備
        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setSettlementHoursMin(new BigDecimal("140"));
        contract.setSettlementHoursMax(new BigDecimal("180"));
        contract.setStartDate(LocalDate.of(2026, 7, 1));
        contract.setStatus("稼動中");
        when(contractMapper.selectByIdForUpdate(contractId)).thenReturn(contract);
        when(workRecordMapper.insert(any(WorkRecord.class))).thenReturn(1);

        // 実績150時間（範囲内）→ 基本料金のみ
        WorkRecord record = workRecordService.saveHours(contractId, workMonth, new BigDecimal("150"), "テスト");

        assertThat(record).isNotNull();
        assertThat(record.getBillingAmount()).isEqualByComparingTo("800000");
        assertThat(record.getPaymentAmount()).isEqualByComparingTo("600000");
        assertThat(record.getStatus()).isEqualTo("入力中");
    }

    /**
     * error.workRecord.confirmedEditの月は編集できないこと
     */
    @Test
    void testSaveHours_confirmedRecordThrowsException() {
        Long contractId = 1L;
        String workMonth = "2026-07";

        // すでにerror.workRecord.confirmedEditの実績が存在
        WorkRecord existingRecord = new WorkRecord();
        existingRecord.setId(10L);
        existingRecord.setStatus("確定");
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(existingRecord);

        assertThatThrownBy(() -> workRecordService.saveHours(contractId, workMonth, new BigDecimal("160"), "再入力"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.confirmedEdit");
    }

    /**
     * BP契約の確定時に t_bp_payment レコードを生成すること
     */
    @Test
    void testConfirmMonth_BP契約の支払が生成される() {
        String workMonth = "2026-07";

        // 確定対象の実績
        WorkRecord record = new WorkRecord();
        record.setId(1L);
        record.setStatus("入力中");
        record.setPaymentAmount(new BigDecimal("600000"));

        when(workRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        // baseMapper.updateById を使うため Mapper にスタブ
        when(workRecordMapper.updateById(any(WorkRecord.class))).thenReturn(1);

        // グリッドDTOでBPを返す
        WorkRecordGridDto dto = new WorkRecordGridDto();
        dto.setWorkRecordId(1L);
        dto.setEmploymentType("BP");
        when(workRecordMapper.selectMonthlyGrid(eq(workMonth), any())).thenReturn(Collections.singletonList(dto));
        when(workRecordMapper.selectEmploymentTypeByContractId(any())).thenReturn("BP");

        // BP支払未存在
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);
        when(bpPaymentMapper.insert(any(BpPayment.class))).thenReturn(1);

        workRecordService.confirmMonth(workMonth);

        verify(bpPaymentMapper, times(1)).insert(any(BpPayment.class));
    }

    @Test
    void testReopenMonth_支払済BP支払ありで例外() {
        String workMonth = "2026-07";
        WorkRecord r1 = new WorkRecord(); r1.setId(1L); r1.setStatus("確定");
        
        WorkRecordServiceImpl spyService = spy(workRecordService);
        doReturn(Collections.singletonList(r1)).when(spyService).list((com.baomidou.mybatisplus.core.conditions.Wrapper<WorkRecord>) any());
        
        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> spyService.reopenMonth(workMonth))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.paidBpDelete");
                
        verify(spyService, never()).updateBatchById(any());
    }

    @Test
    void testReopenMonth_未払のみ成功() {
        String workMonth = "2026-07";
        WorkRecord r1 = new WorkRecord(); r1.setId(1L); r1.setStatus("確定");
        
        WorkRecordServiceImpl spyService = spy(workRecordService);
        doReturn(Collections.singletonList(r1)).when(spyService).list((com.baomidou.mybatisplus.core.conditions.Wrapper<WorkRecord>) any());
        doReturn(true).when(spyService).updateBatchById(any());
        
        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);

        spyService.reopenMonth(workMonth);

        assertThat(r1.getStatus()).isEqualTo("入力中");
        verify(spyService, times(1)).updateBatchById(any());
        verify(bpPaymentMapper, times(1)).delete(any());
    }

    @Test
    void testReopenMonth_有効請求書の明細ありで例外() {
        String workMonth = "2026-07";
        WorkRecord r1 = new WorkRecord(); r1.setId(1L); r1.setStatus("確定");
        
        WorkRecordServiceImpl spyService = spy(workRecordService);
        doReturn(Collections.singletonList(r1)).when(spyService).list((com.baomidou.mybatisplus.core.conditions.Wrapper<WorkRecord>) any());
        
        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.singletonList("INV-202607-0001"));

        assertThatThrownBy(() -> spyService.reopenMonth(workMonth))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.invoicedDelete2");
    }

    @Test
    void testReopenMonth_取消済み請求書のみで成功() {
        String workMonth = "2026-07";
        WorkRecord r1 = new WorkRecord(); r1.setId(1L); r1.setStatus("確定");
        
        WorkRecordServiceImpl spyService = spy(workRecordService);
        doReturn(Collections.singletonList(r1)).when(spyService).list((com.baomidou.mybatisplus.core.conditions.Wrapper<WorkRecord>) any());
        doReturn(true).when(spyService).updateBatchById(any());
        
        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);

        spyService.reopenMonth(workMonth);

        assertThat(r1.getStatus()).isEqualTo("入力中");
        verify(spyService, times(1)).updateBatchById(any());
    }

    // ===== R4: 手動BP階層の保護 / confirm 金額同期 =====

    @Test
    void testReopenMonth_手動BP階層ありで拒否() {
        String workMonth = "2026-07";
        WorkRecord r1 = new WorkRecord(); r1.setId(1L); r1.setStatus("確定");

        WorkRecordServiceImpl spyService = spy(workRecordService);
        doReturn(Collections.singletonList(r1)).when(spyService).list((com.baomidou.mybatisplus.core.conditions.Wrapper<WorkRecord>) any());

        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.emptyList());
        // 支払済はなし
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);
        // 未払の2階層目(手動登録)が存在
        BpPayment tier2 = new BpPayment();
        tier2.setId(5L); tier2.setLayerOrder(2); tier2.setStatus("未払");
        when(bpPaymentMapper.selectList(any())).thenReturn(Collections.singletonList(tier2));

        assertThatThrownBy(() -> spyService.reopenMonth(workMonth))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.manualBpDelete");

        verify(spyService, never()).updateBatchById(any());
        verify(bpPaymentMapper, never()).delete(any());
    }

    @Test
    void testConfirmMonth_既存未払BP1階層目の金額を追従更新する() {
        String workMonth = "2026-07";

        WorkRecord record = new WorkRecord();
        record.setId(1L);
        record.setStatus("入力中");
        record.setPaymentAmount(new BigDecimal("650000"));

        when(workRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(workRecordMapper.updateById(any(WorkRecord.class))).thenReturn(1);

        WorkRecordGridDto dto = new WorkRecordGridDto();
        dto.setWorkRecordId(1L);
        dto.setEmploymentType("BP");
        when(workRecordMapper.selectMonthlyGrid(eq(workMonth), any())).thenReturn(Collections.singletonList(dto));
        when(workRecordMapper.selectEmploymentTypeByContractId(any())).thenReturn("BP");

        // 既存BP支払あり(手動登録などで入力中に生成済み)
        when(bpPaymentMapper.selectCount(any())).thenReturn(1L);
        BpPayment root = new BpPayment();
        root.setId(9L);
        root.setStatus("未払");
        root.setParentPaymentId(null);
        root.setAmount(new BigDecimal("600000")); // 旧金額(payment_amount と不一致)
        when(bpPaymentMapper.selectList(any())).thenReturn(Collections.singletonList(root));
        when(bpPaymentMapper.update(any(), any())).thenReturn(1);

        workRecordService.confirmMonth(workMonth);

        // 新規insertはせず、未払1階層目の金額を追従更新する
        verify(bpPaymentMapper, never()).insert(any(BpPayment.class));
        verify(bpPaymentMapper, times(1)).update(isNull(), any());
    }

    @Test
    void testConfirmMonth_支払済BP1階層目の不一致は更新せず通知する() {
        String workMonth = "2026-07";

        WorkRecord record = new WorkRecord();
        record.setId(1L);
        record.setStatus("入力中");
        record.setPaymentAmount(new BigDecimal("650000"));

        when(workRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(workRecordMapper.updateById(any(WorkRecord.class))).thenReturn(1);

        WorkRecordGridDto dto = new WorkRecordGridDto();
        dto.setWorkRecordId(1L);
        dto.setEmploymentType("BP");
        when(workRecordMapper.selectMonthlyGrid(eq(workMonth), any())).thenReturn(Collections.singletonList(dto));
        when(workRecordMapper.selectEmploymentTypeByContractId(any())).thenReturn("BP");

        when(bpPaymentMapper.selectCount(any())).thenReturn(1L);
        BpPayment root = new BpPayment();
        root.setId(9L);
        root.setStatus("支払済");
        root.setParentPaymentId(null);
        root.setAmount(new BigDecimal("600000"));
        when(bpPaymentMapper.selectList(any())).thenReturn(Collections.singletonList(root));

        workRecordService.confirmMonth(workMonth);

        // 支払済は更新せず通知に留める。リンク先は /invoice(存在するページ)であること。
        verify(bpPaymentMapper, never()).update(any(), any());
        verify(notificationService, times(1)).publish(
                eq("BP_AMOUNT_MISMATCH"), any(), any(), eq("/invoice"), any());
    }

    @Test
    void testConfirmMonth_invalidMonthFormatThrows400() {
        assertThatThrownBy(() -> workRecordService.confirmMonth("2026-99"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void testConfirmMonth_金額同期は自動生成1階層目_layerOrder1_のみを対象にする() {
        String workMonth = "2026-07";

        WorkRecord record = new WorkRecord();
        record.setId(1L);
        record.setStatus("入力中");
        record.setPaymentAmount(new BigDecimal("650000"));

        when(workRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(workRecordMapper.updateById(any(WorkRecord.class))).thenReturn(1);

        WorkRecordGridDto dto = new WorkRecordGridDto();
        dto.setWorkRecordId(1L);
        dto.setEmploymentType("BP");
        when(workRecordMapper.selectMonthlyGrid(eq(workMonth), any())).thenReturn(Collections.singletonList(dto));
        when(workRecordMapper.selectEmploymentTypeByContractId(any())).thenReturn("BP");

        when(bpPaymentMapper.selectCount(any())).thenReturn(1L);
        org.mockito.ArgumentCaptor<QueryWrapper> captor = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        when(bpPaymentMapper.selectList(captor.capture())).thenReturn(Collections.emptyList());

        workRecordService.confirmMonth(workMonth);

        // 1階層目ルックアップの条件に layer_order = 1 が含まれること(design R4 準拠。親なし手動階層を除外)
        assertThat(captor.getValue().getTargetSql()).contains("layer_order");
        // 対象行が無ければ金額更新は行わない
        verify(bpPaymentMapper, never()).update(any(), any());
    }

    @Test
    void testReopenMonth_invalidMonthFormatThrows400() {
        assertThatThrownBy(() -> workRecordService.reopenMonth("invalid-month"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void testSaveHours_請求済み実績は編集不可() {
        Long contractId = 1L;
        String workMonth = "2026-07";

        WorkRecord existingRecord = new WorkRecord();
        existingRecord.setId(10L);
        existingRecord.setStatus("入力中");
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(existingRecord);
        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.singletonList("INV-202607-0001"));

        assertThatThrownBy(() -> workRecordService.saveHours(contractId, workMonth, new BigDecimal("160"), "再入力"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.invoicedEdit2");
    }

    // ===== S7: 工数入力の契約期間・状態検証（縦深防御） =====

    private Contract billableContract(Long id, LocalDate start, LocalDate end, String status) {
        Contract c = new Contract();
        c.setId(id);
        c.setSellingPrice(new BigDecimal("800000"));
        c.setSettlementHoursMin(new BigDecimal("140"));
        c.setSettlementHoursMax(new BigDecimal("180"));
        c.setStartDate(start);
        c.setEndDate(end);
        c.setStatus(status);
        return c;
    }

    @Test
    void testSaveHours_契約期間外の月は拒否() {
        Long contractId = 1L;
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        // 契約期間 2026-08〜2026-09。対象月 2026-07 は期間前。
        when(contractMapper.selectByIdForUpdate(contractId))
                .thenReturn(billableContract(contractId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30), "稼動中"));

        assertThatThrownBy(() -> workRecordService.saveHours(contractId, "2026-07", new BigDecimal("150"), "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.contractNotBillable");
    }

    @Test
    void testSaveHours_準備中契約は拒否() {
        Long contractId = 1L;
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(contractMapper.selectByIdForUpdate(contractId))
                .thenReturn(billableContract(contractId, LocalDate.of(2026, 7, 1), null, "準備中"));

        assertThatThrownBy(() -> workRecordService.saveHours(contractId, "2026-07", new BigDecimal("150"), "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.contractNotBillable");
    }

    @Test
    void testSaveHours_終了契約_期間内は登録できる() {
        Long contractId = 1L;
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(contractMapper.selectByIdForUpdate(contractId))
                .thenReturn(billableContract(contractId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "終了"));
        when(workRecordMapper.insert(any(WorkRecord.class))).thenReturn(1);

        WorkRecord record = workRecordService.saveHours(contractId, "2026-07", new BigDecimal("150"), "x");

        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo("入力中");
    }

    @Test
    void testSaveHours_解約で期間短縮された契約でも既存レコードは更新できる() {
        // G2: 既存レコードの更新はガード免除（解約で end_date 短縮→編集不可の三すくみを防ぐ）。
        Long contractId = 1L;
        WorkRecord existing = new WorkRecord();
        existing.setId(10L);
        existing.setStatus("入力中");
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(existing);
        when(invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(any())).thenReturn(Collections.emptyList());
        // 解約で 2026-03 まで短縮された契約。対象月 2026-07 は期間外＋状態も解約。
        when(contractMapper.selectByIdForUpdate(contractId))
                .thenReturn(billableContract(contractId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), "解約"));

        WorkRecordServiceImpl spyService = spy(workRecordService);
        doReturn(true).when(spyService).saveOrUpdate(any(WorkRecord.class));

        WorkRecord result = spyService.saveHours(contractId, "2026-07", new BigDecimal("150"), "修正");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(10L);
    }

    @Test
    void testSaveHours_解約済み契約への新規作成は拒否() {
        Long contractId = 1L;
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null); // 新規
        when(contractMapper.selectByIdForUpdate(contractId))
                .thenReturn(billableContract(contractId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "解約"));

        assertThatThrownBy(() -> workRecordService.saveHours(contractId, "2026-07", new BigDecimal("150"), "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.contractNotBillable");
    }

    @Test
    void testSaveHours_月形式不正は拒否() {
        Long contractId = 1L;
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(contractMapper.selectByIdForUpdate(contractId))
                .thenReturn(billableContract(contractId, LocalDate.of(2026, 7, 1), null, "稼動中"));

        // R3R-15: 不正な年月形式は400のBusinessExceptionで拒否される（500にしない）。
        assertThatThrownBy(() -> workRecordService.saveHours(contractId, "2026/07", new BigDecimal("150"), "x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void testSaveHours_UK衝突時にBusinessException() {
        Long contractId = 1L;
        String workMonth = "2026-07";

        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setSettlementHoursMin(new BigDecimal("140"));
        contract.setSettlementHoursMax(new BigDecimal("180"));
        contract.setStartDate(LocalDate.of(2026, 7, 1));
        contract.setStatus("稼動中");
        when(contractMapper.selectByIdForUpdate(contractId)).thenReturn(contract);

        WorkRecordServiceImpl spyService = spy(workRecordService);
        doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry")).when(spyService).saveOrUpdate(any(WorkRecord.class));

        assertThatThrownBy(() -> spyService.saveHours(contractId, workMonth, new BigDecimal("150"), "テスト"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.userNotFound2");
    }

    // ===== 要員セルフサービス勤怠（engineer-self-service-timesheet / P1） =====

    @Test
    void saveHours_dailyManagedMonthRejectsManualTotal() {
        WorkRecord existing = new WorkRecord();
        existing.setId(10L);
        existing.setStatus("入力中");
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(existing);
        when(workRecordDailyMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> workRecordService.saveHours(1L, "2026-07", new BigDecimal("160"), "手動"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.dailyManaged");
    }

    @Test
    void submit_入力中から提出済で通知() {
        WorkRecord r = new WorkRecord();
        r.setId(5L);
        r.setStatus("入力中");
        r.setWorkMonth("2026-07");
        when(workRecordMapper.selectById(5L)).thenReturn(r);
        // R3R-10: 条件付きUPDATE(CAS)へ変更。update件数1で後続処理。
        when(workRecordMapper.update(isNull(), any())).thenReturn(1);

        workRecordService.submit(5L);

        verify(workRecordMapper).update(isNull(), any());
        verify(notificationService).publish(
                eq("TIMESHEET_SUBMITTED"), any(), any(), any(),
                org.mockito.ArgumentMatchers.startsWith("timesheet-submitted-5-"),
                eq("work-record"));
    }

    @Test
    void submit_確定からは不正遷移() {
        WorkRecord r = new WorkRecord();
        r.setId(5L);
        r.setStatus("確定");
        when(workRecordMapper.selectById(5L)).thenReturn(r);
        assertThatThrownBy(() -> workRecordService.submit(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.statusTransitionInvalid");
    }

    @Test
    void approve_提出済から確定しBP生成() {
        WorkRecord r = new WorkRecord();
        r.setId(5L);
        r.setStatus("提出済");
        r.setWorkMonth("2026-07");
        r.setPaymentAmount(new BigDecimal("600000"));
        when(workRecordMapper.selectById(5L)).thenReturn(r);
        // R3R-10: 条件付きUPDATE(CAS)。
        when(workRecordMapper.update(isNull(), any())).thenReturn(1);
        when(workRecordMapper.selectEmploymentTypeByContractId(any())).thenReturn("BP");
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);
        when(bpPaymentMapper.insert(any(BpPayment.class))).thenReturn(1);

        workRecordService.approve(5L);

        assertThat(r.getStatus()).isEqualTo("確定");
        verify(bpPaymentMapper, times(1)).insert(any(BpPayment.class));
    }

    @Test
    void approve_入力中からは不正遷移() {
        WorkRecord r = new WorkRecord();
        r.setId(5L);
        r.setStatus("入力中");
        when(workRecordMapper.selectById(5L)).thenReturn(r);
        assertThatThrownBy(() -> workRecordService.approve(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.statusTransitionInvalid");
    }

    @Test
    void reject_提出済から差戻しで通知() {
        WorkRecord r = new WorkRecord();
        r.setId(5L);
        r.setStatus("提出済");
        r.setContractId(7L);
        r.setRemarks("既存の備考");
        when(workRecordMapper.selectById(5L)).thenReturn(r);
        // R3R-10/12: CASでコメント保存。R3R-11: 対象要員本人へ配信。
        when(workRecordMapper.update(isNull(), any())).thenReturn(1);
        Contract c = new Contract();
        c.setId(7L);
        c.setEngineerId(3L);
        when(contractMapper.selectById(7L)).thenReturn(c);
        com.ses.entity.EngineerAccountLink link = new com.ses.entity.EngineerAccountLink();
        link.setSysUserId(99L);
        when(engineerAccountLinkMapper.selectByEngineerId(3L)).thenReturn(link);

        workRecordService.reject(5L, "工数が誤っています");

        // 業務備考は差戻しコメントで上書きしない（R3R-12）。
        assertThat(r.getRemarks()).isEqualTo("既存の備考");
        verify(notificationService).publishToUser(
                eq(99L), eq("TIMESHEET_REJECTED"), any(), any(), any(),
                org.mockito.ArgumentMatchers.startsWith("timesheet-rejected-5-"),
                eq("my-timesheet"));
    }

    @Test
    void saveDaily_不正な時刻は拒否() {
        WorkRecord existing = new WorkRecord();
        existing.setId(10L);
        existing.setStatus("入力中");
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(existing);
        
        com.ses.entity.Contract mockContract = new com.ses.entity.Contract();
        mockContract.setId(1L);
        when(contractMapper.selectByIdForUpdate(1L)).thenReturn(mockContract);
        com.ses.entity.WorkRecordDaily daily = new com.ses.entity.WorkRecordDaily();
        daily.setWorkDate(LocalDate.of(2026, 7, 1));
        daily.setStartTime(java.time.LocalTime.of(9, 0));
        daily.setEndTime(java.time.LocalTime.of(10, 0));
        daily.setBreakMinutes(120);

        assertThatThrownBy(() -> workRecordService.saveDaily(1L, "2026-07", daily))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.workRecord.dailyInvalidTime");
    }
}
