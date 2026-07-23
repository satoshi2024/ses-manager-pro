package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.TemplateRenderer;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.CloudSignClient;
import com.ses.service.ContractDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ContractDocumentServiceImpl extends ServiceImpl<ContractDocumentMapper, ContractDocument> implements ContractDocumentService {
    
    private final ContractTemplateMapper templates;
    private final com.ses.mapper.ContractMapper contracts;
    private final com.ses.service.CloudSignClient cloudSign;
    private final com.ses.common.util.PdfFontUtils pdfFontUtils;
    
    @Value("${app.upload.base-path:./uploads}")
    private String uploadBase;
    
    @Override
    public ContractDocument create(Long contractId, Long templateId, String name, String email) {
        if (name == null || email == null || !email.contains("@")) {
            throw BusinessException.of("error.contract.document.recipientInvalid");
        }
        
        ContractTemplate t = templates.selectById(templateId);
        if (t == null || !Integer.valueOf(1).equals(t.getActiveFlag())) {
            throw BusinessException.of("error.contract.document.templateNotFound");
        }
        
        Contract c = contracts.selectById(contractId);
        if (c == null) {
            throw BusinessException.of("error.contract.notFound");
        }
        
        Map<String, String> p = new HashMap<>();
        p.put("contractNo", Objects.toString(c.getContractNo(), ""));
        p.put("contractType", Objects.toString(c.getContractType(), ""));
        p.put("startDate", Objects.toString(c.getStartDate(), ""));
        p.put("endDate", Objects.toString(c.getEndDate(), ""));
        
        String html = sanitize(TemplateRenderer.render(t.getHtmlContent(), p));
        
        ContractDocument d = new ContractDocument();
        d.setContractId(contractId);
        d.setTemplateId(templateId);
        d.setTemplateVersion(t.getVersion());
        d.setRenderedHtml(html);
        d.setStatus("下書き");
        d.setRecipientName(name);
        d.setRecipientEmail(email);
        
        try {
            Path dir = Paths.get(uploadBase, "contracts", String.valueOf(contractId));
            Files.createDirectories(dir);
            Path pdf = dir.resolve("document-" + System.currentTimeMillis() + ".pdf");
            
            Document doc = new Document();
            PdfWriter.getInstance(doc, Files.newOutputStream(pdf));
            doc.open();
            
            com.lowagie.text.pdf.BaseFont baseFont = pdfFontUtils.resolveCjkFont();
            Font font = new Font(baseFont, 10, Font.NORMAL);
            String plainText = html.replaceAll("(?i)<br\\s*/?>", "\n")
                                   .replaceAll("(?i)</p>", "\n\n")
                                   .replaceAll("<[^>]*>", "");
            doc.add(new Paragraph(plainText, font));
            doc.close();
            
            d.setPdfPath(pdf.toString());
            d.setPdfSha256(hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf))));
        } catch (Exception e) {
            throw BusinessException.of("error.contract.document.pdfFailed", e.getMessage());
        }
        
        save(d);
        return d;
    }

    /**
     * PDF生成前の防御としての簡易サニタイズ。
     * フロントエンド側（contract-document.js）でのプレビュー描画時は
     * 必ず平文としてエスケープ（SES.escapeHtml）することを前提とする。
     */
    private String sanitize(String html) {
        if (html == null) {
            return "";
        }
        String x = html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                       .replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "")
                       .replaceAll("(?i)\\bon[a-z]+\\s*=\\s*\"[^\"]*\"", "")
                       .replaceAll("(?i)\\bon[a-z]+\\s*=\\s*'[^']*'", "")
                       .replaceAll("(?i)href\\s*=\\s*\"javascript:[^\"]*\"", "")
                       .replaceAll("(?i)href\\s*=\\s*'javascript:[^']*'", "");
        if (x.matches("(?is).*<(img|link)[^>]*(https?:|file:).*")) {
            throw BusinessException.of("error.contract.document.externalResource");
        }
        return x;
    }

    private String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) {
            s.append(String.format("%02x", v));
        }
        return s.toString();
    }

    @Override
    public void send(Long id) {
        ContractDocument d = getById(id);
        if (d == null) {
            throw BusinessException.of("error.contract.document.notFound");
        }
        if (!"下書き".equals(d.getStatus())) {
            throw BusinessException.of("error.contract.document.invalidState");
        }
        
        CloudSignClient.Result r = cloudSign.send(d);
        d.setCloudsignDocumentId(r.documentId());
        d.setCloudsignFileId(r.fileId());
        d.setStatus(r.status());
        d.setSentAt(java.time.LocalDateTime.now());
        updateById(d);
    }

    @Override
    public void sync(Long id) {
        ContractDocument d = getById(id);
        if (d == null) {
            throw BusinessException.of("error.contract.document.notFound");
        }
        
        CloudSignClient.Result r = cloudSign.status(d.getCloudsignDocumentId());
        d.setStatus(r.status());
        d.setCloudsignFileId(r.fileId());
        d.setLastSyncedAt(java.time.LocalDateTime.now());
        
        try {
            if (r.signedPdf() != null && r.signedPdf().length > 0) {
                Path p = safePath(id, "signed-" + id + ".pdf");
                Files.createDirectories(p.getParent());
                Files.write(p, r.signedPdf(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                d.setSignedPdfPath(p.toString());
                d.setPdfSha256(hex(MessageDigest.getInstance("SHA-256").digest(r.signedPdf())));
            }
            if (r.certificate() != null && r.certificate().length > 0) {
                Path p = safePath(id, "certificate-" + id + ".dat");
                Files.createDirectories(p.getParent());
                Files.write(p, r.certificate(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                d.setCertificatePath(p.toString());
            }
        } catch (Exception e) {
            throw BusinessException.of("error.contract.document.fileSaveFailed", e.getMessage());
        }
        
        updateById(d);
    }

    @Override
    public byte[] download(Long id) {
        ContractDocument d = getById(id);
        if (d == null) {
            throw BusinessException.of("error.contract.document.notFound");
        }
        
        String p = d.getSignedPdfPath() != null ? d.getSignedPdfPath() : d.getPdfPath();
        if (p == null) {
            throw BusinessException.of("error.contract.document.fileNotFound");
        }
        
        try {
            Path root = Paths.get(uploadBase).toAbsolutePath().normalize();
            Path target = Paths.get(p).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw new SecurityException("outside upload directory");
            }
            return Files.readAllBytes(target);
        } catch (Exception e) {
            throw BusinessException.of("error.contract.document.fileNotFound");
        }
    }

    private Path safePath(Long id, String file) {
        Path root = Paths.get(uploadBase).toAbsolutePath().normalize();
        Path target = root.resolve("contracts").resolve(String.valueOf(id)).resolve(file).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("invalid path");
        }
        return target;
    }
}
