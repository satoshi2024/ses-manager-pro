package com.ses.service.billing;

import com.ses.dto.reconciliation.BankDepositDto;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-09 入金消込の取込元（口座の入金明細）。
 *
 * <p>消込ロジック（{@link PaymentReconciliationService}）と外部APIの取得経路を分ける。
 * 取得に失敗した場合は部分結果を返さず BusinessException を投げる。</p>
 */
public interface BankDepositSource {

    /** 期間内（両端含む）の入金明細を返す。未接続・期間不正・取得失敗は BusinessException。 */
    List<BankDepositDto> fetch(LocalDate from, LocalDate to);
}
