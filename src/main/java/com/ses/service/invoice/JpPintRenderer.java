package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.CanonicalInvoice;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

@Component
@lombok.extern.slf4j.Slf4j
public class JpPintRenderer {

    /**
     * XXE無効・external entity禁止・DTD禁止を満たすセキュアなDocumentBuilderを生成する。
     */
    public DocumentBuilder createSecureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // XXE対策
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /**
     * CanonicalInvoiceからJP PINT XML(ダミー)を生成する。
     */
    public String render(CanonicalInvoice invoice, String specVersion) {
        try {
            DocumentBuilder builder = createSecureDocumentBuilder();
            Document doc = builder.newDocument();
            
            // XMLのルート要素作成
            org.w3c.dom.Element root = doc.createElement("Invoice");
            root.setAttribute("xmlns", "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2");
            root.setAttribute("xmlns:cac", "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2");
            root.setAttribute("xmlns:cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");
            doc.appendChild(root);
            
            org.w3c.dom.Element customizationId = doc.createElement("cbc:CustomizationID");
            customizationId.setTextContent("urn:peppol:pint:billing-3.0@jp:1.0::" + specVersion);
            root.appendChild(customizationId);

            org.w3c.dom.Element id = doc.createElement("cbc:ID");
            id.setTextContent(invoice.getInvoiceNumber());
            root.appendChild(id);

            org.w3c.dom.Element issueDate = doc.createElement("cbc:IssueDate");
            issueDate.setTextContent(invoice.getIssuedDate() != null ? invoice.getIssuedDate().toString() : "");
            root.appendChild(issueDate);

            org.w3c.dom.Element dueDate = doc.createElement("cbc:DueDate");
            dueDate.setTextContent(invoice.getDueDate() != null ? invoice.getDueDate().toString() : "");
            root.appendChild(dueDate);

            org.w3c.dom.Element currencyCode = doc.createElement("cbc:DocumentCurrencyCode");
            currencyCode.setTextContent(invoice.getCurrency() != null ? invoice.getCurrency() : "JPY");
            root.appendChild(currencyCode);

            org.w3c.dom.Element buyerReference = doc.createElement("cbc:BuyerReference");
            buyerReference.setTextContent(invoice.getCustomer() != null ? invoice.getCustomer().getPeppolParticipantId() : "REF");
            root.appendChild(buyerReference);

            if (invoice.getOrderReference() != null) {
                org.w3c.dom.Element orderRef = doc.createElement("cac:OrderReference");
                org.w3c.dom.Element orderRefId = doc.createElement("cbc:ID");
                orderRefId.setTextContent(invoice.getOrderReference());
                orderRef.appendChild(orderRefId);
                root.appendChild(orderRef);
            }
            if (invoice.getContractReference() != null) {
                org.w3c.dom.Element contractRef = doc.createElement("cac:ContractDocumentReference");
                org.w3c.dom.Element contractRefId = doc.createElement("cbc:ID");
                contractRefId.setTextContent(invoice.getContractReference());
                contractRef.appendChild(contractRefId);
                root.appendChild(contractRef);
            }
            org.w3c.dom.Element supplierParty = doc.createElement("cac:AccountingSupplierParty");
            org.w3c.dom.Element supplierPartyName = doc.createElement("cac:Party");
            org.w3c.dom.Element supplierName = doc.createElement("cac:PartyName");
            org.w3c.dom.Element sName = doc.createElement("cbc:Name");
            sName.setTextContent(invoice.getSupplier() != null ? invoice.getSupplier().getName() : "Seller");
            supplierName.appendChild(sName);
            supplierPartyName.appendChild(supplierName);
            
            org.w3c.dom.Element supplierTaxScheme = doc.createElement("cac:PartyTaxScheme");
            org.w3c.dom.Element sCompanyId = doc.createElement("cbc:CompanyID");
            sCompanyId.setTextContent(invoice.getSupplier() != null ? invoice.getSupplier().getCorporateNumber() : "T1234567890123");
            supplierTaxScheme.appendChild(sCompanyId);
            org.w3c.dom.Element sTaxScheme = doc.createElement("cac:TaxScheme");
            org.w3c.dom.Element sTaxSchemeId = doc.createElement("cbc:ID");
            sTaxSchemeId.setTextContent("VAT");
            sTaxScheme.appendChild(sTaxSchemeId);
            supplierTaxScheme.appendChild(sTaxScheme);
            supplierPartyName.appendChild(supplierTaxScheme);
            
            supplierParty.appendChild(supplierPartyName);
            root.appendChild(supplierParty);

            org.w3c.dom.Element customerParty = doc.createElement("cac:AccountingCustomerParty");
            org.w3c.dom.Element customerPartyName = doc.createElement("cac:Party");
            org.w3c.dom.Element customerName = doc.createElement("cac:PartyName");
            org.w3c.dom.Element cName = doc.createElement("cbc:Name");
            cName.setTextContent(invoice.getCustomer() != null ? invoice.getCustomer().getName() : "Buyer");
            customerName.appendChild(cName);
            customerPartyName.appendChild(customerName);
            customerParty.appendChild(customerPartyName);
            root.appendChild(customerParty);

            org.w3c.dom.Element taxTotal = doc.createElement("cac:TaxTotal");
            org.w3c.dom.Element taxAmount = doc.createElement("cbc:TaxAmount");
            taxAmount.setTextContent(invoice.getTaxAmount() != null ? invoice.getTaxAmount().toString() : "0");
            taxTotal.appendChild(taxAmount);
            org.w3c.dom.Element taxSubtotal = doc.createElement("cac:TaxSubtotal");
            org.w3c.dom.Element taxableAmount = doc.createElement("cbc:TaxableAmount");
            taxableAmount.setTextContent(invoice.getTaxExclusiveAmount() != null ? invoice.getTaxExclusiveAmount().toString() : "0");
            taxSubtotal.appendChild(taxableAmount);
            org.w3c.dom.Element subTaxAmount = doc.createElement("cbc:TaxAmount");
            subTaxAmount.setTextContent(invoice.getTaxAmount() != null ? invoice.getTaxAmount().toString() : "0");
            taxSubtotal.appendChild(subTaxAmount);
            
            org.w3c.dom.Element taxCategory = doc.createElement("cac:TaxCategory");
            org.w3c.dom.Element taxId = doc.createElement("cbc:ID");
            taxId.setTextContent("S"); // S = Standard rate
            taxCategory.appendChild(taxId);
            org.w3c.dom.Element taxPercent = doc.createElement("cbc:Percent");
            taxPercent.setTextContent("10"); // Defaults to 10% for basic JP PINT
            taxCategory.appendChild(taxPercent);
            
            org.w3c.dom.Element taxScheme = doc.createElement("cac:TaxScheme");
            org.w3c.dom.Element schemeId = doc.createElement("cbc:ID");
            schemeId.setTextContent("VAT");
            taxScheme.appendChild(schemeId);
            taxCategory.appendChild(taxScheme);
            
            taxSubtotal.appendChild(taxCategory);
            taxTotal.appendChild(taxSubtotal);
            root.appendChild(taxTotal);

            // LegalMonetaryTotal (合計金額)
            org.w3c.dom.Element legalMonetaryTotal = doc.createElement("cac:LegalMonetaryTotal");
            org.w3c.dom.Element lineExtensionAmount = doc.createElement("cbc:LineExtensionAmount");
            lineExtensionAmount.setTextContent(invoice.getTaxExclusiveAmount() != null ? invoice.getTaxExclusiveAmount().toString() : "0");
            legalMonetaryTotal.appendChild(lineExtensionAmount);
            org.w3c.dom.Element taxExclusiveAmount = doc.createElement("cbc:TaxExclusiveAmount");
            taxExclusiveAmount.setTextContent(invoice.getTaxExclusiveAmount() != null ? invoice.getTaxExclusiveAmount().toString() : "0");
            legalMonetaryTotal.appendChild(taxExclusiveAmount);
            org.w3c.dom.Element taxInclusiveAmount = doc.createElement("cbc:TaxInclusiveAmount");
            taxInclusiveAmount.setTextContent(invoice.getTaxInclusiveAmount() != null ? invoice.getTaxInclusiveAmount().toString() : "0");
            legalMonetaryTotal.appendChild(taxInclusiveAmount);
            root.appendChild(legalMonetaryTotal);

            // InvoiceLine (明細)
            if (invoice.getItems() != null) {
                int lineId = 1;
                for (CanonicalInvoice.CanonicalInvoiceItem item : invoice.getItems()) {
                    org.w3c.dom.Element invoiceLine = doc.createElement("cac:InvoiceLine");
                    org.w3c.dom.Element lineIdElem = doc.createElement("cbc:ID");
                    lineIdElem.setTextContent(String.valueOf(lineId++));
                    invoiceLine.appendChild(lineIdElem);
                    
                    org.w3c.dom.Element itemLineAmount = doc.createElement("cbc:LineExtensionAmount");
                    itemLineAmount.setTextContent(item.getLineAmount() != null ? item.getLineAmount().toString() : "0");
                    invoiceLine.appendChild(itemLineAmount);

                    org.w3c.dom.Element itemElem = doc.createElement("cac:Item");
                    org.w3c.dom.Element itemName = doc.createElement("cbc:Name");
                    itemName.setTextContent(item.getDescription() != null ? item.getDescription() : "");
                    itemElem.appendChild(itemName);
                    org.w3c.dom.Element classifiedTaxCategory = doc.createElement("cac:ClassifiedTaxCategory");
                    org.w3c.dom.Element lineTaxId = doc.createElement("cbc:ID");
                    lineTaxId.setTextContent(item.getTaxCategory() != null ? item.getTaxCategory() : "S");
                    classifiedTaxCategory.appendChild(lineTaxId);
                    org.w3c.dom.Element lineTaxPercent = doc.createElement("cbc:Percent");
                    lineTaxPercent.setTextContent(item.getTaxRate() != null ? item.getTaxRate().toString() : "10");
                    classifiedTaxCategory.appendChild(lineTaxPercent);
                    org.w3c.dom.Element lineTaxScheme = doc.createElement("cac:TaxScheme");
                    org.w3c.dom.Element lineSchemeId = doc.createElement("cbc:ID");
                    lineSchemeId.setTextContent("VAT");
                    lineTaxScheme.appendChild(lineSchemeId);
                    classifiedTaxCategory.appendChild(lineTaxScheme);
                    itemElem.appendChild(classifiedTaxCategory);
                    invoiceLine.appendChild(itemElem);

                    org.w3c.dom.Element price = doc.createElement("cac:Price");
                    org.w3c.dom.Element priceAmount = doc.createElement("cbc:PriceAmount");
                    priceAmount.setTextContent(item.getUnitPrice() != null ? item.getUnitPrice().toString() : "0");
                    price.appendChild(priceAmount);
                    invoiceLine.appendChild(price);
                    
                    root.appendChild(invoiceLine);
                }
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            // TransformerにもXXE対策推奨だが、出力側なので比較的安全
            tf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            tf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "");
            
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();

        } catch (Exception e) {
            log.error("XMLの生成に失敗しました。", e); throw new BusinessException("XMLの生成に失敗しました。");
        }
    }

    /**
     * テスト用に文字列からXMLをパースし、XXEが無効化されていることを確認する。
     */
    public Document parseSecurely(String xml) throws Exception {
        DocumentBuilder builder = createSecureDocumentBuilder();
        org.xml.sax.InputSource is = new org.xml.sax.InputSource(new StringReader(xml));
        return builder.parse(is);
    }
}




