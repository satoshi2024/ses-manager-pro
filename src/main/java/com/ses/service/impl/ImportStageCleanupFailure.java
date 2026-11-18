package com.ses.service.impl;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * 取込stage objectの補償削除に失敗したことを示す原因例外（NF-06 N6-2）。
 *
 * <p>外向きの例外（{@code error.import.storageCleanupFailed}の500）の原因として付ける。取込側
 * （{@code ImportCsvIngestionService}）はこの印を見つけたら照合台帳へ記録し、metricで通知し、
 * 照合batchで再試行する。運ぶのはstorage keyとoperation IDだけで、CSV本文・file名・利用者情報は含めない。
 */
public final class ImportStageCleanupFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final int MAX_VISITS = 64;

    private final String storageKey;
    private final String operationId;

    public ImportStageCleanupFailure(String storageKey, String operationId, Throwable cause) {
        super("取込stageの補償削除に失敗しました", cause);
        this.storageKey = storageKey;
        this.operationId = operationId;
    }

    public String storageKey() {
        return storageKey;
    }

    public String operationId() {
        return operationId;
    }

    /** 例外自身・suppressed・原因を有限回だけ辿り、補償削除失敗の印を探す。 */
    public static Optional<ImportStageCleanupFailure> find(Throwable failure) {
        if (failure == null) return Optional.empty();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        int visits = 0;
        while (!pending.isEmpty() && visits++ < MAX_VISITS) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof ImportStageCleanupFailure marker) return Optional.of(marker);
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
        }
        return Optional.empty();
    }
}
