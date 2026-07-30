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

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class ContractDocumentServiceImpl extends ServiceImpl<ContractDocumentMapper, ContractDocument> implements ContractDocumentService {
    
    private final ContractTemplateMapper templates;
    private final com.ses.mapper.ContractMapper contracts;
    private final com.ses.service.CloudSignClient cloudSign;
    private final com.ses.common.util.PdfFontUtils pdfFontUtils;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.mapper.FileSecurityMetadataMapper> metadataMapperProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.security.FileScanner> fileScannerProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.DocumentService> documentServiceProvider;
    
    @Value("${app.upload.base-path:./uploads}")
    private String uploadBase;
    
    @Override
    @org.springframework.transaction.annotation.Transactional
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
            
            com.lowagie.text.Document doc = new com.lowagie.text.Document();
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
        if (d.getPdfPath() != null) {
            recordSelfGeneratedMetadata(Paths.get(d.getPdfPath()), d.getId());
            registerToDocumentLedger(d, c);
        }
        return d;
    }

    /**
     * PDF生成前の防御としてのサニタイズ。
     * 外部リソース参照（http/https/file）を遮断し、XSSスクリプト・iframe等を完全除去する。
     */
    private String sanitize(String html) {
        if (html == null) {
            return "";
        }
        if (html.matches("(?is).*<(img|link)[^>]*(https?:|file:).*")) {
            throw BusinessException.of("error.contract.document.externalResource");
        }
        return html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "")
                   .replaceAll("(?i)<iframe[^>]*>[\\s\\S]*?</iframe>", "")
                   .replaceAll("(?i)on\\w+\\s*=\\s*\"[^\"]*\"", "")
                   .replaceAll("(?i)on\\w+\\s*=\\s*'[^']*'", "")
                   .replaceAll("(?i)javascript:", "");
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
    @org.springframework.transaction.annotation.Transactional
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
                scanAndRecordExternalFile(p, r.signedPdf(), d.getId());
                d.setSignedPdfPath(p.toString());
                d.setPdfSha256(hex(MessageDigest.getInstance("SHA-256").digest(r.signedPdf())));
            }
            if (r.certificate() != null && r.certificate().length > 0) {
                Path p = safePath(id, "certificate-" + id + ".dat");
                Files.createDirectories(p.getParent());
                Files.write(p, r.certificate(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                scanAndRecordExternalFile(p, r.certificate(), d.getId());
                d.setCertificatePath(p.toString());
            }
        } catch (BusinessException e) {
            throw e;
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
            com.ses.mapper.FileSecurityMetadataMapper mapper = metadataMapperProvider.getIfAvailable();
            if (mapper != null) {
                String relativeName = relativeStoredName(target);
                FileSecurityMetadata metadata = mapper.selectByStoredName("default", relativeName);
                if (metadata == null || !"PUBLISHED".equals(metadata.getStorageState())
                        || !"CLEAN".equals(metadata.getScanStatus())) {
                    throw BusinessException.of("error.contract.document.fileNotFound");
                }
            }
            return Files.readAllBytes(target);
        } catch (BusinessException e) {
            throw e;
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

    private String relativeStoredName(Path filePath) {
        Path root = Paths.get(uploadBase).toAbsolutePath().normalize();
        Path fileAbs = filePath.toAbsolutePath().normalize();
        if (fileAbs.startsWith(root)) {
            return root.relativize(fileAbs).toString().replace('\\', '/');
        }
        return filePath.getFileName().toString();
    }

    private void recordSelfGeneratedMetadata(Path pdfPath, Long documentId) {
        com.ses.mapper.FileSecurityMetadataMapper mapper = metadataMapperProvider.getIfAvailable();
        if (mapper == null) {
            return;
        }
        String storedName = relativeStoredName(pdfPath);
        FileSecurityMetadata metadata = mapper.selectByStoredName("default", storedName);
        if (metadata == null) {
            metadata = new FileSecurityMetadata();
            metadata.setTenantId("default");
            metadata.setStoredName(storedName);
            metadata.setFileKind("CONTRACT_DOCUMENT");
            metadata.setStorageState("PUBLISHED");
            metadata.setScanStatus("CLEAN");
            metadata.setOwnerType("CONTRACT_DOCUMENT");
            metadata.setOwnerId(documentId);
            metadata.setCreatedBy(com.ses.common.util.SecurityUtils.currentUserId());
            metadata.setCreatedAt(java.time.LocalDateTime.now());
            metadata.setUpdatedAt(java.time.LocalDateTime.now());
            if (mapper.insert(metadata) != 1) {
                throw BusinessException.of("error.file.saveFailed");
            }
        }
    }

    private void scanAndRecordExternalFile(Path filePath, byte[] data, Long documentId) {
        com.ses.mapper.FileSecurityMetadataMapper mapper = metadataMapperProvider.getIfAvailable();
        com.ses.service.security.FileScanner scanner = fileScannerProvider.getIfAvailable();
        String storedName = relativeStoredName(filePath);
        com.ses.service.security.FileScanResult result = null;
        if (scanner != null) {
            try {
                result = scanner.scan(filePath, com.ses.common.enums.FileKind.SKILL_SHEET);
            } catch (RuntimeException e) {
                result = com.ses.service.security.FileScanResult.unavailable("scanner failed");
            }
        } else {
            result = com.ses.service.security.FileScanResult.unavailable("scanner is not configured");
        }

        if (result != null && result.status() != com.ses.service.security.FileScanResult.Status.CLEAN) {
            if (mapper != null) {
                FileSecurityMetadata metadata = new FileSecurityMetadata();
                metadata.setTenantId("default");
                metadata.setStoredName(storedName);
                metadata.setFileKind("CONTRACT_DOCUMENT");
                metadata.setStorageState("QUARANTINED");
                metadata.setScanStatus(result.status().name());
                metadata.setRejectionReason(result.reason());
                metadata.setOwnerType("CONTRACT_DOCUMENT");
                metadata.setOwnerId(documentId);
                metadata.setCreatedBy(com.ses.common.util.SecurityUtils.currentUserId());
                metadata.setCreatedAt(java.time.LocalDateTime.now());
                metadata.setUpdatedAt(java.time.LocalDateTime.now());
                if (mapper.insert(metadata) != 1) {
                    throw BusinessException.of("error.file.saveFailed");
                }
            }
            throw BusinessException.of("error.file.scanRejected");
        }

        if (mapper != null) {
            FileSecurityMetadata metadata = mapper.selectByStoredName("default", storedName);
            if (metadata == null) {
                metadata = new FileSecurityMetadata();
                metadata.setTenantId("default");
                metadata.setStoredName(storedName);
                metadata.setFileKind("CONTRACT_DOCUMENT");
                metadata.setStorageState("PUBLISHED");
                metadata.setScanStatus("CLEAN");
                metadata.setOwnerType("CONTRACT_DOCUMENT");
                metadata.setOwnerId(documentId);
                metadata.setCreatedBy(com.ses.common.util.SecurityUtils.currentUserId());
                metadata.setCreatedAt(java.time.LocalDateTime.now());
                metadata.setUpdatedAt(java.time.LocalDateTime.now());
                if (mapper.insert(metadata) != 1) {
                    throw BusinessException.of("error.file.saveFailed");
                }
            } else {
                metadata.setStorageState("PUBLISHED");
                metadata.setScanStatus("CLEAN");
                metadata.setUpdatedAt(java.time.LocalDateTime.now());
                if (mapper.updateById(metadata) != 1) {
                    throw BusinessException.of("error.file.saveFailed");
                }
            }
        }
    }

    private void registerToDocumentLedger(ContractDocument doc, Contract contract) {
        com.ses.service.DocumentService docService = documentServiceProvider.getIfAvailable();
        if (docService == null || doc.getPdfPath() == null) {
            return;
        }
        try {
            Path path = Paths.get(doc.getPdfPath());
            if (!Files.exists(path)) {
                return;
            }
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                    .documentType("CONTRACT")
                    .title("契約書 PDF: " + Objects.toString(contract.getContractNo(), "ID:" + contract.getId()))
                    .documentNo(contract.getContractNo())
                    .counterpartyType("CUSTOMER")
                    .counterpartyId(contract.getCustomerId())
                    .transactionDate(contract.getStartDate())
                    .amount(contract.getSellingPrice())
                    .direction("OUTGOING")
                    .originalName(path.getFileName().toString())
                    .contentType("application/pdf")
                    .sourceType("GENERATED")
                    .businessKey("CONTRACT:" + contract.getId() + ":DOC:" + doc.getId())
                    .versionDiscriminator("v1")
                    .targetType("CONTRACT")
                    .targetId(contract.getId())
                    .build();

            try (java.io.InputStream is = Files.newInputStream(path)) {
                docService.registerGenerated(req, is);
            }
        } catch (Exception e) {
            log.warn("[帳票連携] 法定文書台帳への自動登録に失敗しました: contractId={} error={}", contract.getId(), e.getMessage());
        }
    }
}
