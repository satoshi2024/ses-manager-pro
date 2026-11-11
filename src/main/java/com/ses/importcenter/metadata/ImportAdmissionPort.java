package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;

/** 実source approvalがない間はmain default-DENY。positive implementationはtest fixture限定。 */
public interface ImportAdmissionPort {
    ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context, ImportUploadSource source,
                                         ImportEntity entity, String schemaVersion, ImportEncoding encoding);

    ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256);
}
