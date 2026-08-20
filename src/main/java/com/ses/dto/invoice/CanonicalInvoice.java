package com.ses.dto.invoice;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class CanonicalInvoice {
    
    private String invoiceNumber;
    private LocalDate issuedDate;
    private LocalDate dueDate;
    
    private String currency; // "JPY"
    
    private SupplierInfo supplier;
    private CustomerInfo customer;
    
    private BigDecimal taxExclusiveAmount; // subtotal
    private BigDecimal taxAmount;          // tax
    private BigDecimal taxInclusiveAmount; // total
    private BigDecimal roundingAmount;     // 端数調整(基本は0)
    
    private List<CanonicalInvoiceItem> items;
    
    @Data
    @Builder
    public static class SupplierInfo {
        private String corporateNumber; // 登録番号(Peppol Participant ID等)
        private String name;
        private String address;
    }
    
    @Data
    @Builder
    public static class CustomerInfo {
        private String peppolParticipantId; // 宛先
        private String name;
        private String address;
    }
    
    @Data
    @Builder
    public static class CanonicalInvoiceItem {
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineAmount;
        private String taxCategory;
        private BigDecimal taxRate;
    }
}
