package com.ses.importcenter.foundation;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** BOM/Shift_JISを明示選択して読み取る、上限付きstream CSV parser。 */
public final class SesCsvParser {
    private final ImportLimits limits;

    public SesCsvParser() { this(ImportLimits.DEFAULT); }
    public SesCsvParser(ImportLimits limits) { this.limits = limits; }

    public ParseSummary parse(InputStream source, String fileName, ImportEntity entity,
                              ImportEncoding encoding, Consumer<SourceRow> rowConsumer) throws IOException {
        return parseInternal(source, fileName, entity, encoding, null, rowConsumer);
    }

    /** 明示mappingだけを適用する。既存F2 parse入口のschema header規則は変更しない。 */
    public ParseSummary parseMapped(InputStream source, String fileName, ImportEntity entity,
                                    ImportEncoding encoding, Map<String, String> sourceToCanonical,
                                    Consumer<SourceRow> rowConsumer) throws IOException {
        if (sourceToCanonical == null || sourceToCanonical.isEmpty()) {
            throw new IllegalArgumentException("明示header mappingが必要です");
        }
        return parseInternal(source, fileName, entity, encoding, Map.copyOf(sourceToCanonical), rowConsumer);
    }

    private ParseSummary parseInternal(InputStream source, String fileName, ImportEntity entity,
                                       ImportEncoding encoding, Map<String, String> sourceToCanonical,
                                       Consumer<SourceRow> rowConsumer) throws IOException {
        if (source == null || fileName == null || entity == null || encoding == null || rowConsumer == null) {
            throw new IllegalArgumentException("入力source、file名、entity、encoding、row consumerが必要です");
        }
        String lowerName = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!lowerName.endsWith(".csv")) throw new CsvFormatException("CSV以外の拡張子は受け付けません");
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            throw new CsvFormatException("XLSX/XLSは受け付けません");
        }
        MessageDigest digest = sha256();
        DigestInputStream digestStream = new DigestInputStream(new BoundedInputStream(source, limits.maxSourceBytes()), digest);
        PushbackInputStream bytes = new PushbackInputStream(digestStream, 4);
        byte[] prefix = bytes.readNBytes(4);
        if (prefix.length >= 2 && prefix[0] == 'P' && prefix[1] == 'K') {
            throw new CsvFormatException("ZIP/XLSX形式の入力は受け付けません");
        }
        boolean bom = prefix.length >= 3 && (prefix[0] & 0xff) == 0xef
                && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf;
        if (bom && encoding != ImportEncoding.UTF8) {
            throw new CsvFormatException("UTF-8 BOMと選択encodingが一致しません");
        }
        int offset = bom ? 3 : 0;
        if (prefix.length > offset) bytes.unread(prefix, offset, prefix.length - offset);
        var decoder = encoding.charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader raw = new InputStreamReader(bytes, decoder); PushbackReader reader = new PushbackReader(raw, 1)) {
            RecordReader records = new RecordReader(reader, limits.maxFieldChars(), limits.maxColumns());
            List<String> header = records.next();
            if (header == null) throw new CsvFormatException("headerがありません");
            SesImportV1Schema.Definition definition = SesImportV1Schema.definition(entity);
            if (sourceToCanonical == null) validateHeader(header, definition);
            else validateMappedHeader(header, definition, sourceToCanonical);
            long physicalRows = 0;
            long emptySkipped = 0;
            long accepted = 0;
            for (List<String> values; (values = records.next()) != null;) {
                physicalRows++;
                if (physicalRows > limits.maxRows()) throw new CsvFormatException("最大source row数を超過しました");
                if (values.stream().allMatch(String::isEmpty)) { emptySkipped++; continue; }
                if (values.size() != header.size()) throw new CsvFormatException("headerとdata rowの列数が一致しません: row=" + physicalRows);
                Map<String, String> mapped = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    String outputHeader = sourceToCanonical == null ? header.get(i) : sourceToCanonical.get(header.get(i));
                    mapped.put(outputHeader, values.get(i));
                }
                String rowHash = hashRow(values);
                rowConsumer.accept(new SourceRow(physicalRows, rowHash, mapped));
                accepted++;
            }
            String sourceHash = HexFormat.of().formatHex(digest.digest());
            return new ParseSummary(sourceHash, physicalRows, accepted, emptySkipped,
                    records.logicalRecords(), bom, encoding);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new CsvFormatException("選択encodingで復号できません", exception);
        }
    }

    private static void validateHeader(List<String> header, SesImportV1Schema.Definition definition) throws IOException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String field : header) {
            if (field.isBlank()) throw new CsvFormatException("空headerは受け付けません");
            if (!seen.add(field)) throw new CsvFormatException("重複headerを拒否しました: " + field);
            if (!definition.allowedHeaders().contains(field)) throw new CsvFormatException("未知headerを拒否しました: " + field);
        }
        for (String required : definition.requiredHeaders()) {
            if (!seen.contains(required)) throw new CsvFormatException("必須headerがありません: " + required);
        }
    }

    private static void validateMappedHeader(List<String> header, SesImportV1Schema.Definition definition,
                                             Map<String, String> sourceToCanonical) throws IOException {
        Set<String> seen = new java.util.HashSet<>();
        Set<String> targets = new java.util.HashSet<>();
        for (String sourceHeader : header) {
            if (sourceHeader.isBlank() || !seen.add(sourceHeader)) {
                throw new CsvFormatException("空または重複headerを拒否しました");
            }
            String canonical = sourceToCanonical.get(sourceHeader);
            if (canonical == null || !definition.allowedHeaders().contains(canonical) || !targets.add(canonical)) {
                throw new CsvFormatException("header mappingが不正です");
            }
        }
        if (seen.size() != sourceToCanonical.size() || !seen.containsAll(sourceToCanonical.keySet())
                || !targets.containsAll(definition.requiredHeaders())) {
            throw new CsvFormatException("header mappingがsource schemaと一致しません");
        }
    }

    static String hashRow(List<String> values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256が利用できません", exception); }
    }

    public record ParseSummary(String sourceSha256, long physicalRows, long sourceRows,
                               long emptySkipped, long logicalRecords, boolean utf8Bom,
                               ImportEncoding encoding) { }

    public static final class CsvFormatException extends IOException {
        public CsvFormatException(String message) { super(message); }
        public CsvFormatException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long max;
        private long count;
        private BoundedInputStream(InputStream input, long max) { super(input); this.max = max; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > max) throw new CsvFormatException("source byte上限を超過しました");
            return value;
        }
        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            int read = super.read(bytes, off, len);
            if (read > 0 && (count += read) > max) throw new CsvFormatException("source byte上限を超過しました");
            return read;
        }
    }

    private static final class RecordReader {
        private final PushbackReader input;
        private final int maxFieldChars;
        private final int maxColumns;
        private long logicalRecords;
        private RecordReader(PushbackReader input, int maxFieldChars, int maxColumns) {
            this.input = input; this.maxFieldChars = maxFieldChars; this.maxColumns = maxColumns;
        }
        private long logicalRecords() { return logicalRecords; }

        private List<String> next() throws IOException {
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            State state = State.START;
            boolean sawAnything = false;
            while (true) {
                int read = input.read();
                if (read < 0) {
                    if (state == State.QUOTED) throw malformed("閉じquoteがありません");
                    if (!sawAnything && row.isEmpty() && field.isEmpty()) return null;
                    row.add(field.toString());
                    logicalRecords++;
                    checkColumns(row.size());
                    return row;
                }
                char c = (char) read;
                sawAnything = true;
                if (state == State.QUOTED) {
                    if (c == '"') {
                        int next = input.read();
                        if (next == '"') append(field, '"');
                        else { state = State.AFTER_QUOTE; if (next >= 0) input.unread(next); }
                    } else append(field, c);
                    continue;
                }
                if (state == State.AFTER_QUOTE) {
                    if (c == ',') { finishField(row, field); state = State.START; sawAnything = true; }
                    else if (c == '\r' || c == '\n') { finishField(row, field); consumeLf(c); logicalRecords++; checkColumns(row.size()); return row; }
                    else throw malformed("閉じquote後に不正な文字があります");
                    continue;
                }
                if (state == State.START && c == '"') { state = State.QUOTED; continue; }
                if (c == '"') throw malformed("quoted fieldの途中にquoteがあります");
                if (c == ',') { finishField(row, field); state = State.START; continue; }
                if (c == '\r' || c == '\n') { finishField(row, field); consumeLf(c); logicalRecords++; checkColumns(row.size()); return row; }
                append(field, c);
                state = State.UNQUOTED;
            }
        }

        private void consumeLf(char c) throws IOException {
            if (c == '\r') { int next = input.read(); if (next >= 0 && next != '\n') input.unread(next); }
        }
        private void append(StringBuilder field, char c) throws IOException {
            if (field.length() >= maxFieldChars) throw new CsvFormatException("最大field長を超過しました");
            field.append(c);
        }
        private void finishField(List<String> row, StringBuilder field) throws IOException {
            row.add(field.toString()); field.setLength(0); checkColumns(row.size());
        }
        private void checkColumns(int count) throws IOException {
            if (count > maxColumns) throw new CsvFormatException("最大column数を超過しました");
        }
        private CsvFormatException malformed(String reason) { return new CsvFormatException("CSV構造が不正です（record=" + (logicalRecords + 1) + "): " + reason); }
        private enum State { START, UNQUOTED, QUOTED, AFTER_QUOTE }
    }
}
