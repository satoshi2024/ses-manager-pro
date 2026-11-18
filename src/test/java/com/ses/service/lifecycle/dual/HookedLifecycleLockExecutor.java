package com.ses.service.lifecycle.dual;

import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.LifecycleLockMapper;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.security.epoch.SharedLockSupport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M8・M9の試験用 {@link LifecycleLockExecutor}。lock取得の前後に試験のhookを差し込む（本class自身は試験ではない）。
 *
 * <p>{@code @SpyBean} で {@code Propagation.MANDATORY} のbeanを差し替えると、{@code doAnswer(..).when(field)} の
 * 呼出しがSpringのAOP proxyを通り、transactionの無いsetUpで {@code IllegalTransactionStateException} になる
 * （W3-21a Runner 4f3583d4）。そのためMockitoを使わず、本classを {@link Configuration} で {@code @Primary} として
 * 登録し、試行の中では元の {@link LifecycleLockExecutor#acquire} をそのまま実行する。transactionの宣言は
 * 親classのmethodから継承される。</p>
 */
public class HookedLifecycleLockExecutor extends LifecycleLockExecutor {

    /** lock取得の前後に呼ぶ処理（{@code attempt} は本beanの呼出し回数、1始まり）。 */
    public interface Hook {
        default void before(int attempt) throws Exception {
        }

        default void after(int attempt) throws Exception {
        }

        default void cleanup(int attempt) {
        }
    }

    /** {@code @Import} して使う試験用の設定（component scanの対象外）。 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean
        @Primary
        HookedLifecycleLockExecutor hookedLifecycleLockExecutor(LifecycleLockMapper lockMapper,
                                                                AuthorizationEpochMapper epochMapper,
                                                                SharedLockSupport sharedLockSupport) {
            return new HookedLifecycleLockExecutor(lockMapper, epochMapper, sharedLockSupport);
        }
    }

    private final AtomicReference<Hook> hook = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public HookedLifecycleLockExecutor(LifecycleLockMapper lockMapper, AuthorizationEpochMapper epochMapper,
                                       SharedLockSupport sharedLockSupport) {
        super(lockMapper, epochMapper, sharedLockSupport);
    }

    /** hookを設定し、呼出し回数を0に戻す（nullでhookを外す）。 */
    public void reset(Hook next) {
        calls.set(0);
        hook.set(next);
    }

    /** 前回の {@link #reset} 以降の呼出し回数（試行ごとに1回）。 */
    public int calls() {
        return calls.get();
    }

    @Override
    public void acquire(LifecycleLockPlan plan) {
        int attempt = calls.incrementAndGet();
        Hook current = hook.get();
        try {
            if (current != null) {
                current.before(attempt);
            }
            super.acquire(plan);
            if (current != null) {
                current.after(attempt);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception checked) {
            throw new IllegalStateException("試験hookが失敗しました", checked);
        } finally {
            if (current != null) {
                current.cleanup(attempt);
            }
        }
    }
}
