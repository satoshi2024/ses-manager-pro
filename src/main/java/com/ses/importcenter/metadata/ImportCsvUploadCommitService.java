package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.impl.DocumentMetadataCommitService;
import com.ses.service.impl.StagedImportDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CLEAN済みstorageとDocument/version/job/linkを一つの短いDB transactionで確定する。 */
@Service
public class ImportCsvUploadCommitService {
    private final ImportMetadataAuthorizationService authorization;
    private final DocumentMetadataCommitService documentCommit;
    private final ImportMetadataService metadata;

    public ImportCsvUploadCommitService(ImportMetadataAuthorizationService authorization,
                                        DocumentMetadataCommitService documentCommit,
                                        ImportMetadataService metadata) {
        this.authorization = authorization;
        this.documentCommit = documentCommit;
        this.metadata = metadata;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public ImportUploadJobDetail commit(ImportAdmissionToken token, ImportAdmissionManifest contentManifest,
                                        StagedImportDocument staged) {
        ImportAuthorizedContext expected = new ImportAuthorizedContext(token.tenantId(),
                token.actorUserId(), token.legalEntityId());
        ImportAuthorizedContext current = authorization.requireCurrentAuthorizationForWrite(expected);
        requireTokenMatch(token, contentManifest, current, staged);

        documentCommit.commitImportDocument(staged);
        var document = staged.document();
        var version = staged.version();
        ImportAdmissionManifest committedManifest = new ImportAdmissionManifest(contentManifest.fixtureKey(),
                token.tenantId(), token.legalEntityId(), token.sourceSystemId(), token.sourceVersion(),
                ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, token.encoding(), contentManifest.sourceSha256(),
                document.getId(), version.getId(), version.getVersionNo(), version.getSha256(), "A1A_UPLOADED");
        ImportJobRegistrationRequest request = new ImportJobRegistrationRequest(token.sourceSystemId(),
                ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, token.encoding(), contentManifest.sourceSha256(),
                document.getId(), version.getId(), version.getVersionNo());
        long jobId = metadata.registerImportJobFromStaged(request, token.sourceVersion(), committedManifest);
        return new ImportUploadJobDetail(jobId, "UPLOADED", ImportEntity.CUSTOMER.name(),
                contentManifest.sourceSha256().substring(0, 12), version.getVersionNo(), token.encoding().name());
    }

    private static void requireTokenMatch(ImportAdmissionToken token, ImportAdmissionManifest manifest,
                                          ImportAuthorizedContext current, StagedImportDocument staged) {
        if (token == null || manifest == null || staged == null || staged.document() == null
                || staged.version() == null || token.fixtureKey() == null || token.fixtureKey().isBlank()
                || staged.operationId() == null || staged.operationId().isBlank()
                || token.actorUserId() < 1 || token.sourceSystemId() < 1 || token.sourceVersion() < 1
                || token.entity() != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(token.schemaVersion())
                || token.encoding() == null
                || !current.tenantId().equals(token.tenantId()) || current.actorUserId() != token.actorUserId()
                || current.legalEntityId() != token.legalEntityId()
                || !manifest.fixtureKey().equals(token.fixtureKey())
                || !manifest.tenantId().equals(token.tenantId()) || manifest.legalEntityId() != token.legalEntityId()
                || manifest.sourceSystemId() != token.sourceSystemId()
                || manifest.sourceVersion() != token.sourceVersion() || manifest.entity() != ImportEntity.CUSTOMER
                || !SesImportV1Schema.VERSION.equals(manifest.schemaVersion()) || manifest.encoding() != token.encoding()
                || !"A1A_CONTENT".equals(manifest.phase())
                || manifest.sourceSha256() == null || !manifest.sourceSha256().matches("[0-9a-f]{64}")
                || !manifest.sourceSha256().equals(staged.version().getSha256())
                || !"IMPORT".equals(staged.document().getDocumentType())
                || !token.tenantId().equals(staged.document().getTenantId())
                || !Long.toString(token.legalEntityId()).equals(staged.document().getLegalEntityId())
                || !staged.storageKey().equals(staged.version().getStorageKey())
                || staged.version().getSizeBytes() == null || staged.version().getSizeBytes() < 1
                || staged.version().getSizeBytes() > 64L * 1024 * 1024
                || !"CLEAN".equals(staged.version().getScanStatus())) {
            throw BusinessException.of(403, "error.import.admissionMismatch");
        }
    }
}
