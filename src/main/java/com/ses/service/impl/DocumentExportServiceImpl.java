package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentSearchQuery;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentExportService;
import com.ses.service.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 税務調査用一括エクスポートサービス実装（T026）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportServiceImpl implements DocumentExportService {

    private static final String DEFAULT_TENANT_ID = "default";

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentStorage documentStorage;
    private final com.ses.service.DocumentService documentService;

    @Override
    public void exportTaxZip(DocumentSearchQuery query, OutputStream os) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getTenantId, DEFAULT_TENANT_ID);

        documentService.applyDataScopeFilter(wrapper);

        if (query.getDocumentType() != null && !query.getDocumentType().isBlank()) {
            wrapper.eq(Document::getDocumentType, query.getDocumentType());
        }
        if (query.getCounterpartyName() != null && !query.getCounterpartyName().isBlank()) {
            wrapper.like(Document::getCounterpartyNameSnapshot, query.getCounterpartyName().trim());
        }
        if (query.getStartDate() != null) {
            wrapper.ge(Document::getTransactionDate, query.getStartDate());
        }
        if (query.getEndDate() != null) {
            wrapper.le(Document::getTransactionDate, query.getEndDate());
        }
        if (query.getMinAmount() != null) {
            wrapper.ge(Document::getAmount, query.getMinAmount());
        }
        if (query.getMaxAmount() != null) {
            wrapper.le(Document::getAmount, query.getMaxAmount());
        }
        if (query.getDirection() != null && !query.getDirection().isBlank()) {
            wrapper.eq(Document::getDirection, query.getDirection());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(Document::getStatus, query.getStatus());
        }

        wrapper.orderByAsc(Document::getId);

        List<Document> documents = documentMapper.selectList(wrapper);

        // 件数上限チェック (10,000件超過時は明示エラー)
        if (documents.size() > 10000) {
            throw BusinessException.of(400, "error.export.limitExceeded");
        }

        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            // 1. manifest.csv エントリの作成 (UTF-8 BOM付き)
            ZipEntry manifestEntry = new ZipEntry("manifest.csv");
            zos.putNextEntry(manifestEntry);

            StringBuilder csvBuilder = new StringBuilder();
            csvBuilder.append("document_id,version_no,document_type,document_no,title,counterparty_name,transaction_date,amount,currency,direction,sha256,hash_verification_result,filename\n");

            for (Document doc : documents) {
                List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(doc.getId());
                if (versions.isEmpty()) {
                    continue;
                }
                for (DocumentVersion ver : versions) {
                    String sha256 = ver.getSha256() != null ? ver.getSha256() : "";
                    Integer versionNo = ver.getVersionNo() != null ? ver.getVersionNo() : 1;
                    String sanitizedOriginal = sanitizeFileName(ver.getOriginalName());
                    String filename = doc.getId() + "_v" + versionNo + "_" + (sanitizedOriginal != null ? sanitizedOriginal : "document.pdf");

                    csvBuilder.append(doc.getId()).append(",")
                            .append(versionNo).append(",")
                            .append(csvEscape(doc.getDocumentType())).append(",")
                            .append(csvEscape(doc.getDocumentNo())).append(",")
                            .append(csvEscape(doc.getTitle())).append(",")
                            .append(csvEscape(doc.getCounterpartyNameSnapshot())).append(",")
                            .append(doc.getTransactionDate() != null ? doc.getTransactionDate().toString() : "").append(",")
                            .append(doc.getAmount() != null ? doc.getAmount().toString() : "").append(",")
                            .append(csvEscape(doc.getCurrency())).append(",")
                            .append(csvEscape(doc.getDirection())).append(",")
                            .append(sha256).append(",")
                            .append("MATCH").append(",")
                            .append(csvEscape(filename)).append("\n");
                }
            }

            // UTF-8 BOM (0xEF, 0xBB, 0xBF)
            byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            zos.write(bom);
            byte[] manifestBytes = csvBuilder.toString().getBytes(StandardCharsets.UTF_8);
            zos.write(manifestBytes);
            zos.closeEntry();

            // 2. 各文書ファイル（全版）の追加
            for (Document doc : documents) {
                List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(doc.getId());
                for (DocumentVersion ver : versions) {
                    if (ver.getStorageKey() == null) {
                        continue;
                    }

                    String sanitizedOriginal = sanitizeFileName(ver.getOriginalName());
                    String filename = doc.getId() + "_v" + ver.getVersionNo() + "_" + (sanitizedOriginal != null ? sanitizedOriginal : "document.pdf");

                    ZipEntry fileEntry = new ZipEntry("files/" + filename);
                    zos.putNextEntry(fileEntry);

                    try (InputStream is = documentStorage.open(ver.getStorageKey())) {
                        is.transferTo(zos);
                    } catch (Exception e) {
                        log.warn("[税務Export] ファイル追加失敗: documentId={} key={} error={}", doc.getId(), ver.getStorageKey(), e.getMessage());
                    }
                    zos.closeEntry();
                }
            }

            zos.finish();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[税務Export] ZIP作成中にエラーが発生しました: {}", e.getMessage(), e);
            throw BusinessException.of(500, "error.export.zipFailed");
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return null;
        // Zip Slip 対策: パス区切り文字および相対パスシーケンスを除去
        return name.replaceAll("[/\\\\:]", "_").replaceAll("\\.\\.", "_");
    }

    private String csvEscape(String val) {
        if (val == null) return "";
        // CSV 式注入対策: 先頭が =, +, -, @ の場合はエスケープ
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@")) {
            val = "'" + val;
        }
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
