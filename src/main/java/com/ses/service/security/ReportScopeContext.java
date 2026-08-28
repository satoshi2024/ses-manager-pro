package com.ses.service.security;

import com.ses.dto.report.ReportScopeSnapshot;

import java.util.function.Supplier;

/**
 * schedulerが保存済みscopeを正本serviceへ伝えるための短命な実行コンテキスト。
 * HTTP sessionや認証principalへscopeを再解決させず、finallyで必ず破棄する。
 */
public final class ReportScopeContext {

    private static final ThreadLocal<ReportScopeSnapshot> CURRENT = new ThreadLocal<>();

    private ReportScopeContext() {
    }

    public static ReportScopeSnapshot current() {
        return CURRENT.get();
    }

    public static <T> T with(ReportScopeSnapshot scope, Supplier<T> action) {
        ReportScopeSnapshot previous = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
