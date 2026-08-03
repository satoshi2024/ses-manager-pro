package com.ses.dto.crm;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;

/** リード登録・更新リクエスト。 */
@Data
public class LeadSaveRequest {
    @NotBlank
    @Size(max = 200)
    private String companyName;
    @Size(max = 100)
    private String contactName;
    @Email
    @Size(max = 255)
    private String contactEmail;
    @Size(max = 50)
    private String contactPhone;
    @Size(max = 100)
    private String source;
    @DecimalMin("0")
    private BigDecimal sourceCost;
    private Long ownerUserId;
    private String status;
    private Integer version;
}
