package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 支払照合・同期DTO。
 * <p>
 * 2つの粒度を同一DTOで表現する (design §6.2 / R1-P1-09):
 * <ul>
 *   <li><b>deal単位</b>: 売上/仕入/経費の母集団照合と EXTERNAL_ONLY 表示に使用。
 *       {@code dealId} / {@code amount}(deal総額) / {@code refNumber} / {@code issueDate} を保持。</li>
 *   <li><b>payment単位</b>: 入金の 1:1 消込照合に使用。freee の {@code payments[]} を展開した
 *       {@code paymentId} / {@code amount}(実決済金額) / {@code paymentDate}(実決済日) を保持。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalPaymentSync {
    private String externalId;
    private String dealId;
    /** payments[].id。入金照合の一意キー要素 ({@code dealId}:{@code paymentId})。 */
    private String paymentId;
    private String partnerCode;
    private String partnerName;
    /** payment単位の実決済日 (payments[].date)。欠落時は NULL (fail-closed)。 */
    private LocalDate paymentDate;
    /** deal単位の発生日 (deal.issue_date)。 */
    private LocalDate issueDate;
    /** deal単位: deal総額 / payment単位: 実決済金額。 */
    private BigDecimal amount;
    private String status;
    private boolean settled;
    private String referenceNo;
    private String refNumber;
}
