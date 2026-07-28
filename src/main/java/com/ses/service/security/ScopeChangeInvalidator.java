package com.ses.service.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 可視範囲（組織scope / DataScope）に影響する更新のコミット後に
 * {@link ScopeVersionRegistry} を進める共通処理。
 *
 * <p>各Serviceが同じ {@code TransactionSynchronization} 登録を個別に書くと、対応漏れが起きやすい
 * （第十四次Review P1-3: 要員↔担当営業の割当・解除、契約の担当営業変更、要員アカウント連携の
 * 追加・解除、要員の所属組織変更、ロール変更のいずれも {@link ScopeVersionRegistry} を進めておらず、
 * これらの変更後もDashboardキャッシュのTTLが切れるまで旧scopeの母集団が返っていた）。
 * 可視範囲（{@link DataScopeService#compute*Ids()}が返す集合）を変える可能性のある更新は、
 * 個別にコードを書かずこのクラスの {@link #invalidate()} を呼ぶ。
 *
 * <p>コミット後に世代を進めるのは、ロールバックした変更でキャッシュを捨てる必要がなく、
 * またコミット前に進めると、まだ見えないはずの新scopeでキャッシュが作られてしまうため。
 */
@Component
public class ScopeChangeInvalidator {

    private final ScopeVersionRegistry scopeVersionRegistry;

    public ScopeChangeInvalidator(ScopeVersionRegistry scopeVersionRegistry) {
        this.scopeVersionRegistry = scopeVersionRegistry;
    }

    /** 可視範囲に影響する更新が確定したときに呼ぶ。トランザクション外なら即時反映する。 */
    public void invalidate() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scopeVersionRegistry.bump();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scopeVersionRegistry.bump();
            }
        });
    }
}
