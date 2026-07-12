package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.billing.SettlementCalculator;
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
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        contract.setSellingPrice(new BigDecimal("80")); // 80万円
        contract.setCostPrice(new BigDecimal("60"));    // 60万円
        contract.setSettlementHoursMin(new BigDecimal("140"));
        contract.setSettlementHoursMax(new BigDecimal("180"));
        when(contractMapper.selectById(contractId)).thenReturn(contract);
        when(workRecordMapper.insert(any(WorkRecord.class))).thenReturn(1);

        // 実績150時間（範囲内）→ 基本料金のみ
        WorkRecord record = workRecordService.saveHours(contractId, workMonth, new BigDecimal("150"), "テスト");

        assertThat(record).isNotNull();
        assertThat(record.getBillingAmount()).isEqualByComparingTo(
                SettlementCalculator.calc(new BigDecimal("80"), new BigDecimal("140"), new BigDecimal("180"), new BigDecimal("150"))
        );
        assertThat(record.getStatus()).isEqualTo("入力中");
    }

    /**
     * 確定済みの月は編集できないこと
     */
    @Test
    void testSaveHours_確定済みはBusinessExceptionをスロー() {
        Long contractId = 1L;
        String workMonth = "2026-07";

        // すでに確定済みの実績が存在
        WorkRecord existingRecord = new WorkRecord();
        existingRecord.setId(10L);
        existingRecord.setStatus("確定");
        when(workRecordMapper.selectOne(any(), anyBoolean())).thenReturn(existingRecord);

        assertThatThrownBy(() -> workRecordService.saveHours(contractId, workMonth, new BigDecimal("160"), "再入力"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("確定済み");
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
        when(workRecordMapper.selectMonthlyGrid(workMonth)).thenReturn(Collections.singletonList(dto));

        // BP支払未存在
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);
        when(bpPaymentMapper.insert(any(BpPayment.class))).thenReturn(1);

        workRecordService.confirmMonth(workMonth);

        verify(bpPaymentMapper, times(1)).insert(any(BpPayment.class));
    }
}
