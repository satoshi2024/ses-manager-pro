package com.ses.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本番の起動時schema guard（配備決定P-3: migrationは専用Flyway jobだけが実行し、アプリinstanceは実行しない）。
 *
 * <p>本番は {@code spring.flyway.enabled=false} で起動時migrateを行わない。その代わりに、artifactが持つ
 * migration（{@code spring.flyway.locations}）とDBの {@code flyway_schema_history} を照合し、次のいずれかなら
 * 起動を中止する（理由はERROR logと起動失敗の例外messageに出す）。
 * <ul>
 *   <li>適用履歴が1件も無い（Flyway job未実行、または接続先DBの誤り）</li>
 *   <li>失敗したmigration（versioned / repeatable）が履歴に残っている</li>
 *   <li>artifactにあるversioned migrationが未適用（pending / ignored）</li>
 *   <li>artifactに無いversioned migrationが適用済み（DBがartifactより新しい、または別artifact）</li>
 *   <li>成功済みの最新versionがartifactの最新versioned migrationと一致しない</li>
 * </ul>
 *
 * <p>期待versionはコードへ固定せず、Flyway自身にartifactのmigrationを解決させて求める。
 * db/migration-prod にversioned migrationを置いた場合も同じ規則で照合される（test側の
 * {@code LatestMigrationVersion.LATEST} との一致は {@code ProductionSchemaVersionGuardTest} が固定する）。
 *
 * <p>placeholderの置換は行わない。初期管理者パスワードhashをアプリinstanceへ渡さないためで、
 * そのためplaceholderを含むRepeatableのchecksumはjobと一致しない。Repeatableは「失敗が無いこと」だけを確認する。
 *
 * <p>{@link SmartInitializingSingleton} で全singleton生成後・Tomcatのconnector開始とscheduler登録の前に
 * 実行する（失敗するとportを開かず、{@code @Scheduled} も1回も走らない）。読むだけでDBへは書かない。
 */
public class ProductionSchemaVersionGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionSchemaVersionGuard.class);

    /** 起動失敗messageに列挙する問題の上限（全件未適用のときにmessageが巨大にならないようにする）。 */
    static final int MAX_LISTED_PROBLEMS = 10;

    private final DataSource dataSource;
    private final List<String> locations;
    private final boolean outOfOrder;

    public ProductionSchemaVersionGuard(DataSource dataSource, List<String> locations, boolean outOfOrder) {
        this.dataSource = dataSource;
        this.locations = List.copyOf(locations);
        this.outOfOrder = outOfOrder;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    /** 照合して不一致なら {@link IllegalStateException} で起動を止める。一致すれば最新versionを返す。 */
    public String verify() {
        List<Entry> entries;
        try {
            entries = readMigrations();
        } catch (RuntimeException e) {
            String message = "[schema guard] flyway_schema_historyを確認できないため起動を中止します"
                    + "（DB接続・権限・locationsを確認してください）: " + e.getMessage();
            log.error(message, e);
            throw new IllegalStateException(message, e);
        }
        Result result = evaluate(entries);
        if (!result.ok()) {
            String message = result.message();
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("[schema guard] DBのschemaはartifactの最新migration V{} と一致しています", result.expectedVersion());
        return result.expectedVersion();
    }

    private List<Entry> readMigrations() {
        Flyway flyway = Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .outOfOrder(outOfOrder)
                .placeholderReplacement(false)
                .load();
        return Arrays.stream(flyway.info().all())
                .map(ProductionSchemaVersionGuard::toEntry)
                .toList();
    }

    private static Entry toEntry(MigrationInfo info) {
        return new Entry(info.getVersion() == null ? null : info.getVersion().getVersion(),
                info.getState(), info.getScript());
    }

    /**
     * Flywayの {@link MigrationInfo} を判定に必要な項目だけへ写したもの。
     *
     * @param version versioned migrationのversion（repeatableは {@code null}）
     */
    record Entry(String version, MigrationState state, String script) {

        String label() {
            return (version == null ? "R" : "V" + version) + "（" + script + ", " + state.getDisplayName() + "）";
        }
    }

    /** 判定結果。{@code problems} が空なら起動してよい。 */
    record Result(List<String> problems, String expectedVersion, String appliedVersion) {

        boolean ok() {
            return problems.isEmpty();
        }

        String message() {
            StringBuilder text = new StringBuilder("[schema guard] DBのschemaがartifactと一致しないため起動を中止します。")
                    .append("専用Flyway jobの実行結果を確認してください（.kiro/specs/production-launch/deploy-runbook.md）。")
                    .append(" artifactの最新versioned migration=").append(expectedVersion == null ? "なし" : "V" + expectedVersion)
                    .append(", DBの成功済み最新version=").append(appliedVersion == null ? "なし" : "V" + appliedVersion)
                    .append(", 問題").append(problems.size()).append("件: ");
            int listed = Math.min(problems.size(), MAX_LISTED_PROBLEMS);
            text.append(String.join(" / ", problems.subList(0, listed)));
            if (problems.size() > listed) {
                text.append(" / ほか").append(problems.size() - listed).append("件");
            }
            return text.toString();
        }
    }

    /** artifact側（解決済み）とDB側（適用済み）の状態から、起動してよいかを判定する。 */
    static Result evaluate(List<Entry> entries) {
        List<String> problems = new ArrayList<>();
        MigrationVersion expected = null;
        MigrationVersion applied = null;
        boolean anyApplied = false;
        for (Entry entry : entries) {
            MigrationState state = entry.state();
            if (state.isApplied()) {
                anyApplied = true;
            }
            if (state.isFailed()) {
                problems.add("失敗したmigrationが履歴に残っています: " + entry.label());
                continue;
            }
            if (entry.version() == null || state == MigrationState.DELETED) {
                // Repeatableは失敗だけを見る（placeholderを置換しないためchecksumはjobと一致しない）。
                continue;
            }
            switch (state) {
                case PENDING, IGNORED, ABOVE_TARGET ->
                        problems.add("artifactのmigrationが未適用です: " + entry.label());
                case MISSING_SUCCESS, FUTURE_SUCCESS ->
                        problems.add("artifactに無いmigrationが適用済みです（DBが別のartifactで更新されています）: "
                                + entry.label());
                default -> {
                    // SUCCESS / BASELINE / BELOW_BASELINE / OUT_OF_ORDER 等は整合している
                }
            }
            MigrationVersion version = MigrationVersion.fromVersion(entry.version());
            if (state.isResolved() && (expected == null || version.compareTo(expected) > 0)) {
                expected = version;
            }
            if (state.isApplied() && (applied == null || version.compareTo(applied) > 0)) {
                applied = version;
            }
        }
        if (!anyApplied) {
            problems.add(0, "flyway_schema_historyに適用履歴がありません（専用Flyway jobが未実行、または接続先DBの誤り）");
        }
        if (expected == null) {
            problems.add("artifactにversioned migrationがありません（locationsの設定を確認してください）");
        } else if (applied == null || applied.compareTo(expected) != 0) {
            problems.add("成功済みの最新version（" + (applied == null ? "なし" : "V" + applied.getVersion())
                    + "）がartifactの最新versioned migration（V" + expected.getVersion() + "）と一致しません");
        }
        return new Result(List.copyOf(problems),
                expected == null ? null : expected.getVersion(),
                applied == null ? null : applied.getVersion());
    }
}
