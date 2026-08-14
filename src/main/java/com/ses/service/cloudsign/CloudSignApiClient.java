package com.ses.service.cloudsign;

import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.dto.cloudsign.PdfDownload;

/**
 * 固定OpenAPI 0.36.0 に閉じたCloudSign typed client。
 *
 * <p>mutation（create/upload/participant/send）は内部で自動retryしない。
 * timeout/504/connection resetは {@link CloudSignApiException#isUncertain()} で結果不明を表現し、
 * 同じmutationを自動再実行してはならない（HFP-02-AC-04-03）。401は一操作につき一回だけtoken再取得する。
 */
public interface CloudSignApiClient {

    /** POST /documents（form）。返却documentのid/statusが必須。 */
    CloudSignDocument createDocument(CreateDocumentRequest request);

    /** POST /documents/{id}/files（multipart: name, uploadfile）。送信原本bytesと同一であること。 */
    CloudSignDocument uploadFile(String documentId, String fileName, byte[] pdfBytes);

    /** POST /documents/{id}/participants（form: name, email, organization, language_code）。 */
    CloudSignDocument addParticipant(String documentId, AddParticipantRequest request);

    /** GET /documents/{id}。status/ファイル/宛先の照合に使うread。 */
    CloudSignDocument getDocument(String documentId);

    /** POST /documents/{id}（送信またはリマインド。status=1の再実行はreminderになるため禁止）。 */
    CloudSignDocument sendDocument(String documentId);

    /** GET /documents/{id}/files/{fileId}。size上限付きでtemp quarantineへstreamする。 */
    PdfDownload downloadFile(String documentId, String fileId);

    /** GET /documents/{id}/certificate。締結済み以外は404契約。PDFとして返る。 */
    PdfDownload downloadCertificate(String documentId);
}
