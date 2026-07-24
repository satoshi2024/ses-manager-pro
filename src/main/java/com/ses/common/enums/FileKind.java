package com.ses.common.enums;

import java.util.Set;

/**
 * アップロード可能なファイル種別。
 * 拡張子ホワイトリスト・最大サイズ・Content-Type前方一致の許可リストを保持する。
 */
public enum FileKind {

    /** スキルシート: pdf / xlsx / docx、最大10MB */
    SKILL_SHEET(
            Set.of("pdf", "xlsx", "docx"),
            10L * 1024 * 1024,
            Set.of("application/pdf",
                    "application/vnd.openxmlformats-officedocument",
                    "application/msword",
                    "application/vnd.ms-excel",
                    "application/octet-stream")),

    /** 顔写真: png / jpg / jpeg、最大2MB */
    PHOTO(
            Set.of("png", "jpg", "jpeg"),
            2L * 1024 * 1024,
            Set.of("image/", "application/octet-stream")),

    /** 案件メール: eml / txt、最大10MB */
    PROJECT_EMAIL(
            Set.of("eml", "txt"),
            10L * 1024 * 1024,
            Set.of("message/rfc822", "text/plain", "application/octet-stream")),

    /** 外部要員メール: eml / txt、最大10MB */
    BP_EMAIL(
            Set.of("eml", "txt"),
            10L * 1024 * 1024,
            Set.of("message/rfc822", "text/plain", "application/octet-stream"));

    private final Set<String> allowedExtensions;
    private final long maxBytes;
    private final Set<String> allowedContentTypePrefixes;

    FileKind(Set<String> allowedExtensions, long maxBytes, Set<String> allowedContentTypePrefixes) {
        this.allowedExtensions = allowedExtensions;
        this.maxBytes = maxBytes;
        this.allowedContentTypePrefixes = allowedContentTypePrefixes;
    }

    public boolean isExtensionAllowed(String ext) {
        return ext != null && allowedExtensions.contains(ext.toLowerCase());
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    /** Content-Typeが許可リストのいずれかで前方一致すればtrue（未指定はtrue扱い） */
    public boolean isContentTypeAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String ct = contentType.toLowerCase();
        return allowedContentTypePrefixes.stream().anyMatch(ct::startsWith);
    }

    public String allowedExtensionsLabel() {
        return String.join(" / ", allowedExtensions);
    }
}
