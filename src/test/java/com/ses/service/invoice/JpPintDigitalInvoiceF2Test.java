package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.CanonicalInvoice;
import com.ses.dto.invoice.CanonicalInvoice.CanonicalInvoiceItem;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JpPintDigitalInvoiceF2Test {

    private final JpPintValidator validator = new JpPintValidator();
    private final JpPintRenderer renderer = new JpPintRenderer();

    @Test
    void testValidator_SuccessWhenAmountsMatch() {
        CanonicalInvoice invoice = CanonicalInvoice.builder()
                .taxExclusiveAmount(new BigDecimal("1000"))
                .taxAmount(new BigDecimal("100"))
                .roundingAmount(BigDecimal.ZERO)
                .taxInclusiveAmount(new BigDecimal("1100"))
                .items(List.of(
                        CanonicalInvoiceItem.builder()
                                .lineAmount(new BigDecimal("1000"))
                                .build()
                ))
                .build();
        
        assertDoesNotThrow(() -> validator.validateAmount(invoice));
    }

    @Test
    void testValidator_ThrowsExceptionWhenLineTotalMismatchesSubtotal() {
        CanonicalInvoice invoice = CanonicalInvoice.builder()
                .taxExclusiveAmount(new BigDecimal("1000")) // 小計1000
                .taxAmount(new BigDecimal("100"))
                .roundingAmount(BigDecimal.ZERO)
                .taxInclusiveAmount(new BigDecimal("1100"))
                .items(List.of(
                        CanonicalInvoiceItem.builder()
                                .lineAmount(new BigDecimal("999")) // 明細合算が合わない
                                .build()
                ))
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateAmount(invoice));
        assertTrue(ex.getMessage().contains("インボイスの小計(taxExclusiveAmount)が明細の合算(lineTotal)と一致しません"));
    }

    @Test
    void testValidator_ThrowsExceptionWhenTotalMismatches() {
        CanonicalInvoice invoice = CanonicalInvoice.builder()
                .taxExclusiveAmount(new BigDecimal("1000"))
                .taxAmount(new BigDecimal("100"))
                .roundingAmount(BigDecimal.ZERO)
                .taxInclusiveAmount(new BigDecimal("1101")) // 1円合わない
                .items(List.of(
                        CanonicalInvoiceItem.builder()
                                .lineAmount(new BigDecimal("1000"))
                                .build()
                ))
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateAmount(invoice));
        assertTrue(ex.getMessage().contains("インボイスの合計金額が明細の合算・税額と一致しません"));
    }

    @Test
    void testRenderer_XXEPrevention() {
        // 悪意のあるXML
        String xxeXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>" +
                "<Invoice>&xxe;</Invoice>";

        // DOCTYPEがパース時点で拒否されるべき
        assertThrows(SAXParseException.class, () -> {
            renderer.parseSecurely(xxeXml);
        });
    }

    @Test
    void testRenderer_GeneratesXml() {
        CanonicalInvoice invoice = CanonicalInvoice.builder()
                .invoiceNumber("INV-2026-001")
                .issuedDate(LocalDate.of(2026, 8, 20))
                .dueDate(LocalDate.of(2026, 9, 20))
                .orderReference("PO-1234")
                .contractReference("CONT-999")
                .items(List.of(CanonicalInvoiceItem.builder().taxCategory("S").taxRate(new BigDecimal("10")).build()))
                .build();
        
        String xml = renderer.render(invoice, "1.1.3");
        
        assertTrue(xml.contains("<cbc:ID>INV-2026-001</cbc:ID>"));
        assertTrue(xml.contains("<cbc:CustomizationID>urn:peppol:pint:billing-3.0@jp:1.0::1.1.3</cbc:CustomizationID>"));
        assertTrue(xml.contains("<cbc:IssueDate>2026-08-20</cbc:IssueDate>"));
        assertTrue(xml.contains("<cbc:DueDate>2026-09-20</cbc:DueDate>"));
        assertTrue(xml.contains("<cac:AccountingSupplierParty>"));
        assertTrue(xml.contains("<cac:AccountingCustomerParty>"));
        assertTrue(xml.contains("<cac:TaxTotal>"));
        assertTrue(xml.contains("<cac:OrderReference>"));
        assertTrue(xml.contains("<cbc:ID>PO-1234</cbc:ID>"));
        assertTrue(xml.contains("<cac:ContractDocumentReference>"));
        assertTrue(xml.contains("<cbc:ID>CONT-999</cbc:ID>"));
        assertTrue(xml.contains("<cac:ClassifiedTaxCategory>"));
        assertTrue(xml.contains("<cbc:Percent>10</cbc:Percent>"));
    }
}

