package com.ses.common.enums;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A02: 機能の開放の署名の文書（{@link FileKind#FEATURE_SIGN_OFF}）は pdf だけ・10 MB まで（multipart の上限の中）で、PDF の
 * magic byte と末尾の検査は既存の pdf の分岐を使い、専用の経路だけが保存・取得する種別であること（design §6.1）。
 */
class FileKindTest {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void 署名の文書はpdfだけで10MBまで() {
        FileKind kind = FileKind.FEATURE_SIGN_OFF;

        assertThat(kind.isExtensionAllowed("pdf")).isTrue();
        assertThat(kind.isExtensionAllowed("PDF")).isTrue();
        for (String other : new String[]{"docx", "xlsx", "png", "jpg", "json", "csv", "txt", "eml", "zip"}) {
            assertThat(kind.isExtensionAllowed(other)).as(other).isFalse();
        }
        assertThat(kind.getMaxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(kind.isContentTypeAllowed("pdf", "application/pdf")).isTrue();
        assertThat(kind.isContentTypeAllowed("pdf", "application/octet-stream")).isFalse();
        assertThat(kind.isMagicBytesAllowed("pdf", PDF)).isTrue();
        assertThat(kind.isMagicBytesAllowed("pdf", "%PDF-1.7\ntruncated".getBytes(StandardCharsets.US_ASCII)))
                .as("末尾の %%EOF の無い PDF").isFalse();
    }

    @Test
    void 署名の文書は専用の経路だけの種別でserver生成の種別ではない() {
        assertThat(FileKind.FEATURE_SIGN_OFF.isDedicatedPathOnly()).isTrue();
        assertThat(FileKind.FEATURE_SIGN_OFF.isServerGeneratedOnly()).isFalse();
        assertThat(FileKind.PRIVACY_EXPORT.isDedicatedPathOnly()).as("server 生成の種別も専用の経路だけ").isTrue();
        for (FileKind kind : FileKind.values()) {
            if (kind.isServerGeneratedOnly()) {
                assertThat(kind.isDedicatedPathOnly()).as(kind.name()).isTrue();
            }
        }
        assertThat(FileKind.SKILL_SHEET.isDedicatedPathOnly()).isFalse();
        assertThat(FileKind.CONTRACT_PDF.isDedicatedPathOnly()).isFalse();
    }
}
