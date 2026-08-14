package com.ses.service.impl;

import com.ses.common.enums.CloudSignErrorCode;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.dto.cloudsign.PdfDownload;
import com.ses.entity.ContractDocument;
import com.ses.service.CloudSignClient;
import com.ses.service.cloudsign.CloudSignApiClient;
import com.ses.service.cloudsign.CloudSignApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 旧CloudSignClientの互換facade（HFP-02-03の移行用・@Deprecated）。
 * 実処理は固定OpenAPI 0.36.0に閉じた {@link CloudSignApiClient} へ委譲する。
 * 公式の直列4工程（create→upload→participant→send）を同期実行するだけで、
 * durable dispatch（queue/CAS/checkpoint）はHFP-02-04で置き換える。
 */
@Slf4j
@Component
@Deprecated
public class CloudSignClientImpl implements CloudSignClient {

    private final CloudSignApiClient api;
    private final CloudSignProperties properties;

    public CloudSignClientImpl(CloudSignApiClient api, CloudSignProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    @Override
    public Result send(ContractDocument d) {
        if (!properties.isEnabled()) {
            throw com.ses.common.exception.BusinessException.of("error.contract.document.cloudsignNotConfigured");
        }
        try {
            String title = "SES契約書 " + d.getContractId();
            CloudSignDocument doc = api.createDocument(new CreateDocumentRequest(title, "op:" + d.getId(), null));
            byte[] pdf = readSourcePdf(d);
            CloudSignDocument afterUpload = api.uploadFile(doc.id(), fileNameOf(d), pdf);
            api.addParticipant(doc.id(), new AddParticipantRequest(
                    d.getRecipientName(), d.getRecipientEmail(), null, "ja"));
            CloudSignDocument afterSend = api.sendDocument(doc.id());
            String fileId = firstFileId(afterUpload);
            return new Result(doc.id(), fileId, mapStatus(afterSend.status()), null, null);
        } catch (CloudSignApiException e) {
            throw com.ses.common.exception.BusinessException.of(
                    "error.contract.document.cloudsignFailed", e.getCode().name());
        } catch (IOException e) {
            throw com.ses.common.exception.BusinessException.of(
                    "error.contract.document.cloudsignFailed", CloudSignErrorCode.NETWORK.name());
        }
    }

    @Override
    public Result status(String id) {
        if (!properties.isEnabled()) {
            throw com.ses.common.exception.BusinessException.of("error.contract.document.cloudsignNotConfigured");
        }
        if (id == null || id.isBlank()) {
            throw com.ses.common.exception.BusinessException.of("error.contract.document.cloudsignInvalid");
        }
        CloudSignDocument doc = api.getDocument(id);
        String fileId = firstFileId(doc);
        byte[] signedPdf = null;
        if (doc.status() != null && doc.status() == 2 && fileId != null) {
            try {
                PdfDownload download = api.downloadFile(id, fileId);
                signedPdf = Files.readAllBytes(download.tempPath());
            } catch (CloudSignApiException | IOException ignored) {
                log.warn("[契約書] 署名済PDFの取得に失敗: documentId={}", masked(id));
            }
        }
        return new Result(id, fileId, mapStatus(doc.status()), signedPdf, null);
    }

    private byte[] readSourcePdf(ContractDocument d) throws IOException {
        Path p = Paths.get(d.getPdfPath());
        return Files.readAllBytes(p);
    }

    private String fileNameOf(ContractDocument d) {
        Path p = Paths.get(d.getPdfPath());
        return p.getFileName() == null ? "document.pdf" : p.getFileName().toString();
    }

    private static String firstFileId(CloudSignDocument doc) {
        if (doc.files() == null || doc.files().isEmpty()) {
            return null;
        }
        return doc.files().get(0).id();
    }

    /** 旧インターフェースの日本語statusへ変換する（HFP-02-04で業務状態mappingへ置換）。 */
    private static String mapStatus(Integer status) {
        if (status == null) {
            return "確認中";
        }
        return switch (status) {
            case 0 -> "下書き";
            case 1 -> "確認中";
            case 2 -> "完了";
            case 3 -> "却下";
            default -> "確認中";
        };
    }

    private static String masked(String id) {
        if (id == null) {
            return null;
        }
        return id.length() > 6 ? id.substring(0, 3) + "..." : "***";
    }
}
