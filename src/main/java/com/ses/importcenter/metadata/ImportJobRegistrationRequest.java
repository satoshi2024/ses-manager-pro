package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;

/** 既存CLEAN exact document versionを登録するmetadata input。権威tenant/法人は含めない。 */
public record ImportJobRegistrationRequest(long sourceSystemId, ImportEntity entity, String schemaVersion,
                                           ImportEncoding encoding, String sourceSha256Hex,
                                           long documentId, long documentVersionId, int documentVersionNo) { }
