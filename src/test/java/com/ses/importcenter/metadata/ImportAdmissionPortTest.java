package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportAdmissionPortTest {
    @Test
    void productionDefaultAdmissionAlwaysDeniesBeforeBody() {
        ImportAdmissionPort admission = new DefaultDenyImportAdmissionPort();

        BusinessException failure = assertThrows(BusinessException.class, () -> admission.admitBeforeBody(
                new ImportAuthorizedContext("tenant-a", 1, 2), new ImportUploadSource(3, "source-a", 1),
                ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));

        assertEquals(403, failure.getCode());
        assertThrows(BusinessException.class, () -> admission.admitContent(null, "a".repeat(64)));
    }
}
