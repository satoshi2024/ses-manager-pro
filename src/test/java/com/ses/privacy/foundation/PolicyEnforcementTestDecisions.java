package com.ses.privacy.foundation;

/**
 * test 専用: 許可の判定（{@link PolicyEnforcementDecision}）を foundation の外の test から作る入口。
 *
 * <p>main では許可の判定を作れるのは foundation の本番の内部判定だけ（{@code PolicyEnforcementDecision.permit()} は
 * package-private。RB-NF08-LINK review P3-1）。本 class は test の source にだけあり、main の classpath には入らない。
 * テストclassではない（surefire対象外の命名）。
 */
public final class PolicyEnforcementTestDecisions {

    private PolicyEnforcementTestDecisions() {
    }

    /** test-only の stub port が返す許可。 */
    public static PolicyEnforcementDecision permit() {
        return PolicyEnforcementDecision.permit();
    }
}
