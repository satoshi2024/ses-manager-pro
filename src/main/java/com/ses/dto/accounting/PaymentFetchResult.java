package com.ses.dto.accounting;

import com.ses.dto.accounting.canonical.CanonicalPaymentSync;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部決済実績の一覧取得結果 (R1-P1-09)。
 * <p>
 * 50ページ上限到達・重複deal ID・取得途中障害をページング結果と共に返し、
 * 月次照合側で fail-closed (readyForClosing = false) を判定する。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFetchResult {

    @Builder.Default
    private List<CanonicalPaymentSync> payments = new ArrayList<>();

    /** freee取引一覧の50ページ (5,000件) 上限に到達した (次ページが存在し得る)。 */
    private boolean pageCapReached;

    /** ページ跨ぎで重複する deal ID を検出した (外部データ不整合)。 */
    private boolean duplicateDealId;

    /** 取得途中でAPI障害・タイムアウトが発生した。 */
    private boolean fetchFailed;

    /** 定型エラーコード (fetchFailed 時のみ)。 */
    private String errorCode;
}
