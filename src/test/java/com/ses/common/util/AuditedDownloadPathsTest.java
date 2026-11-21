package com.ses.common.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-005: {@code ApiAuditFilter} から移した download の判定が、移す前の規則と同じであること（監査と静止中の 503 が
 * 同じ path を download とみなす。U-10）。
 */
class AuditedDownloadPathsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "/api/files/abc.pdf,                                  true",
            "/api/files/,                                         true",
            "/api/contracts/1/pdf/download,                       true",
            "/api/invoices/download/2026-11,                      true",
            "/api/contract-documents/12/artifacts/signed,         true",
            "/api/sales-orders/1/documents/2/download,            true",
            "/api/sales-orders/1/acknowledgement-pdf/download,    true",
            "/api/acceptances/9/document/download,                true",
            "/api/my/change-requests/3/attachment,                true",
            "/api/engineer-change-requests/4/attachment,          true",
            "/api/files,                                          false",
            "/api/acceptances/9/document,                         false",
            "/api/contract-documents/x/artifacts/signed,          false",
            "/api/my/change-requests/3/attachment/extra,          false",
            "/api/engineers,                                      false",
            "/downloads,                                          false"
    })
    void downloadとして監査するpathを判定する(String path, boolean expected) {
        assertThat(AuditedDownloadPaths.isDownloadUri(path)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nullはdownloadではない() {
        assertThat(AuditedDownloadPaths.isDownloadUri(null)).isFalse();
    }
}
