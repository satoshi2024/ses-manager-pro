package com.ses.dto.report;

import com.ses.entity.ReportDelivery;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** recipient previewと配布状態。link token自体は通知本文のみに含め、DBにはhashだけを保存する。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportDeliveryResult {
    private ReportRecipientPreviewResult preview;
    private List<ReportDelivery> deliveries;
}
