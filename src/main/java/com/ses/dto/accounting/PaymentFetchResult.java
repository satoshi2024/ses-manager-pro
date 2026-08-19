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
 * 50ページ上限到達・重複deal ID・重複payment ID・取得途中障害を結果と共に返し、
 * 月次照合側で fail-closed (readyForClosing = false) を判定する。
 * <ul>
 *   <li>{@code deals}: deal単位 (売上/仕入/経費の母集団照合・EXTERNAL_ONLY表示用)</li>
 *   <li>{@code payments}: freee {@code payments[]} を展開した payment単位フラットリスト (入金 1:1 消込用)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFetchResult {

    /** deal単位の取引一覧 (dealId で一意)。 */
    @Builder.Default
    private List<CanonicalPaymentSync> deals = new ArrayList<>();

    /** payment単位の決済一覧 ({@code dealId}:{@code paymentId} で一意)。 */
    @Builder.Default
    private List<CanonicalPaymentSync> payments = new ArrayList<>();

    /** freee取引一覧の50ページ (5,000件) 上限に到達した (次ページが存在し得る)。 */
    private boolean pageCapReached;

    /** ページ跨ぎで重複する deal ID を検出した (外部データ不整合)。 */
    private boolean duplicateDealId;

    /** 同一 deal 配下で重複する payment ID を検出した (外部データ不整合)。 */
    private boolean duplicatePaymentId;

    /** 取得途中でAPI障害・タイムアウトが発生した。 */
    private boolean fetchFailed;

    /** 定型エラーコード (fetchFailed 時のみ)。 */
    private String errorCode;
}
