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

    @Override
    public void exportTaxZip(DocumentSearchQuery query, OutputStream os) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getTenantId, DEFAULT_TENANT_ID);

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

        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            // 1. manifest.csv エントリの作成
            ZipEntry manifestEntry = new ZipEntry("manifest.csv");
            zos.putNextEntry(manifestEntry);

            StringBuilder csvBuilder = new StringBuilder();
            csvBuilder.append("document_id,document_type,document_no,title,counterparty_name,transaction_date,amount,currency,direction,sha256,filename\n");

            for (Document doc : documents) {
                DocumentVersion latest = documentVersionMapper.findLatestByDocumentId(doc.getId());
                String sha256 = latest != null ? latest.getSha256() : "";
                String filename = latest != null && latest.getOriginalName() != null 
                        ? doc.getId() + "_" + latest.getOriginalName() 
                        : doc.getId() + "_document.pdf";

                csvBuilder.append(doc.getId()).append(",")
                        .append(csvEscape(doc.getDocumentType())).append(",")
                        .append(csvEscape(doc.getDocumentNo())).append(",")
                        .append(csvEscape(doc.getTitle())).append(",")
                        .append(csvEscape(doc.getCounterpartyNameSnapshot())).append(",")
                        .append(doc.getTransactionDate() != null ? doc.getTransactionDate().toString() : "").append(",")
                        .append(doc.getAmount() != null ? doc.getAmount().toString() : "").append(",")
                        .append(csvEscape(doc.getCurrency())).append(",")
                        .append(csvEscape(doc.getDirection())).append(",")
                        .append(sha256).append(",")
                        .append(csvEscape(filename)).append("\n");
            }

            byte[] manifestBytes = csvBuilder.toString().getBytes(StandardCharsets.UTF_8);
            zos.write(manifestBytes);
            zos.closeEntry();

            // 2. 各文書ファイルの追加
            for (Document doc : documents) {
                DocumentVersion latest = documentVersionMapper.findLatestByDocumentId(doc.getId());
                if (latest == null || latest.getStorageKey() == null) {
                    continue;
                }

                String filename = latest.getOriginalName() != null 
                        ? doc.getId() + "_" + latest.getOriginalName() 
                        : doc.getId() + "_document.pdf";

                ZipEntry fileEntry = new ZipEntry("files/" + filename);
                zos.putNextEntry(fileEntry);

                try (InputStream is = documentStorage.open(latest.getStorageKey())) {
                    is.transferTo(zos);
                } catch (Exception e) {
                    log.warn("[税務Export] ファイル追加失敗: documentId={} key={} error={}", doc.getId(), latest.getStorageKey(), e.getMessage());
                }
                zos.closeEntry();
            }

            zos.finish();
        } catch (Exception e) {
            log.error("[税務Export] ZIP作成中にエラーが発生しました: {}", e.getMessage(), e);
            throw BusinessException.of(500, "error.export.zipFailed");
        }
    }

    private String csvEscape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
