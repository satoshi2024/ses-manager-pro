package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface WorkRecordService extends IService<WorkRecord> {
    List<WorkRecordGridDto> monthlyGrid(String workMonth);
    WorkRecord saveHours(Long contractId, String workMonth, BigDecimal actualHours, String remarks);
    void confirmMonth(String workMonth);
    void reopenMonth(String workMonth);

    // ===== 要員セルフサービス勤怠（engineer-self-service-timesheet / P1） =====
    /** 日次勤怠を保存(upsert)し、月次合計を再計算して既存精算ロジックへ連動する。 */
    WorkRecord saveDaily(Long contractId, String workMonth, WorkRecordDaily daily);
    /** 日次勤怠を削除し、月次合計を再計算する。 */
    void deleteDaily(Long contractId, String workMonth, LocalDate workDate);
    /** 実績の日次明細を取得する。 */
    List<WorkRecordDaily> listDaily(Long workRecordId);
    /** 入力中/差戻し→提出済（承認者へ通知）。 */
    void submit(Long workRecordId);
    /** 提出済→確定（confirmMonth と同じBP生成後続処理を単契約分）。 */
    void approve(Long workRecordId);
    /** 提出済→差戻し（要員へコメント付き通知）。 */
    void reject(Long workRecordId, String comment);
}
