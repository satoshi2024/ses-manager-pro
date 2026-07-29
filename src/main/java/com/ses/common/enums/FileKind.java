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
                    "application/vnd.ms-excel")),

    /** 顔写真: png / jpg / jpeg、最大2MB */
    PHOTO(
            Set.of("png", "jpg", "jpeg"),
            2L * 1024 * 1024,
            Set.of("image/")),

    /** 案件メール: eml / txt、最大10MB */
    PROJECT_EMAIL(
            Set.of("eml", "txt"),
            10L * 1024 * 1024,
            Set.of("message/rfc822", "text/plain")),

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

    /** Content-Typeは明示必須。許可されたprefixのいずれかに一致すればtrue。 */
    public boolean isContentTypeAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return allowedContentTypePrefixes.stream().anyMatch(ct::startsWith);
    }

    /** 拡張子ごとの最低限のmagic byteを検証し、偽装拡張子を拒否する。 */
    public boolean isMagicBytesAllowed(String extension, byte[] data) {
        if (extension == null || data == null || data.length == 0) {
            return false;
        }
        String ext = extension.toLowerCase();
        return switch (ext) {
            case "pdf" -> startsWith(data, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D});
            case "png" -> startsWith(data, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg", "jpeg" -> startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "docx", "xlsx" -> startsWith(data, new byte[]{0x50, 0x4B, 0x03, 0x04})
                    || startsWith(data, new byte[]{0x50, 0x4B, 0x05, 0x06});
            case "eml", "txt" -> isText(data);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isText(byte[] data) {
        for (byte value : data) {
            if (value == 0) {
                return false;
            }
        }
        return true;
    }

    public String allowedExtensionsLabel() {
        return String.join(" / ", allowedExtensions);
    }
}
