package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 外部へ送信した後に取消された（freeeに取引が作成されたか確定できない）前の世代の送信内容のうち、
 * 既存取引の確認で走査範囲を決める項目。
 *
 * <p>再送信は新しい世代の取引先・発生日で送るため、その範囲だけを走査すると、取消の前に別の取引先
 * （マッピングの訂正前）や別の発生日で作成された取引を見落とす。前の世代ごとにこの範囲も走査する。
 * 両方nullは「範囲が分からない」で、収支区分だけで走査する（件数が多ければ作成せずに失敗する）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriorDealScope {
    /** 前の世代で送ったfreee取引先ID。取引先を送らない経費や、取引先コードで送った旧スナップショットはnull。 */
    private String partnerExternalId;
    /** 前の世代で送った発生日。スナップショットに無ければnull。 */
    private LocalDate issueDate;
}
