package com.ses.service.billing;

import com.ses.dto.reconciliation.PendingDepositDto;
import com.ses.dto.reconciliation.ReconciliationFetchResultDto;

import java.time.LocalDate;
import java.util.List;

/**
 * 入金消込の半自動化（FR-09）。
 * freee経由で銀行入金明細を取得し、請求書（未入金/一部入金）に金額・振込名義・時期で突合する。
 * 高信頼一致（金額一致＋名義一致）は自動消込、それ以外は候補提示または保留とし、
 * 最終確定は必ず人（{@link #apply}）が行う。
 */
public interface PaymentReconciliationService {

    /**
     * freeeから入金明細を取得し、新規分を保存した上で未消込分の突合・自動消込を行う。
     * @param from 開始日（null可・省略時は直近30日）
     * @param to   終了日（null可・省略時は当日）
     */
    ReconciliationFetchResultDto fetchAndReconcile(LocalDate from, LocalDate to);

    /** 未消込の入金一覧を、突合候補（信頼度順）付きで返す。 */
    List<PendingDepositDto> listPending();

    /** 入金を指定の請求書へ手動で消込確定する（候補提示からの選択・保留の手動割当の両方に使用）。 */
    void apply(Long depositId, Long invoiceId);
}
