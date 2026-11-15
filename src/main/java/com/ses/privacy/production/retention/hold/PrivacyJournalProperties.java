package com.ses.privacy.production.retention.hold;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 独立 Journal インスタンスの設定契約（{@code privacy.journal.*}、journal-witness-grant-design.md §2.1）。
 *
 * <p>既定値は接続の枠（pool の大きさ・timeout・schema 名）だけで、接続先・principal・journal ID は持たない。
 * application.yml にも書かず、環境変数（例: {@code PRIVACY_JOURNAL_DATASOURCE_URL}・
 * {@code PRIVACY_JOURNAL_DATASOURCE_USERNAME}・{@code PRIVACY_JOURNAL_DATASOURCE_PASSWORD}・
 * {@code PRIVACY_JOURNAL_JOURNALID}）で与える。password は secret store から注入し、log・toString に出さない。
 * 値の検査は {@link PrivacyJournalConnections#contractViolations} が行う（不適合なら pool を作らない）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "privacy.journal")
public class PrivacyJournalProperties {

    /** pool の上限の既定値（1〜8。application DB の pool とは別枠）。 */
    public static final int DEFAULT_MAX_POOL_SIZE = 4;

    /** 接続待ちの既定値（上限 {@code PT30S}）。 */
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    /** 接続先 schema 名の既定値。 */
    public static final String DEFAULT_EXPECTED_SCHEMA = "privacy_journal";

    private Datasource datasource = new Datasource();

    /** 接続先の {@code DATABASE()} と一致しなければならない schema 名。 */
    private String expectedSchema = DEFAULT_EXPECTED_SCHEMA;

    /** journal 識別子（{@code [A-Za-z0-9._-]{4,64}}）。{@code t_privacy_projection_state.journal_id} と一致すること。 */
    private String journalId;

    /**
     * reconciler が「Journal に command が無い」pending を消してよいまでの時間（運用で決定。既定は無し）。
     * 設定する場合は append の transaction 上限より長いこと。未設定なら reconciler は pending を消さない（A1H）。
     */
    private Duration pendingTimeout;

    /** {@code privacy.journal.datasource.*}。Spring の {@code DataSource} 型 Bean にはしない。 */
    @Getter
    @Setter
    public static class Datasource {

        /** {@code jdbc:mysql://} だけ。{@code sslMode=VERIFY_IDENTITY} 必須、透過再接続と時区の上書きは不可。 */
        private String url;

        private String username;

        private String password;

        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;

        private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

        /** 接続文字列・principal・password を出さない（設定の有無だけ）。 */
        @Override
        public String toString() {
            return "Datasource{url=" + (url == null ? "unset" : "set") + ", username="
                    + (username == null ? "unset" : "set") + ", password=" + (password == null ? "unset" : "set")
                    + ", maxPoolSize=" + maxPoolSize + ", connectionTimeout=" + connectionTimeout + "}";
        }
    }

    @Override
    public String toString() {
        return "PrivacyJournalProperties{datasource=" + datasource + ", expectedSchema=" + expectedSchema
                + ", journalId=" + (journalId == null ? "unset" : "set") + ", pendingTimeout=" + pendingTimeout + "}";
    }
}
