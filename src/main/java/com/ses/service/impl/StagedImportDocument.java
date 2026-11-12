package com.ses.service.impl;

import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;

/** storageへ配置・CLEAN scan済み、まだmetadata commitしていないrequest所有upload。 */
public record StagedImportDocument(Document document, DocumentVersion version, String storageKey, String operationId) { }
