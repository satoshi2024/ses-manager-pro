package com.ses.dto.acceptance;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 月次検収グリッドDTO（契約・要員・顧客・案件名をJOINして表示用に整形する）。 */
@Data
public class AcceptanceGridDto {
    private Long id;
    private Long contractId;
    private String contractNo;
    private Long engineerId;
    private String engineerName;
    private Long customerId;
    private String customerName;
    private Long projectId;
    private String projectName;
    private Long workRecordId;
    private String workMonth;
    private String status;
    private LocalDateTime submittedAt;
    private Long customerContactId;
    private String customerContactName;
    /** 検収実行時点の顧客確認者名snapshot（改名後も不変）。 */
    private String customerContactNameSnapshot;
    private LocalDateTime acceptedAt;
    private String rejectComment;
    private BigDecimal hoursSnapshot;
    private BigDecimal amountSnapshot;
    /** 検収書原本document ID（R3.1）。 */
    private Long documentId;
    private Integer version;
    /** 契約の検収要否（false=検収不要契約）。 */
    private Boolean acceptanceRequired;
}
