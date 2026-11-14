package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;

/** server/test-fixtureが生成するingestion manifest。HTTP DTOとしては公開しない。 */
public record ImportAdmissionManifest(String fixtureKey, String tenantId, long legalEntityId,
                                      long sourceSystemId, long sourceVersion, ImportEntity entity,
                                      String schemaVersion, ImportEncoding encoding, String sourceSha256,
                                      Long documentId, Long documentVersionId, Integer documentVersionNo,
                                      String documentSha256, String phase) { }
