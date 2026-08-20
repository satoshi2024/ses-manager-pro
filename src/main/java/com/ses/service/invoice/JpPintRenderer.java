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

            // TODO: 詳細なマッピング実装（今回はSpikeとして最小限）

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
            throw new BusinessException("XMLの生成に失敗しました。", e);
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
