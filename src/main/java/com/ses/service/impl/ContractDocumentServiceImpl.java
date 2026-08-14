package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.TemplateRenderer;
import com.ses.entity.*;
import com.ses.mapper.*;
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
    @org.springframework.transaction.annotation.Transactional
    public ContractDocument queueSend(Long id, com.ses.dto.cloudsign.ConfirmedSendRequest request) {
        ContractDocument d = getById(id);
        if (d == null) {
            throw BusinessException.of("error.contract.document.notFound");
        }
        // 確認済みpayloadが現在の書類/契約と一致することを検証（HFP-02-AC-03-04/05）
        Contract c = contracts.selectById(d.getContractId());
        if (c == null || !Objects.equals(c.getContractNo(), request.contractNo())) {
            throw BusinessException.of("error.contract.document.payloadChanged");
        }
        if (!Objects.equals(d.getTemplateVersion(), request.templateVersion())
                || !Objects.equals(d.getRecipientName(), request.recipientName())
                || !Objects.equals(d.getRecipientEmail(), request.recipientEmail())) {
            throw BusinessException.of("error.contract.document.payloadChanged");
        }
        // 送信直前に原本PDFの存在・正規化path・magic/終端・size・保存hash一致を検査（HFP-02-AC-03-01）
        verifySourcePdf(d);

        String payloadHash = com.ses.service.cloudsign.CloudSignPayloadHasher.hash(request);
        String currentState = d.getDispatchState();
        if (com.ses.common.enums.DispatchState.QUEUED.name().equals(currentState)
                && Objects.equals(d.getSendPayloadSha256(), payloadHash)
                && d.getOperationId() != null) {
            // 同一payloadの再queue（二重クリック）は既存operationを返す（冪等）
            return d;
        }
        if (currentState != null && !com.ses.common.enums.DispatchState.NONE.name().equals(currentState)) {
            throw BusinessException.of("error.contract.document.invalidState");
        }
        if (d.getSendPayloadSha256() != null && !d.getSendPayloadSha256().equals(payloadHash)) {
            // queue後に原本/宛先が変わった（HFP-02-AC-03-05）
            throw BusinessException.of("error.contract.document.payloadChanged");
        }
        String operationId = d.getOperationId() != null ? d.getOperationId()
                : java.util.UUID.randomUUID().toString();
        int updated = baseMapper.casQueue(id, safeVersion(d), operationId, payloadHash);
        if (updated == 0) {
            // 並列requestが先にqueueした可能性: 再読込して同じoperationなら成功扱い
            ContractDocument reloaded = getById(id);
            if (reloaded != null
                    && com.ses.common.enums.DispatchState.QUEUED.name().equals(reloaded.getDispatchState())
                    && Objects.equals(reloaded.getSendPayloadSha256(), payloadHash)) {
                return reloaded;
            }
            throw BusinessException.of("error.contract.document.invalidState");
        }
        ContractDocument queued = getById(id);
        log.info("契約書の送信をqueue受付: docId={} operationId={} payloadHashPrefix={}",
                id, operationId, payloadHash.substring(0, 8));
        return queued;
    }

    private static int safeVersion(ContractDocument doc) {
        return doc.getVersion() == null ? 0 : doc.getVersion();
    }

    /** 送信原本PDFの事前検証（存在・正規化path・PDF magic/EOF・size・保存hash一致）。 */
    private void verifySourcePdf(ContractDocument d) {
        if (d.getPdfPath() == null || d.getPdfSha256() == null) {
            throw BusinessException.of("error.contract.document.sourceMissing");
        }
        try {
            Path root = Paths.get(uploadBase).toAbsolutePath().normalize();
            Path target = Paths.get(d.getPdfPath()).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw BusinessException.of("error.contract.document.sourceInvalid");
            }
            if (!Files.isRegularFile(target)) {
                throw BusinessException.of("error.contract.document.sourceMissing");
            }
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length == 0 || !isPdfBytes(bytes)) {
                throw BusinessException.of("error.contract.document.sourceInvalid");
            }
            String current = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!current.equals(d.getPdfSha256())) {
                throw BusinessException.of("error.contract.document.sourceChanged");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of("error.contract.document.sourceInvalid");
        }
    }

    private static boolean isPdfBytes(byte[] data) {
        if (data.length < 8) {
            return false;
        }
        if (data[0] != 0x25 || data[1] != 0x50 || data[2] != 0x44 || data[3] != 0x46 || data[4] != 0x2D) {
            return false;
        }
        String tail = new String(data, Math.max(0, data.length - 1024), Math.min(data.length, 1024),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        int eof = tail.lastIndexOf("%%EOF");
        return eof >= 0 && tail.substring(eof + 5).trim().isEmpty();
    }

    @Override
    public byte[] download(Long id) {
        ContractDocument d = getById(id);
        if (d == null) {
            throw BusinessException.of("error.contract.document.notFound");
        }
        
        // 送信原本（ローカル生成物）のみ。signed/certificateはCloudSignArtifactServiceが別経路で返す。
        String p = d.getPdfPath();
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
                    .businessKey("CONTRACT:" + contract.getId())
                    .versionDiscriminator(doc.getId() != null ? "doc-" + doc.getId() : "v1")
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