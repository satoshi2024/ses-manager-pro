package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * BP portalの空き要員登録/更新リクエスト（R3.1）。
 */
@Data
public class PortalBpAvailabilityRequest {

    @NotBlank(message = "error.portal.bp.availabilityNameRequired")
    @Size(max = 200, message = "error.portal.bp.availabilityNameTooLong")
    private String initialName;

    private String skillsJson;

    @NotNull(message = "error.portal.bp.unitPriceRequired")
    private BigDecimal unitPrice;

    private LocalDate availableFrom;

    private Integer experienceYears;

    @Size(max = 1000, message = "error.portal.bp.remarksTooLong")
    private String remarks;
}
