package com.ses.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 本番の専用Flyway jobで、初期管理者パスワードhash（Flyway placeholder）を migrate 前に検査するcallback。
 *
 * <p>配備決定-3: 初期adminのパスワードは配備時にsecretから注入したBCrypt hashだけを使い、公開済みの
 * 既知パスワードを本番へ残さない。値は {@code db/migration-prod/R__update_admin_password_bcrypt.sql} が
 * placeholder {@value #PLACEHOLDER} として受け取る。
 *
 * <p>placeholderが無い場合はFlywayがmigrationの解決時（checksum計算時）に失敗させる。空・BCrypt以外・
 * 旧scriptの公開hashはFlywayには正しい値と区別できず、全versioned migrationの後でRepeatableが失敗するまで
 * 進んでしまうため、{@link Event#BEFORE_MIGRATE} で1件もmigrationを実行する前に止める。
 * 例外messageへ値は含めない（job logへhashを残さない）。
 *
 * <p>Spring beanにしてはならない。Spring BootのFlyway自動構成は全 {@link Callback} beanを登録するため、
 * beanにするとdev/testの起動時migrateまでplaceholderを要求してしまう。登録は {@code ops/flyway/flyway-prod.conf}
 * の {@code flyway.callbacks} にclass名で書く（jobと {@code FlywayProdFreshInstallMySqlTest} が同じ設定を読む）。
 */
public class ProductionAdminInitialPasswordFlywayCallback implements Callback {

    /** {@code R__update_admin_password_bcrypt.sql} が参照するplaceholder名（環境変数は大文字snake_case）。 */
    public static final String PLACEHOLDER = "admin_initial_password_hash";

    /** BCrypt（2a/2b/2y、cost 10〜31、salt+hash 53文字）。SQL側の検査と同じ規則。 */
    static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$(1[0-9]|2[0-9]|3[01])\\$[./A-Za-z0-9]{53}$");

    /** 旧版の {@code R__update_admin_password_bcrypt.sql} が書いていた {@code admin123} の公開BCrypt hash。 */
    static final String PUBLISHED_ADMIN123_HASH =
            "$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event != Event.BEFORE_MIGRATE) {
            return;
        }
        Map<String, String> placeholders = context.getConfiguration().getPlaceholders();
        validate(placeholders == null ? null : placeholders.get(PLACEHOLDER));
    }

    /** 不正なら値を含まないmessageで失敗させる。 */
    static void validate(String hash) {
        if (hash == null || hash.isBlank()) {
            throw new FlywayException("初期管理者パスワードhash（placeholder " + PLACEHOLDER
                    + "、環境変数 FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH）が未設定または空です。"
                    + "migrationは1件も実行していません");
        }
        if (!BCRYPT.matcher(hash).matches()) {
            throw new FlywayException("初期管理者パスワードhash（placeholder " + PLACEHOLDER
                    + "）がBCrypt形式（$2a$/$2b$/$2y$、cost 10以上）ではありません。平文パスワードを渡していないか"
                    + "確認してください（値はログへ出力しません）。migrationは1件も実行していません");
        }
        if (PUBLISHED_ADMIN123_HASH.equals(hash)) {
            throw new FlywayException("初期管理者パスワードhash（placeholder " + PLACEHOLDER
                    + "）に公開済みの admin123 のhashは使えません。migrationは1件も実行していません");
        }
    }

    /**
     * Flywayは同じeventのcallbackを {@code getCallbackName()} の昇順で実行する。
     * {@code LegacyDatabaseFlywayCallback}（補償DDLを行い得る）より先に検査するため、名前を A で始める。
     */
    @Override
    public String getCallbackName() {
        return CALLBACK_NAME;
    }

    /** {@link #getCallbackName()}。{@code "LegacyDatabaseFlywayCallback"} より辞書順で前に来ること。 */
    static final String CALLBACK_NAME = "AdminInitialPasswordHashCheck";
}
