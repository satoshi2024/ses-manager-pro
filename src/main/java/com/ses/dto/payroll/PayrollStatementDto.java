package com.ses.dto.payroll;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;
@Data public class PayrollStatementDto {
    private String engineerId; private String employeeId; private Integer year; private Integer month; private String type;
    private BigDecimal grossAmount; private BigDecimal deductions; private BigDecimal netAmount; private Map<String, BigDecimal> items;
}
