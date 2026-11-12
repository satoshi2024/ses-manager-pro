package com.ses.config.feature;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 段階公開する機能（S-NF）と、その機能スイッチがOFFの時に遮断する入口の一覧。
 *
 * <p>ここは「権限」ではなく「公開可否」の台帳である。OFFの機能は管理者を含む全利用者・
 * 未認証利用者に対して404になり、サイドバーからも消える（認証済み利用者には存在しないURLと
 * 同じ404。未認証利用者は通常URLだとlogin誘導/401になるため、その差は残る）。
 * 入口を追加した場合は必ずここへ登録すること（{@code FeatureSwitchRouteCoverageTest} が
 * NF配下のcontroller・scheduler・sidebar項目の登録漏れと、NF外の入口の誤遮断を検出する）。
 *
 * <p>照合はURIのパス区切り単位で行う（{@code /lifecycle} は {@code /lifecycle/1} に一致し、
 * {@code /lifecycle-x} には一致しない）。完全一致パスは静的ファイル等の単体URL用で、
 * 大文字小文字と末尾の {@code /} を区別しない（Windows開発機のfile system classpath対策）。
 */
public enum Feature {

    /** NF-01 ライフサイクル管理・退社ゲート。 */
    NF01("ライフサイクル管理・退社ゲート",
            List.of("/lifecycle", "/my/lifecycle"),
            List.of("/api/lifecycle", "/api/my/lifecycle"),
            List.of(),
            List.of("lifecycle", "myLifecycle")),

    /** NF-02 カスタマーサクセス・サービスデスク（内部画面と顧客ポータルの問い合わせ）。 */
    NF02("カスタマーサクセス・サービスデスク",
            List.of("/service-desk", "/customer-success"),
            List.of("/api/service-desk", "/api/customer-success", "/api/portal/customer/service-desk"),
            List.of(),
            List.of("service-desk", "customer-success")),

    /** NF-03 資格・学習・スキルギャップ。 */
    NF03("資格・学習・スキルギャップ",
            List.of("/certification-learning-skill-gap", "/my/certification-learning-skill-gap"),
            List.of("/api/certification-learning-gap", "/api/my/certification-learning-gap"),
            List.of(),
            List.of("certification-learning-skill-gap", "myCertificationLearningGap")),

    /**
     * NF-04 要員PWAシェル（Service Worker登録・インストール用manifest）。
     *
     * <p>次は既存セルフサービス画面の日次入力queueそのもの（またはその復旧経路）なので、このスイッチの
     * 対象外とする。遮断すると日次入力が止まる、または未同期の下書きを復旧できずlogout時に失われる。
     * <ul>
     *   <li>{@code /api/my/pwa/{attendance,timesheet,expenses,change-requests}}、{@code /api/my/session-context}、
     *       {@code /js/pwa-queue.js}（通常の保存経路とuser scope bootstrap）</li>
     *   <li>{@code /offline.html} と {@code /api/my/pwa/restore/**}（ロック済み下書きの復元。パスワード再認証が必須）</li>
     * </ul>
     */
    NF04("要員PWAシェル（Service Worker・manifest）",
            List.of(),
            List.of(),
            List.of("/service-worker.js", "/manifest.webmanifest"),
            List.of()),

    /** NF-10 定期管理レポート。 */
    NF10("定期管理レポート",
            List.of("/management-reports"),
            List.of("/api/management-reports"),
            List.of(),
            List.of("management-report"));

    private final String displayName;
    private final List<String> pagePrefixes;
    private final List<String> apiPrefixes;
    private final List<String> exactPaths;
    private final List<String> menuKeys;

    Feature(String displayName, List<String> pagePrefixes, List<String> apiPrefixes,
            List<String> exactPaths, List<String> menuKeys) {
        this.displayName = displayName;
        this.pagePrefixes = pagePrefixes;
        this.apiPrefixes = apiPrefixes;
        this.exactPaths = exactPaths;
        this.menuKeys = menuKeys;
    }

    /** 設定キー・テンプレートで使う小文字の識別子（例: {@code nf01}）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 設定プロパティ名（例: {@code app.features.nf01.enabled}）。 */
    public String propertyName() {
        return "app.features." + key() + ".enabled";
    }

    /** 運用で上書きする環境変数名（例: {@code FEATURE_NF01_ENABLED}）。 */
    public String environmentVariable() {
        return "FEATURE_" + name() + "_ENABLED";
    }

    public String displayName() {
        return displayName;
    }

    public List<String> pagePrefixes() {
        return pagePrefixes;
    }

    public List<String> apiPrefixes() {
        return apiPrefixes;
    }

    public List<String> exactPaths() {
        return exactPaths;
    }

    public List<String> menuKeys() {
        return menuKeys;
    }

    /** このpathがこの機能の入口に該当するか（context path除去済みのpathを渡す）。 */
    public boolean matches(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (matchesExactPath(path)) {
            return true;
        }
        return pagePrefixes.stream().anyMatch(prefix -> matchesSegmentPrefix(path, prefix))
                || apiPrefixes.stream().anyMatch(prefix -> matchesSegmentPrefix(path, prefix));
    }

    /** 静的ファイルの完全一致。大文字小文字と末尾の {@code /} の表記ゆれも同じファイルとみなす。 */
    private boolean matchesExactPath(String path) {
        String candidate = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return exactPaths.stream().anyMatch(exact -> exact.equalsIgnoreCase(candidate));
    }

    /** pathが該当する機能。どの機能の入口でもなければ空。 */
    public static Optional<Feature> forPath(String path) {
        return Arrays.stream(values()).filter(feature -> feature.matches(path)).findFirst();
    }

    /** パス区切り単位の前方一致。{@code prefix} 自身と {@code prefix + "/..."} だけを一致とみなす。 */
    static boolean matchesSegmentPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
