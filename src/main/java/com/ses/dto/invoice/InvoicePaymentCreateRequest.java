package com.ses.dto.invoice;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvoicePaymentCreateRequest {
    @NotNull(message = "{error.invoice.paymentDateRequired}")
    private LocalDate paidDate;

    @NotNull(message = "{error.invoice.paymentAmountInvalid}")
    @Digits(integer = 10, fraction = 0, message = "{error.invoice.paymentAmountInvalid}")
    private BigDecimal amount;

    @Digits(integer = 10, fraction = 0, message = "{error.invoice.paymentAmountInvalid}")
    private BigDecimal fee;

    @Size(max = 300, message = "{validation.maxLength}")
    private String remarks;
}