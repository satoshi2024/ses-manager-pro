package com.ses.service.cloudsign;

import com.ses.common.enums.CloudSignErrorCode;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.dto.cloudsign.PdfDownload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 固定OpenAPI 0.36.0に閉じたCloudSign typed client実装。
 *
 * <p>全mutationはretry=0。401は一操作につき一回だけtoken再取得して再実行する
 * （providerがmutationを処理した後の401ではないため、再実行は安全なread/認証失敗のみ扱う:
 * 401はrequestが受理されないため再実行で重複を生まない）。timeout/504/5xxは結果不明。
 */
@Slf4j
@Component
public class CloudSignApiClientImpl implements CloudSignApiClient {

    private final CloudSignProperties properties;
    private final RestTemplate rest;
    private final CloudSignTokenProvider tokenProvider;
    private final CloudSignErrorClassifier classifier;

    public CloudSignApiClientImpl(CloudSignProperties properties,
                                  @Qualifier("cloudsignRestTemplate") RestTemplate rest,
                                  CloudSignTokenProvider tokenProvider,
                                  CloudSignErrorClassifier classifier) {
        this.properties = properties;
        this.rest = rest;
        this.tokenProvider = tokenProvider;
        this.classifier = classifier;
    }

    @Override
    public CloudSignDocument createDocument(CreateDocumentRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("title", request.title());
        form.add("note", request.note());
        if (request.message() != null) {
            form.add("message", request.message());
        }
        form.add("can_transfer", request.canTransfer() ? "true" : "false");
        form.add("private", request.isPrivate() ? "true" : "false");
        return executeWithTokenRetry("/documents", HttpMethod.POST, form,
                CloudSignDocument.class, true);
    }

    @Override
    public CloudSignDocument uploadFile(String documentId, String fileName, byte[] pdfBytes) {
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("name", fileName);
        multipart.add("uploadfile", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return executeWithTokenRetry("/documents/" + documentId + "/files", HttpMethod.POST,
                multipart, CloudSignDocument.class, true);
    }

    @Override
    public CloudSignDocument addParticipant(String documentId, AddParticipantRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", request.name());
        form.add("email", request.email());
        if (request.organization() != null && !request.organization().isBlank()) {
            form.add("organization", request.organization());
        }
        form.add("language_code", request.languageCode());
        return executeWithTokenRetry("/documents/" + documentId + "/participants", HttpMethod.POST,
                form, CloudSignDocument.class, true);
    }

    @Override
    public CloudSignDocument getDocument(String documentId) {
        return executeWithTokenRetry("/documents/" + documentId, HttpMethod.GET, null,
                CloudSignDocument.class, false);
    }

    @Override
    public CloudSignDocument sendDocument(String documentId) {
        return executeWithTokenRetry("/documents/" + documentId, HttpMethod.POST, null,
                CloudSignDocument.class, true);
    }

    @Override
    public PdfDownload downloadFile(String documentId, String fileId) {
        return download("/documents/" + documentId + "/files/" + fileId, "signed.pdf");
    }

    @Override
    public PdfDownload downloadCertificate(String documentId) {
        return download("/documents/" + documentId + "/certificate", "certificate.pdf");
    }

    // ------------------------------------------------------------------
    // internal
    // ------------------------------------------------------------------

    private <T> T executeWithTokenRetry(String path, HttpMethod method,
                                        MultiValueMap<?, ?> body, Class<T> responseType,
                                        boolean mutation) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HttpEntity<?> entity = entityWithBearer(body);
                ResponseEntity<T> response = rest.exchange(
                        properties.effectiveBaseUri() + path, method, entity, responseType);
                T value = requireBody(response.getBody(), responseType.getSimpleName());
                if (value instanceof CloudSignDocument doc) {
                    requireDocumentFields(doc, mutation);
                }
                return value;
            } catch (RestClientResponseException e) {
                CloudSignApiException classified = classifier.classifyResponse(
                        e.getRawStatusCode(), e.getResponseBodyAsByteArray(), mutation);
                if (classified.getCode() == CloudSignErrorCode.UNAUTHORIZED && attempt == 1) {
                    // 401はrequestが受理されていないため、token再取得後の再実行で外部重複を生まない。
                    tokenProvider.invalidateAndGetOnce();
                    continue;
                }
                throw classified;
            } catch (RuntimeException e) {
                throw classifier.classify(e, mutation);
            }
        }
    }

    /** 公式documentModelの必須field（id/status）欠落はschema error（HFP-02-AC-01-03）。 */
    private void requireDocumentFields(CloudSignDocument doc, boolean mutation) {
        if (doc.id() == null || doc.id().isBlank()) {
            throw new CloudSignApiException(CloudSignErrorCode.MALFORMED_RESPONSE,
                    "document responseにidがありません");
        }
        if (mutation && doc.status() == null) {
            throw new CloudSignApiException(CloudSignErrorCode.MALFORMED_RESPONSE,
                    "document responseにstatusがありません");
        }
    }

    private HttpEntity<?> entityWithBearer(MultiValueMap<?, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenProvider.getToken());
        // Content-TypeはFormHttpMessageConverterがbody内容から決定する
        // （Stringのみ→application/x-www-form-urlencoded / binary含む→multipart/form-data）。
        return new HttpEntity<>(body, headers);
    }

    private <T> T requireBody(T body, String what) {
        if (body == null) {
            throw new CloudSignApiException(CloudSignErrorCode.MALFORMED_RESPONSE, "response bodyが空です: " + what);
        }
        return body;
    }

    /** GET系のPDF download。size上限付きでtemp quarantineへstreamし、byte[]をメモリに保持しない。 */
    private PdfDownload download(String path, String defaultName) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                PdfDownload result = rest.execute(properties.effectiveBaseUri() + path, HttpMethod.GET,
                        request -> request.getHeaders().setBearerAuth(tokenProvider.getToken()),
                        (ResponseExtractor<PdfDownload>) response -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                byte[] body = readLimited(response.getBody(), 64 * 1024);
                                throw classifier.classifyResponse(
                                        response.getRawStatusCode(), body, false);
                            }
                            String contentType = response.getHeaders().getContentType() == null
                                    ? "" : response.getHeaders().getContentType().toString();
                            TempResult temp = streamToTempFile(response.getBody());
                            return PdfDownload.of(temp.path(), temp.size(), contentType);
                        });
                return result;
            } catch (RestClientResponseException e) {
                CloudSignApiException classified = classifier.classifyResponse(
                        e.getRawStatusCode(), e.getResponseBodyAsByteArray(), false);
                if (classified.getCode() == CloudSignErrorCode.UNAUTHORIZED && attempt == 1) {
                    tokenProvider.invalidateAndGetOnce();
                    continue;
                }
                throw classified;
            } catch (RuntimeException e) {
                throw classifier.classify(e, false);
            }
        }
    }

    private record TempResult(Path path, long size) {
    }

    private TempResult streamToTempFile(InputStream input) {
        if (input == null) {
            throw new CloudSignApiException(CloudSignErrorCode.MALFORMED_RESPONSE, "download bodyが空です");
        }
        Path temp = tempFile();
        long total = 0;
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > properties.getMaxPdfBytes()) {
                    throw new CloudSignApiException(CloudSignErrorCode.TOO_LARGE,
                            "download sizeが上限を超えました");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new CloudSignApiException(CloudSignErrorCode.NETWORK, "download streamに失敗");
        }
        if (total == 0) {
            throw new CloudSignApiException(CloudSignErrorCode.MALFORMED_RESPONSE, "download bodyが空です");
        }
        return new TempResult(temp, total);
    }

    private Path tempFile() {
        try {
            return Files.createTempFile("cloudsign-", ".pdf");
        } catch (IOException e) {
            throw new CloudSignApiException(CloudSignErrorCode.NETWORK, "temp file作成に失敗");
        }
    }

    private byte[] readLimited(InputStream input, int limit) {
        if (input == null) {
            return new byte[0];
        }
        try {
            return input.readNBytes(limit);
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
