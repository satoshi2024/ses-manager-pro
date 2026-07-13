package com.ses.dto;

import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.entity.Customer;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class InvoiceDetailDto extends Invoice {
    private Customer customer;
    private List<InvoiceItem> items;

    // 適格請求書(インボイス制度)の記載事項: 発行者情報と適用税率
    private String companyName;
    private String companyRegistrationNumber;
    private String companyAddress;
    /** 適用税率(パーセント表記の整数文字列。例: "10") */
    private String taxRatePercent;
}
