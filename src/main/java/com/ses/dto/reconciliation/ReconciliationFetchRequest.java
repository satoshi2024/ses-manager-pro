package com.ses.dto.reconciliation;

import lombok.Data;

import java.time.LocalDate;

/** fetch対象期間（省略時はサービス側で既定値=直近30日を適用）。 */
@Data
public class ReconciliationFetchRequest {
    private LocalDate from;
    private LocalDate to;
}
