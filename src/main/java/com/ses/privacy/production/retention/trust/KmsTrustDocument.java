package com.ses.privacy.production.retention.trust;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseTrustAnchor;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.TrustedAuthority;
import com.ses.privacy.production.retention.hold.ConfiguredHoldReleaseTrustRegistry;
import com.ses.privacy.production.retention.policy.ConfiguredPolicyTrustRegistry;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NF07 の trust 文書（D-03 = AWS KMS 非対称署名、鍵 version 単位の失効）。{@link KmsPrivacyTrustSource} が file から読む。
 *
 * <p>文書は D-03 owner が作る JSON で、署名鍵ごとに1件の anchor（authority ID・key ID・KMS key ARN・公開鍵・役割・有効期間・
 * tenant・状態）と、policy envelope の {@code revocationReference} ごとの状態を持つ。鍵の失効は anchor の {@code status} を
 * {@code REVOKED} にする（鍵 version ＝ KMS key 1つ ＝ anchor 1件。CRL / OCSP は使わない）。形式は deploy-runbook §4.6。
 *
 * <p>読み方は厳格で、次のどれかに当たれば文書全体を拒否する（{@link InvalidDocumentException}。固定の code と位置だけを持ち、
 * 値は持たない）: JSON でない・末尾の余分な token・同じ名前の field の重複・未知の field・必須の field の欠落・型の違い、
 * {@code format} が {@value #FORMAT} でない、authority ID・key ID が {@code [A-Za-z0-9._-]{1,64}} でない（{@code :} を含めない
 * ため registry の key {@code authorityId:keyId} が一意に分解できる）、KMS key ARN が key の ARN でない（alias は付け替えられる
 * ため不可）、key spec が {@value #KEY_SPEC} でない、署名算法が {@value #SIGNING_ALGORITHM} でない（prehash 版の
 * {@code ED25519_PH_SHA_512} は不可）、公開鍵が Ed25519 の SubjectPublicKeyInfo でない（{@link KmsEd25519PublicKeys}）、
 * 状態が {@code ACTIVE} / {@code REVOKED} でない、有効期間が空・逆順、範囲（tenant 等）が空・重複・文字種外、anchor ID の重複
 * （policy と hold 解除をまたいでも不可。失効状態の照会の名前空間が1つのため）、同じ KMS key ARN・同じ公開鍵の anchor が2件以上、
 * envelope の参照が anchor ID と同じ。
 *
 * <p>policy の anchor の {@code envelopeKinds} は {@code POLICY}、{@code audiences} は {@code PRODUCTION} に固定する（verifier は
 * 本番の policy envelope にこの2つを要求し、それ以外の値は使われないため、文書には書かせない）。解除の anchor の主体は
 * {@code sys_user:<id>} の正規形（{@link HoldReleaseTrustAnchor} の constructor が検査する）。不変で、状態を持たない。
 */
final class KmsTrustDocument {

    /** 文書の形式の版。 */
    static final String FORMAT = "ses-nf07-kms-trust-v1";
    /** AWS KMS の Ed25519 の key spec。 */
    static final String KEY_SPEC = "ECC_NIST_EDWARDS25519";
    /** AWS KMS の純粋な Ed25519 の署名算法（{@code MessageType=RAW} で使う）。 */
    static final String SIGNING_ALGORITHM = "ED25519_SHA_512";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SCOPE_ITEM = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    /** key の ARN（partition・region・12桁の account・key ID（UUID か multi-Region key の {@code mrk-}））。alias は不可。 */
    private static final Pattern KMS_KEY_ARN = Pattern.compile(
            "arn:aws(?:-[a-z]+)*:kms:[a-z]{2}(?:-[a-z]+)+-[0-9]{1,2}:[0-9]{12}:key/"
                    + "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|mrk-[0-9a-f]{32})");
    private static final int MAX_REFERENCE_LENGTH = 256;

    private static final Set<String> TOP_FIELDS = Set.of("format", "generatedAt", "policyAnchors",
            "holdReleaseAnchors", "envelopeStatuses");
    private static final Set<String> COMMON_ANCHOR_FIELDS = Set.of("authorityId", "keyId", "kmsKeyArn", "keySpec",
            "signingAlgorithm", "publicKey", "status", "validFrom", "validUntil", "tenants");
    private static final Set<String> POLICY_ANCHOR_FIELDS = union(COMMON_ANCHOR_FIELDS,
            Set.of("scopes", "dataElements", "actions", "providers"));
    private static final Set<String> HOLD_ANCHOR_FIELDS = union(COMMON_ANCHOR_FIELDS,
            Set.of("principal", "roles", "targetTypes"));
    private static final Set<String> TARGET_TYPES = Set.of("SUBJECT", "DATA_ELEMENT");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Instant generatedAt;
    private final ConfiguredPolicyTrustRegistry policyRegistry;
    private final ConfiguredHoldReleaseTrustRegistry holdReleaseRegistry;
    private final Map<String, RevocationStatus> keyStatuses;
    private final Map<String, RevocationStatus> envelopeStatuses;

    private KmsTrustDocument(Instant generatedAt, Map<String, TrustedAuthority> policyAnchors,
                             Map<String, HoldReleaseTrustAnchor> holdReleaseAnchors,
                             Map<String, RevocationStatus> keyStatuses, Map<String, RevocationStatus> envelopeStatuses) {
        this.generatedAt = generatedAt;
        this.policyRegistry = new ConfiguredPolicyTrustRegistry(policyAnchors);
        this.holdReleaseRegistry = new ConfiguredHoldReleaseTrustRegistry(holdReleaseAnchors);
        this.keyStatuses = Map.copyOf(keyStatuses);
        this.envelopeStatuses = Map.copyOf(envelopeStatuses);
    }

    /** 文書の作成日時（鮮度の判定に使う）。 */
    Instant generatedAt() {
        return generatedAt;
    }

    /** policy envelope の anchor（失効した鍵の anchor も含む。失効は {@link #statusOf} が答える）。 */
    ConfiguredPolicyTrustRegistry policyRegistry() {
        return policyRegistry;
    }

    /** hold 解除の anchor（失効した鍵の anchor も含む）。 */
    ConfiguredHoldReleaseTrustRegistry holdReleaseRegistry() {
        return holdReleaseRegistry;
    }

    /** 鍵（anchor ID）の状態。文書に無ければ null。 */
    RevocationStatus keyStatusOf(String anchorId) {
        return anchorId == null ? null : keyStatuses.get(anchorId);
    }

    /** envelope の参照の状態。文書に無ければ null。 */
    RevocationStatus envelopeStatusOf(String reference) {
        return reference == null ? null : envelopeStatuses.get(reference);
    }

    /** 文書が REVOKED と書いた鍵と envelope の参照（{@link KmsPrivacyTrustSource} の失効の固定に使う）。 */
    Set<String> revokedReferences() {
        Set<String> revoked = new HashSet<>();
        keyStatuses.forEach((id, status) -> {
            if (status == RevocationStatus.REVOKED) {
                revoked.add(id);
            }
        });
        envelopeStatuses.forEach((reference, status) -> {
            if (status == RevocationStatus.REVOKED) {
                revoked.add(reference);
            }
        });
        return revoked;
    }

    /** {@code ACTIVE} と書いた鍵と envelope の参照。 */
    Set<String> activeReferences() {
        Set<String> active = new HashSet<>();
        keyStatuses.forEach((id, status) -> {
            if (status == RevocationStatus.ACTIVE) {
                active.add(id);
            }
        });
        envelopeStatuses.forEach((reference, status) -> {
            if (status == RevocationStatus.ACTIVE) {
                active.add(reference);
            }
        });
        return active;
    }

    /**
     * 文書を厳格に読む。
     *
     * @throws InvalidDocumentException 文書が上の規則に合わない
     */
    static KmsTrustDocument parse(byte[] json) {
        if (json == null || json.length == 0) {
            throw new InvalidDocumentException("document.empty", "$");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException | RuntimeException e) {
            throw new InvalidDocumentException("document.notJson", "$");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidDocumentException("document.notObject", "$");
        }
        requireFields(root, TOP_FIELDS, "$");
        if (!FORMAT.equals(text(root, "format", "$"))) {
            throw new InvalidDocumentException("document.format", "$.format");
        }
        Instant generatedAt = instant(root, "generatedAt", "$");

        Map<String, TrustedAuthority> policyAnchors = new LinkedHashMap<>();
        Map<String, HoldReleaseTrustAnchor> holdAnchors = new LinkedHashMap<>();
        Map<String, RevocationStatus> keyStatuses = new LinkedHashMap<>();
        Map<String, String> arns = new HashMap<>();
        Set<PublicKey> keys = new HashSet<>();

        JsonNode policies = array(root, "policyAnchors", "$");
        for (int i = 0; i < policies.size(); i++) {
            String at = "$.policyAnchors[" + i + "]";
            JsonNode node = object(policies.get(i), at);
            requireFields(node, POLICY_ANCHOR_FIELDS, at);
            Common common = common(node, at, keyStatuses, arns, keys);
            TrustedAuthority anchor;
            try {
                anchor = new TrustedAuthority(common.authorityId, common.keyId, common.publicKey,
                        items(node, "tenants", at), items(node, "scopes", at), items(node, "dataElements", at),
                        Set.of("POLICY"), Set.of("PRODUCTION"), items(node, "actions", at),
                        items(node, "providers", at), common.validFrom, common.validUntil);
            } catch (IllegalArgumentException e) {
                throw new InvalidDocumentException("anchor.invalid", at);
            }
            policyAnchors.put(common.anchorId(), anchor);
            keyStatuses.put(common.anchorId(), common.status);
        }

        JsonNode holds = array(root, "holdReleaseAnchors", "$");
        for (int i = 0; i < holds.size(); i++) {
            String at = "$.holdReleaseAnchors[" + i + "]";
            JsonNode node = object(holds.get(i), at);
            requireFields(node, HOLD_ANCHOR_FIELDS, at);
            Common common = common(node, at, keyStatuses, arns, keys);
            Set<HoldReleaseAuthorityClaim.Role> roles = new LinkedHashSet<>();
            for (String role : items(node, "roles", at)) {
                try {
                    roles.add(HoldReleaseAuthorityClaim.Role.valueOf(role));
                } catch (IllegalArgumentException e) {
                    throw new InvalidDocumentException("anchor.roles", at + ".roles");
                }
            }
            Set<String> targetTypes = items(node, "targetTypes", at);
            if (!TARGET_TYPES.containsAll(targetTypes)) {
                throw new InvalidDocumentException("anchor.targetTypes", at + ".targetTypes");
            }
            HoldReleaseTrustAnchor anchor;
            try {
                anchor = new HoldReleaseTrustAnchor(common.authorityId, common.keyId, common.publicKey,
                        text(node, "principal", at), items(node, "tenants", at), roles, targetTypes,
                        common.validFrom, common.validUntil);
            } catch (IllegalArgumentException e) {
                throw new InvalidDocumentException("anchor.principal", at + ".principal");
            }
            holdAnchors.put(common.anchorId(), anchor);
            keyStatuses.put(common.anchorId(), common.status);
        }

        Map<String, RevocationStatus> envelopeStatuses = new LinkedHashMap<>();
        JsonNode envelopes = root.get("envelopeStatuses");
        if (envelopes == null || !envelopes.isObject()) {
            throw new InvalidDocumentException("document.field.type", "$.envelopeStatuses");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = envelopes.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String reference = entry.getKey();
            String at = "$.envelopeStatuses";
            if (!validReference(reference)) {
                throw new InvalidDocumentException("envelope.reference", at);
            }
            if (keyStatuses.containsKey(reference)) {
                // 失効状態の照会は鍵と envelope の参照が同じ名前空間のため、anchor ID と同じ参照は区別できない
                throw new InvalidDocumentException("envelope.collidesWithAnchor", at);
            }
            envelopeStatuses.put(reference, status(entry.getValue(), at));
        }
        try {
            return new KmsTrustDocument(generatedAt, policyAnchors, holdAnchors, keyStatuses, envelopeStatuses);
        } catch (IllegalArgumentException e) {
            // Configured*TrustRegistry の key・鍵の算法の検査（上で同じ規則を検査済みのため通常は起きない）
            throw new InvalidDocumentException("anchor.registry", "$");
        }
    }

    /** anchor の共通項目（検査済み）。 */
    private record Common(String authorityId, String keyId, PublicKey publicKey, RevocationStatus status,
                          Instant validFrom, Instant validUntil) {
        String anchorId() {
            return authorityId + ":" + keyId;
        }
    }

    private static Common common(JsonNode node, String at, Map<String, RevocationStatus> seenAnchors,
                                 Map<String, String> arns, Set<PublicKey> keys) {
        String authorityId = text(node, "authorityId", at);
        if (!IDENTIFIER.matcher(authorityId).matches()) {
            throw new InvalidDocumentException("anchor.authorityId", at + ".authorityId");
        }
        String keyId = text(node, "keyId", at);
        if (!IDENTIFIER.matcher(keyId).matches()) {
            throw new InvalidDocumentException("anchor.keyId", at + ".keyId");
        }
        String anchorId = authorityId + ":" + keyId;
        if (seenAnchors.containsKey(anchorId)) {
            throw new InvalidDocumentException("anchor.duplicate", at);
        }
        String arn = text(node, "kmsKeyArn", at);
        if (!KMS_KEY_ARN.matcher(arn).matches()) {
            throw new InvalidDocumentException("anchor.kmsKeyArn", at + ".kmsKeyArn");
        }
        if (arns.putIfAbsent(arn, anchorId) != null) {
            // 鍵 version（KMS key 1つ）に anchor は1件。同じ鍵を2つの anchor（役割・tenant 違い）に分けない
            throw new InvalidDocumentException("anchor.kmsKeyArn.duplicate", at + ".kmsKeyArn");
        }
        if (!KEY_SPEC.equals(text(node, "keySpec", at))) {
            throw new InvalidDocumentException("anchor.keySpec", at + ".keySpec");
        }
        if (!SIGNING_ALGORITHM.equals(text(node, "signingAlgorithm", at))) {
            // ED25519_PH_SHA_512（Ed25519ph）は純粋な Ed25519 と互換が無く、verifier は検証しない
            throw new InvalidDocumentException("anchor.signingAlgorithm", at + ".signingAlgorithm");
        }
        PublicKey publicKey;
        try {
            publicKey = KmsEd25519PublicKeys.parse(text(node, "publicKey", at));
        } catch (IllegalArgumentException e) {
            throw new InvalidDocumentException("anchor.publicKey", at + ".publicKey");
        }
        if (!keys.add(publicKey)) {
            throw new InvalidDocumentException("anchor.publicKey.duplicate", at + ".publicKey");
        }
        RevocationStatus status = status(node.get("status"), at + ".status");
        Instant validFrom = instant(node, "validFrom", at);
        Instant validUntil = instant(node, "validUntil", at);
        if (!validUntil.isAfter(validFrom)) {
            throw new InvalidDocumentException("anchor.validity", at);
        }
        return new Common(authorityId, keyId, publicKey, status, validFrom, validUntil);
    }

    private static RevocationStatus status(JsonNode node, String at) {
        if (node == null || !node.isTextual()) {
            throw new InvalidDocumentException("status.invalid", at);
        }
        return switch (node.textValue()) {
            case "ACTIVE" -> RevocationStatus.ACTIVE;
            case "REVOKED" -> RevocationStatus.REVOKED;
            default -> throw new InvalidDocumentException("status.invalid", at);
        };
    }

    private static void requireFields(JsonNode node, Set<String> allowed, String at) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new InvalidDocumentException("document.field.unknown", at);
            }
        }
        for (String name : allowed) {
            if (!node.has(name) || node.get(name).isNull()) {
                throw new InvalidDocumentException("document.field.missing", at + "." + name);
            }
        }
    }

    private static String text(JsonNode node, String name, String at) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new InvalidDocumentException("document.field.type", at + "." + name);
        }
        return value.textValue();
    }

    private static Instant instant(JsonNode node, String name, String at) {
        try {
            return Instant.parse(text(node, name, at));
        } catch (DateTimeParseException e) {
            throw new InvalidDocumentException("document.instant", at + "." + name);
        }
    }

    private static JsonNode array(JsonNode node, String name, String at) {
        JsonNode value = node.get(name);
        if (value == null || !value.isArray()) {
            throw new InvalidDocumentException("document.field.type", at + "." + name);
        }
        return value;
    }

    private static JsonNode object(JsonNode node, String at) {
        if (node == null || !node.isObject()) {
            throw new InvalidDocumentException("document.field.type", at);
        }
        return node;
    }

    /** 範囲の項目（空でない配列、各要素は文字種内の文字列、重複なし）。 */
    private static Set<String> items(JsonNode node, String name, String at) {
        JsonNode values = array(node, name, at);
        if (values.isEmpty()) {
            throw new InvalidDocumentException("anchor." + name, at + "." + name);
        }
        Set<String> items = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !SCOPE_ITEM.matcher(value.textValue()).matches()
                    || !items.add(value.textValue())) {
                throw new InvalidDocumentException("anchor." + name, at + "." + name);
            }
        }
        return Collections.unmodifiableSet(items);
    }

    private static boolean validReference(String reference) {
        if (reference == null || reference.isEmpty() || reference.length() > MAX_REFERENCE_LENGTH
                || Character.isWhitespace(reference.charAt(0))
                || Character.isWhitespace(reference.charAt(reference.length() - 1))) {
            return false;
        }
        return reference.chars().noneMatch(Character::isISOControl);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    /** 文書を受け付けない理由（固定の code と、文書の中の位置。値は含めない）。 */
    static final class InvalidDocumentException extends RuntimeException {

        private final String code;

        InvalidDocumentException(String code, String location) {
            super(code + " at " + location);
            this.code = code;
        }

        /** 固定の code（監視の log 検索用）。 */
        String code() {
            return code;
        }
    }
}
