package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import org.springframework.stereotype.Component;

/** real-source approval bindingが別途整うまで、production/main ingestionを常に拒否する。 */
@Component
public final class DefaultDenyImportAdmissionPort implements ImportAdmissionPort {
    @Override
    public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context, ImportUploadSource source,
                                                ImportEntity entity, String schemaVersion, ImportEncoding encoding) {
        throw BusinessException.of(403, "error.import.sourceNotApproved");
    }

    @Override
    public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256) {
        throw BusinessException.of(403, "error.import.sourceNotApproved");
    }
}
