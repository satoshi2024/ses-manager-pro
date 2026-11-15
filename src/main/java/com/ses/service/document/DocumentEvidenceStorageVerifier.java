package com.ses.service.document;

import com.ses.common.enums.FileKind;
import com.ses.common.util.LogRedaction;
import com.ses.service.storage.DocumentStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * 証跡版のstorage実体を開き、内容のSHA-256が版の {@code sha256} と一致するかを確かめる
 * （NF01細粒度epoch計画 §5.1 条件10、§5.4）。
 *
 * <p>lockを持ったままI/Oをしないため、DB transactionの外でだけ呼べる（transaction内の呼出しは
 * {@link IllegalStateException}）。CONFIRMのcoordinatorはdiscoveryで読んだ版の組を本classで検証し、attempt内では
 * 文書・版をS lockした状態でその組が変わっていないことを照合する。storage外部での改ざん・消失は検証時点でしか
 * 検出できない（RR-4）。読込量は初回sliceが受け付けるSKILL_SHEETの上限（{@link FileKind#SKILL_SHEET}）までに限る。</p>
 *
 * <p>logには文書ID・版IDと例外の型だけを出し、storage key・内容・hash値は出さない。</p>
 */
@Slf4j
@Component
public class DocumentEvidenceStorageVerifier {

    /** 検証の結果。 */
    public enum Outcome {
        /** 内容のSHA-256（と記録済みの場合は大きさ）が版と一致した。 */
        MATCH,
        /** 内容が版の記録と一致しない、または上限を超えた。 */
        MISMATCH,
        /** storageを開けない・読めない。 */
        UNREADABLE
    }

    private final DocumentStorage documentStorage;
    private final long maxBytes;

    @Autowired
    public DocumentEvidenceStorageVerifier(DocumentStorage documentStorage) {
        this(documentStorage, FileKind.SKILL_SHEET.getMaxBytes());
    }

    DocumentEvidenceStorageVerifier(DocumentStorage documentStorage, long maxBytes) {
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytesは正数で指定してください");
        }
        this.maxBytes = maxBytes;
    }

    public Outcome verify(DocumentEvidenceEligibility.EligibleVersion version) {
        Objects.requireNonNull(version, "version");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("storage検証はDB transactionの外で呼び出してください（lock保持中のI/O禁止）");
        }
        String expected = version.sha256() == null ? null : version.sha256().toLowerCase(Locale.ROOT);
        if (version.storageKey() == null || version.storageKey().isBlank()
                || expected == null || !expected.matches("[0-9a-f]{64}")) {
            return mismatch(version, "版の記録が不完全です");
        }
        long limit = version.sizeBytes() == null ? maxBytes : Math.min(version.sizeBytes(), maxBytes);
        try (InputStream input = documentStorage.open(version.storageKey())) {
            if (input == null) {
                return unreadable(version, null);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long count = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                count += read;
                if (count > limit) {
                    return mismatch(version, "内容が記録の大きさまたは上限を超えています");
                }
                digest.update(buffer, 0, read);
            }
            if (version.sizeBytes() != null && count != version.sizeBytes()) {
                return mismatch(version, "内容の大きさが記録と一致しません");
            }
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
                return mismatch(version, "内容のSHA-256が記録と一致しません");
            }
            return Outcome.MATCH;
        } catch (Exception failure) {
            return unreadable(version, failure);
        }
    }

    private static Outcome mismatch(DocumentEvidenceEligibility.EligibleVersion version, String reason) {
        log.error("[証跡storage検証] {}: documentId={} documentVersionId={}",
                reason, version.documentId(), version.documentVersionId());
        return Outcome.MISMATCH;
    }

    private static Outcome unreadable(DocumentEvidenceEligibility.EligibleVersion version, Exception failure) {
        log.error("[証跡storage検証] storageを読めません: documentId={} documentVersionId={} exceptionType={}",
                version.documentId(), version.documentVersionId(),
                failure == null ? "null-stream" : LogRedaction.exceptionType(failure));
        return Outcome.UNREADABLE;
    }
}
