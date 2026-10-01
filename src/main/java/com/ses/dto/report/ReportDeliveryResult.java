package com.ses.dto.report;

import com.ses.entity.ReportDelivery;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** recipient previewと配布状態。通知linkは認証済みsessionのaction URLであり、raw tokenを含めない。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportDeliveryResult {
    private ReportRecipientPreviewResult preview;
    private List<ReportDelivery> deliveries;
}
