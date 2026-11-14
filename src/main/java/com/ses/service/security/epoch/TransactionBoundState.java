package com.ses.service.security.epoch;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 現在の実transactionに1つだけ結び付く状態（例: そのtransactionで既に進めたepoch anchorの集合）を保持する。
 *
 * <p>{@link TransactionSynchronizationManager#bindResource} で結び付けた任意のresourceは、
 * {@code REQUIRES_NEW} 等で外側transactionがsuspendされても自動では外れない。そのため登録した
 * {@link TransactionSynchronization} の {@code suspend()/resume()} で結び付けを外し・戻し、内側transactionが
 * 外側の状態を共有しないようにする（外側の増分を内側が「済み」と誤認すると、内側だけcommitされた場合に増分が漏れる）。
 * transaction完了時（commit・rollbackとも）に結び付けを解く。</p>
 */
public final class TransactionBoundState {

    private TransactionBoundState() {
    }

    /**
     * 現在の実transactionに結び付いた状態を返す。無ければ作って結び付ける。
     *
     * @throws IllegalStateException 実transactionが無い、またはtransaction同期が無効な場合
     */
    public static <T> T current(Object key, Supplier<T> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        requireActualTransaction();
        @SuppressWarnings("unchecked")
        T existing = (T) TransactionSynchronizationManager.getResource(key);
        if (existing != null) {
            return existing;
        }
        T created = Objects.requireNonNull(factory.get(), "state");
        TransactionSynchronizationManager.bindResource(key, created);
        TransactionSynchronizationManager.registerSynchronization(new Binding(key, created));
        return created;
    }

    /** 呼出し時点で実transactionがあり、transaction同期が有効であることを要求する。 */
    public static void requireActualTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("実transactionの中で呼び出してください（Propagation.MANDATORY）");
        }
    }

    private record Binding(Object key, Object state) implements TransactionSynchronization {

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(key, state);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
        }
    }
}
