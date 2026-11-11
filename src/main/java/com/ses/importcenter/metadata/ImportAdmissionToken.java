package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;

/** raw bodyを読む前にserver authorityへbindされた不透明token。 */
public record ImportAdmissionToken(String tenantId, long actorUserId, long legalEntityId, long sourceSystemId,
                                   long sourceVersion, ImportEntity entity, String schemaVersion,
                                   ImportEncoding encoding, String fixtureKey) { }
