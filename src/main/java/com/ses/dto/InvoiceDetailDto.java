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
}
