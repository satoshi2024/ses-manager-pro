package com.ses.service.lifecycle.evidence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 将来のlifecycle evidence facadeで使う内部immutable値とcanonical hash。
 * 文書metadataを含む値はserver側DB/storage読取から作成し、client指定hash、storage key、
 * actor、型なしMapを認可根拠にしない。
 */
public final class LifecycleCanonicalization {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private LifecycleCanonicalization() {
    }

    public enum Operation {
        SUBMIT,
        CONFIRM
    }

    /** clientが選べる識別子のみを保持する。他の文書事実はserver側で解決する。 */
    public record DocumentSelection(long documentId, long documentVersionId) {
        public DocumentSelection {
            if (documentId <= 0 || documentVersionId <= 0) {
                throw new IllegalArgumentException("文書IDと文書版IDは正数で指定してください");
            }
        }
    }

    /** server側で解決・検証した版の不変事実。 */
    public record DocumentVersionSnapshot(
            long documentId,
            long documentVersionId,
            long versionNo,
            String sha256,
            long evidenceEligibilityEpoch) {
        public DocumentVersionSnapshot {
            if (documentId <= 0 || documentVersionId <= 0 || versionNo <= 0 || evidenceEligibilityEpoch < 0) {
                throw new IllegalArgumentException("文書証跡の識別子・版番号・世代が不正です");
            }
            Objects.requireNonNull(sha256, "sha256");
            String normalizedHash = sha256.toLowerCase(Locale.ROOT);
            if (!normalizedHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("文書SHA-256は64桁のhex値で指定してください");
            }
            sha256 = normalizedHash;
        }
    }

    /**
     * canonical evidenceはserver検証済みのexact versionを保持し、client metadataを保持しない。
     * 複数版の曖昧さを防ぐため、同一documentは一度だけ含める。
     */
    public record EvidenceSnapshot(String tenantId, List<DocumentVersionSnapshot> documents) {
        public EvidenceSnapshot {
            requireText(tenantId, "tenantId");
            Objects.requireNonNull(documents, "documents");
            List<DocumentVersionSnapshot> sorted = documents.stream()
                    .map(document -> Objects.requireNonNull(document, "document"))
                    .sorted(Comparator.comparingLong(DocumentVersionSnapshot::documentId)
                            .thenComparingLong(DocumentVersionSnapshot::documentVersionId))
                    .toList();
            Set<Long> documentIds = new HashSet<>();
            for (DocumentVersionSnapshot document : sorted) {
                if (!documentIds.add(document.documentId())) {
                    throw new IllegalArgumentException("同じ文書の複数版を証跡に指定できません");
                }
            }
            documents = List.copyOf(sorted);
        }

        public String canonicalJson() {
            return writeJson(generator -> {
                generator.writeStartObject();
                generator.writeStringField("tenantId", tenantId);
                generator.writeArrayFieldStart("documents");
                for (DocumentVersionSnapshot document : documents) {
                    generator.writeStartObject();
                    generator.writeNumberField("documentId", document.documentId());
                    generator.writeNumberField("documentVersionId", document.documentVersionId());
                    generator.writeNumberField("versionNo", document.versionNo());
                    generator.writeStringField("sha256", document.sha256());
                    generator.writeNumberField("evidenceEligibilityEpoch", document.evidenceEligibilityEpoch());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
                generator.writeEndObject();
            });
        }

        public String sha256() {
            return hash(canonicalJson());
        }
    }

    /**
     * 業務操作のsemantic input。server採番submission ID/revisionを含めず、同じclient意図の
     * retryで同一request hashを得る。actor IDはbody値でなく、呼出側が認証済み主体から渡す。
     */
    public record Request(
            String tenantId,
            long taskId,
            long taskGeneration,
            Operation operation,
            long actorUserId,
            String idempotencyKey,
            String businessComment,
            List<DocumentSelection> documentSelections) {
        public Request {
            requireText(tenantId, "tenantId");
            if (taskId <= 0 || taskGeneration < 0 || actorUserId <= 0) {
                throw new IllegalArgumentException("操作対象またはactor識別子が不正です");
            }
            Objects.requireNonNull(operation, "operation");
            requireText(idempotencyKey, "idempotencyKey");
            businessComment = businessComment == null ? "" : businessComment;
            Objects.requireNonNull(documentSelections, "documentSelections");
            List<DocumentSelection> sorted = documentSelections.stream()
                    .map(selection -> Objects.requireNonNull(selection, "documentSelection"))
                    .sorted(Comparator.comparingLong(DocumentSelection::documentId)
                            .thenComparingLong(DocumentSelection::documentVersionId))
                    .toList();
            Set<Long> documentIds = new HashSet<>();
            for (DocumentSelection selection : sorted) {
                if (!documentIds.add(selection.documentId())) {
                    throw new IllegalArgumentException("同じ文書の複数版を選択できません");
                }
            }
            documentSelections = List.copyOf(sorted);
        }

        public String canonicalJson() {
            return writeJson(generator -> {
                generator.writeStartObject();
                generator.writeStringField("tenantId", tenantId);
                generator.writeNumberField("taskId", taskId);
                generator.writeNumberField("taskGeneration", taskGeneration);
                generator.writeStringField("operation", operation.name());
                generator.writeNumberField("actorUserId", actorUserId);
                generator.writeStringField("idempotencyKey", idempotencyKey);
                generator.writeStringField("businessComment", businessComment);
                generator.writeArrayFieldStart("documentSelections");
                for (DocumentSelection selection : documentSelections) {
                    generator.writeStartObject();
                    generator.writeNumberField("documentId", selection.documentId());
                    generator.writeNumberField("documentVersionId", selection.documentVersionId());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
                generator.writeEndObject();
            });
        }

        public String sha256() {
            return hash(canonicalJson());
        }
    }

    /** confirmationは永続化済みsubmission identityとevidence hashへ別途bindする。 */
    public record ConfirmationBinding(long submissionId, long submissionRevision, String evidenceSha256) {
        public ConfirmationBinding {
            if (submissionId <= 0 || submissionRevision <= 0) {
                throw new IllegalArgumentException("submission IDとrevisionは正数で指定してください");
            }
            Objects.requireNonNull(evidenceSha256, "evidenceSha256");
            evidenceSha256 = evidenceSha256.toLowerCase(Locale.ROOT);
            if (!evidenceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("証跡SHA-256は64桁のhex値で指定してください");
            }
        }

        public String canonicalJson() {
            return writeJson(generator -> {
                generator.writeStartObject();
                generator.writeNumberField("submissionId", submissionId);
                generator.writeNumberField("submissionRevision", submissionRevision);
                generator.writeStringField("evidenceSha256", evidenceSha256);
                generator.writeEndObject();
            });
        }

        public String sha256() {
            return hash(canonicalJson());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "は必須です");
        }
    }

    private static String hash(String canonicalJson) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String writeJson(JsonWriter writer) {
        try {
            StringWriter output = new StringWriter();
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
                writer.write(generator);
            }
            return output.toString();
        } catch (IOException impossible) {
            throw new IllegalStateException("Canonical JSON generation failed", impossible);
        }
    }

    @FunctionalInterface
    private interface JsonWriter {
        void write(JsonGenerator generator) throws IOException;
    }
}
