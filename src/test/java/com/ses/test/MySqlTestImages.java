package com.ses.test;

import java.util.function.UnaryOperator;

/**
 * 実MySQL test（Testcontainers）が起動するMySQL imageの唯一の定義。
 *
 * <p>本番DBは MySQL 8.4 LTS とする（MySQL 8.0 のcommunity supportは2026-04-30で終了）。既定値は本番と同じ系列の
 * 固定tag {@value #DEFAULT_IMAGE} で、CIは何も指定せずに既定値で実行する。浮動tag（{@code 8.4}・{@code lts}）は
 * 使わない。実行ごとに版が変わると、MySQL側の挙動変化とアプリ側の変更を切り分けられなくなるため。
 *
 * <p>8.4のimageを持たない端末（8.0系だけを保持するローカルRunnerなど）は、次の優先順で上書きできる。
 * <ol>
 *   <li>system property {@value #IMAGE_PROPERTY}（例: {@code -Dses.test.mysql.image=mysql:8.0.36}。
 *       Maven Surefire はコマンドラインの {@code -D} をforkしたtest JVMへ引き継ぐ）</li>
 *   <li>環境変数 {@value #IMAGE_ENV}（{@code scripts/verify-like-ci.*} のように {@code -D} を足せない経路向け）</li>
 *   <li>既定値 {@value #DEFAULT_IMAGE}</li>
 * </ol>
 * 空文字・空白だけの値は未指定として扱う（{@code -Dses.test.mysql.image=} のような空展開で起動失敗させないため）。
 *
 * <p>本番fresh-install test（{@code FlywayProdFreshInstallMySqlTest}）だけは、従来の
 * {@value #FRESH_INSTALL_IMAGE_PROPERTY} を最優先の別名として受け付ける（{@link #freshInstallImage()}）。
 * deploy runbookが「本番の版で検証する」手順としてこのpropertyを記載しているため。指定が無ければ
 * {@link #image()} と同じ解決になる。
 *
 * <p>8.0系へ戻して得た結果は8.0での回帰確認にすぎず、8.4上での証拠にはならない。8.4の証拠はCIの既定値runで取る。
 *
 * <p>{@code src/test/java} にimage名のliteralを直接書かないこと（{@code MySqlTestImagesTest} が検出する）。
 */
public final class MySqlTestImages {

    /** test全体のMySQL imageを上書きするsystem property。 */
    public static final String IMAGE_PROPERTY = "ses.test.mysql.image";

    /** {@link #IMAGE_PROPERTY} が無いときに参照する環境変数。 */
    public static final String IMAGE_ENV = "SES_TEST_MYSQL_IMAGE";

    /** 本番fresh-install testだけが参照する従来のsystem property（最優先の別名）。 */
    public static final String FRESH_INSTALL_IMAGE_PROPERTY = "ses.flyway.fresh-install.mysql-image";

    /** 既定image。本番と同じ MySQL 8.4 LTS の固定tag。 */
    public static final String DEFAULT_IMAGE = "mysql:8.4.13";

    private MySqlTestImages() {
    }

    /** 実MySQL testが使うimage名を返す。 */
    public static String image() {
        return resolve(System::getProperty, System::getenv);
    }

    /** 本番fresh-install testが使うimage名を返す（{@value #FRESH_INSTALL_IMAGE_PROPERTY} が最優先）。 */
    public static String freshInstallImage() {
        return resolveFreshInstall(System::getProperty, System::getenv);
    }

    static String resolve(UnaryOperator<String> properties, UnaryOperator<String> environment) {
        String fromProperty = trimToNull(properties.apply(IMAGE_PROPERTY));
        if (fromProperty != null) {
            return fromProperty;
        }
        String fromEnvironment = trimToNull(environment.apply(IMAGE_ENV));
        return fromEnvironment != null ? fromEnvironment : DEFAULT_IMAGE;
    }

    static String resolveFreshInstall(UnaryOperator<String> properties, UnaryOperator<String> environment) {
        String alias = trimToNull(properties.apply(FRESH_INSTALL_IMAGE_PROPERTY));
        return alias != null ? alias : resolve(properties, environment);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
