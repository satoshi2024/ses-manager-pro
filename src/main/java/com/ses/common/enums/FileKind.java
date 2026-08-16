package com.ses.common.enums;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * アップロード可能なファイル種別。
 * 拡張子ホワイトリスト・最大サイズを保持し、拡張子/MIME/実体構造を組で検証する。
 */
public enum FileKind {

    /** スキルシート: pdf / xlsx / docx、最大10MB */
    SKILL_SHEET(
            Set.of("pdf", "xlsx", "docx"),
            10L * 1024 * 1024),

    /** 顔写真: png / jpg / jpeg、最大2MB */
    PHOTO(
            Set.of("png", "jpg", "jpeg"),
            2L * 1024 * 1024),

    /** 案件メール: eml / txt、最大10MB */
    PROJECT_EMAIL(
            Set.of("eml", "txt"),
            10L * 1024 * 1024),

    /** 外部要員メール: eml / txt、最大10MB */
    BP_EMAIL(
            Set.of("eml", "txt"),
            10L * 1024 * 1024),

    /**
     * 電子契約の署名済みPDF・合意締結証明書（HFP-02-06）。
     * 公式body上限50MBを自システム上限とする。SKILL_SHEETを代用しない。
     */
    CONTRACT_PDF(
            Set.of("pdf"),
            50L * 1024 * 1024);

    private final Set<String> allowedExtensions;
    private final long maxBytes;

    FileKind(Set<String> allowedExtensions, long maxBytes) {
        this.allowedExtensions = allowedExtensions;
        this.maxBytes = maxBytes;
    }

    public boolean isExtensionAllowed(String ext) {
        return ext != null && allowedExtensions.contains(ext.toLowerCase());
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    /** Content-Typeは明示必須。拡張子と完全一致する組だけを許可する。 */
    public boolean isContentTypeAllowed(String extension, String contentType) {
        if (extension == null || contentType == null || contentType.isBlank()) {
            return false;
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        String ct = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "pdf" -> ct.equals("application/pdf");
            case "docx" -> ct.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xlsx" -> ct.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "png" -> ct.equals("image/png");
            case "jpg", "jpeg" -> ct.equals("image/jpeg");
            case "eml" -> ct.equals("message/rfc822");
            case "txt" -> ct.equals("text/plain");
            default -> false;
        };
    }

    /** 拡張子ごとの最低限のmagic byteを検証し、偽装拡張子を拒否する。 */
    public boolean isMagicBytesAllowed(String extension, byte[] data) {
        if (extension == null || data == null || data.length == 0) {
            return false;
        }
        String ext = extension.toLowerCase();
        return switch (ext) {
            case "pdf" -> isPdf(data);
            case "png", "jpg", "jpeg" -> isDecodedImage(ext, data);
            case "docx", "xlsx" -> isOoxml(ext, data);
            case "eml", "txt" -> isText(data);
            default -> false;
        };
    }

    private static boolean isPdf(byte[] data) {
        if (!startsWith(data, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D})) {
            return false;
        }
        String tail = new String(data, Math.max(0, data.length - 1024), Math.min(data.length, 1024),
                StandardCharsets.ISO_8859_1);
        int eof = tail.lastIndexOf("%%EOF");
        return eof >= 0 && tail.substring(eof + 5).trim().isEmpty();
    }

    private static boolean isDecodedImage(String extension, byte[] data) {
        boolean cleanEnd = "png".equals(extension)
                ? endsWith(data, new byte[]{0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82})
                : endsWith(data, new byte[]{(byte) 0xFF, (byte) 0xD9});
        if (!cleanEnd) {
            return false;
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (stream == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                String expected = "png".equals(extension) ? "png" : "jpeg";
                if (!reader.getFormatName().equalsIgnoreCase(expected)) {
                    return false;
                }
                reader.setInput(stream, true, true);
                return reader.getWidth(0) > 0 && reader.getHeight(0) > 0 && reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isOoxml(String extension, byte[] data) {
        if (!startsWith(data, new byte[]{0x50, 0x4B, 0x03, 0x04}) || !hasCleanZipEnd(data)) {
            return false;
        }
        Set<String> entries = new HashSet<>();
        String contentTypes = null;
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++count > 1000 || name == null || name.startsWith("/") || name.contains("\\")
                        || name.contains("../") || name.contains("/../") || isExecutableArchiveEntry(name)) {
                    return false;
                }
                entries.add(name);
                ByteArrayOutputStream captured = "[Content_Types].xml".equals(name)
                        ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > 40L * 1024 * 1024) {
                        return false;
                    }
                    if (captured != null) {
                        if (captured.size() + read > 1024 * 1024) {
                            return false;
                        }
                        captured.write(buffer, 0, read);
                    }
                }
                if (captured != null) {
                    contentTypes = captured.toString(StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (!entries.contains("[Content_Types].xml") || !entries.contains("_rels/.rels")
                || contentTypes == null) {
            return false;
        }
        boolean hasWord = entries.contains("word/document.xml");
        boolean hasExcel = entries.contains("xl/workbook.xml");
        if (hasWord == hasExcel) {
            return false;
        }
        return "docx".equals(extension)
                ? hasWord && contentTypes.contains("wordprocessingml.document.main+xml")
                : hasExcel && contentTypes.contains("spreadsheetml.sheet.main+xml");
    }

    private static boolean isExecutableArchiveEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".js")
                || lower.endsWith(".vbs") || lower.endsWith("vbaproject.bin")
                || lower.contains("/embeddings/") || lower.contains("/oleobjects/");
    }

    private static boolean hasCleanZipEnd(byte[] data) {
        int first = Math.max(0, data.length - 65557);
        for (int i = data.length - 22; i >= first; i--) {
            if ((data[i] & 0xFF) == 0x50 && (data[i + 1] & 0xFF) == 0x4B
                    && (data[i + 2] & 0xFF) == 0x05 && (data[i + 3] & 0xFF) == 0x06) {
                int commentLength = (data[i + 20] & 0xFF) | ((data[i + 21] & 0xFF) << 8);
                return i + 22 + commentLength == data.length;
            }
        }
        return false;
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

    private static boolean endsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        int offset = data.length - signature.length;
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
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
