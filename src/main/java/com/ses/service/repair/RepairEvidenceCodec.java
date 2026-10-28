package com.ses.service.repair;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 境界A 修復証跡（SES_NF09_COMPL / SES_NF09_MEMBER）の厳格エンコード・デコードおよび暗号学的ダイジェスト算出コンポーネント。
 * <p>
 * 共有 ObjectMapper を改変せず、コンポーネント独立の厳格設定（重複キー禁止・末尾余剰トークン禁止・数値型厳格検証）を保持する。
 * </p>
 */
public class RepairEvidenceCodec {

    public static final String CARRIER_PREFIX = "SES_NF09_COMPL:";
    public static final String MEMBER_PREFIX = "SES_NF09_MEMBER:";
    public static final int MAX_UTF8_BYTES = 500;

    private final ObjectMapper objectMapper;

    public RepairEvidenceCodec(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "objectMapper must not be null");
        // 共有 ObjectMapper を直接変更せず、コンポーネント独立の厳格設定を持つコピーを生成
        this.objectMapper = mapper.copy();
        this.objectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public RepairEvidenceCodec() {
        this(new ObjectMapper());
    }

    /**
     * 代表載体証跡レコードを固定標識付きコンパクト文字列へシリアライズする。
     *
     * @param evidence 代表載体証跡レコード
     * @return シリアライズ後文字列
     * @throws IllegalStateException UTF-8実バイト長が500バイトを超過した場合
     */
    public String encodeCarrier(CarrierEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        try {
            String json = objectMapper.writeValueAsString(evidence);
            String note = CARRIER_PREFIX + json;
            byte[] bytes = note.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_UTF8_BYTES) {
                throw new IllegalStateException("シリアライズ後の証跡バイト長が500バイトを超過しています: length=" + bytes.length);
            }
            return note;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("代表載体証跡のシリアライズに失敗しました", e);
        }
    }

    /**
     * 参加部材参照レコードを固定標識付きコンパクト文字列へシリアライズする。
     *
     * @param evidence 参加部材参照レコード
     * @return シリアライズ後文字列
     * @throws IllegalStateException UTF-8実バイト長が500バイトを超過した場合
     */
    public String encodeMember(MemberEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        try {
            String json = objectMapper.writeValueAsString(evidence);
            String note = MEMBER_PREFIX + json;
            byte[] bytes = note.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_UTF8_BYTES) {
                throw new IllegalStateException("シリアライズ後の部材参照バイト長が500バイトを超過しています: length=" + bytes.length);
            }
            return note;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("参加部材参照のシリアライズに失敗しました", e);
        }
    }

    /**
     * 文字列から代表載体証跡レコードを厳格にパースする。
     * <p>
     * 固定標識プレフィックスを持たない歴史的普通備考の場合は null を返却して安全に無視する。
     * 固定標識プレフィックスを持つが構文やスキーマが破損している場合は CorruptedEvidenceException を送出する。
     * </p>
     *
     * @param note キューの resolution_note 文字列
     * @return パースされた代表載体証跡レコード、または非プロトコル行の場合は null
     * @throws CorruptedEvidenceException プロトコル標識行が破損している場合
     */
    public CarrierEvidence parseCarrier(String note) {
        if (note == null || !note.startsWith(CARRIER_PREFIX)) {
            return null;
        }
        String json = note.substring(CARRIER_PREFIX.length());
        try (JsonParser parser = objectMapper.createParser(json)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || !node.isObject()) {
                throw new CorruptedEvidenceException("代表載体証跡がJSONオブジェクトではありません");
            }
            // 末尾に余剰トークンや第2のJSONが存在しないことを厳格検証
            if (parser.nextToken() != null) {
                throw new CorruptedEvidenceException("JSON末尾に余分なトークンまたは第2のJSONが存在します: " + parser.currentToken());
            }

            int v = getRequiredInt(node, "v");
            String ticket = getRequiredString(node, "ticket");
            int round = getRequiredInt(node, "round");
            long invId = getRequiredLong(node, "invId");
            String tenant = getRequiredString(node, "tenant");
            long legalEntity = getRequiredLong(node, "legalEntity");
            String pCls = getRequiredString(node, "pCls");
            long vBf = getRequiredLong(node, "vBf");
            long vAf = getRequiredLong(node, "vAf");
            int qc = getRequiredInt(node, "qc");
            String qh = getRequiredString(node, "qh");
            long by = getRequiredLong(node, "by");
            String at = getRequiredString(node, "at");

            return new CarrierEvidence(v, ticket, round, invId, tenant, legalEntity, pCls, vBf, vAf, qc, qh, by, at);
        } catch (CorruptedEvidenceException e) {
            throw e;
        } catch (Exception e) {
            throw new CorruptedEvidenceException("代表載体証跡の解析に失敗しました", e);
        }
    }

    /**
     * 文字列から参加部材参照レコードを厳格にパースする。
     * <p>
     * 固定標識プレフィックスを持たない歴史的普通備考の場合は null を返却して安全に無視する。
     * 固定標識プレフィックスを持つが構文やスキーマが破損している場合は CorruptedEvidenceException を送出する。
     * </p>
     *
     * @param note キューの resolution_note 文字列
     * @return パースされた参加部材参照レコード、または非プロトコル行の場合は null
     * @throws CorruptedEvidenceException プロトコル標識行が破損している場合
     */
    public MemberEvidence parseMember(String note) {
        if (note == null || !note.startsWith(MEMBER_PREFIX)) {
            return null;
        }
        String json = note.substring(MEMBER_PREFIX.length());
        try (JsonParser parser = objectMapper.createParser(json)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || !node.isObject()) {
                throw new CorruptedEvidenceException("参加部材参照がJSONオブジェクトではありません");
            }
            // 末尾に余剰トークンや第2のJSONが存在しないことを厳格検証
            if (parser.nextToken() != null) {
                throw new CorruptedEvidenceException("JSON末尾に余分なトークンまたは第2のJSONが存在します: " + parser.currentToken());
            }

            int v = getRequiredInt(node, "v");
            long carrierId = getRequiredLong(node, "carrierId");
            String ticket = getRequiredString(node, "ticket");
            int round = getRequiredInt(node, "round");

            return new MemberEvidence(v, carrierId, ticket, round);
        } catch (CorruptedEvidenceException e) {
            throw e;
        } catch (Exception e) {
            throw new CorruptedEvidenceException("参加部材参照の解析に失敗しました", e);
        }
    }

    /**
     * 参加キューID集合を昇順ソートし、カンマ区切り列の小文字 SHA-256 ダイジェスト（64文字）を算出する。
     *
     * @param queueIds 参加キューID集合
     * @return 64文字小文字16進数ダイジェスト文字列
     */
    public String calculateQueueDigest(Collection<Long> queueIds) {
        if (queueIds == null || queueIds.isEmpty()) {
            throw new IllegalArgumentException("キューID集合が空です");
        }
        List<Long> sorted = queueIds.stream().sorted().toList();
        String joined = sorted.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用不可です", e);
        }
    }

    private JsonNode getRequiredField(JsonNode node, String fieldName) {
        JsonNode f = node.get(fieldName);
        if (f == null || f.isNull()) {
            throw new CorruptedEvidenceException("必須フィールドが欠落またはNULLです: " + fieldName);
        }
        return f;
    }

    private int getRequiredInt(JsonNode node, String fieldName) {
        JsonNode f = getRequiredField(node, fieldName);
        if (f.isTextual()) {
            throw new CorruptedEvidenceException("数値フィールドが文字列形式で指定されています: " + fieldName);
        }
        if (f.isFloatingPointNumber()) {
            throw new CorruptedEvidenceException("整数フィールドに小数または浮動小数点数が指定されています: " + fieldName);
        }
        if (!f.isIntegralNumber() || !f.canConvertToInt()) {
            throw new CorruptedEvidenceException("整数値がint型の表現範囲外です: " + fieldName);
        }
        return f.asInt();
    }

    private long getRequiredLong(JsonNode node, String fieldName) {
        JsonNode f = getRequiredField(node, fieldName);
        if (f.isTextual()) {
            throw new CorruptedEvidenceException("数値フィールドが文字列形式で指定されています: " + fieldName);
        }
        if (f.isFloatingPointNumber()) {
            throw new CorruptedEvidenceException("整数フィールドに小数または浮動小数点数が指定されています: " + fieldName);
        }
        if (!f.isIntegralNumber() || !f.canConvertToLong()) {
            throw new CorruptedEvidenceException("整数値がlong型の表現範囲外です: " + fieldName);
        }
        return f.asLong();
    }

    private String getRequiredString(JsonNode node, String fieldName) {
        JsonNode f = getRequiredField(node, fieldName);
        if (!f.isTextual()) {
            throw new CorruptedEvidenceException("文字列フィールドが非テキスト形式で指定されています: " + fieldName);
        }
        return f.asText();
    }
}
