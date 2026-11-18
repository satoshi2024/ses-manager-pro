package com.ses.importcenter.metadata;

import com.ses.common.util.LogRedaction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 現在の transaction が使う JDBC 接続の server 側 ID（MySQL の {@code CONNECTION_ID()}）と、その server の識別
 * （{@code @@server_uuid}）を取得する（b-14。W2-NF06 review P3-1、RB-NF05 review P3-3）。
 *
 * <p>取込 metadata の COMMIT が結果不明になったとき、照合（{@link ImportStageReconciler}）はこの ID の server thread が
 * まだ文を実行している間は判定しない。COMMIT が server 側で長く止まり（disk full・FTWRL 待ち等）、その間に client 側の
 * 接続だけが L4 LB に切られた場合でも、時間だけで未commitと決めて原本を消さないためである。接続 ID は server の再起動・
 * failover の後に別の接続へ振り直されるため、照合は server の識別と起動時刻も見て、記録後に別の server になった・再起動した
 * 場合はその ID を使わない。
 *
 * <p>transaction に束縛された {@link ConnectionHolder} がちょうど1つで、DB が MySQL の場合だけ取得する。それ以外
 * （H2 等の開発・試験 DB、束縛が無い・複数ある）と取得の失敗は null を返し、照合は従来どおり時間だけで判定する。
 * 例外は投げない（取込 commit 自体を失敗させない）。取得は COMMIT の前に行う（COMMIT の失敗後は接続が使えないことがある）。
 */
final class ImportCommitConnectionIdentity {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportCommitConnectionIdentity.class);
    /** {@code @@server_uuid} の形式。形式外の値は記録しない。 */
    static final Pattern SERVER_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** COMMIT を送った接続。{@code serverUuid} は取得できない・形式外なら null（小文字に揃える）。 */
    record CommitConnection(long connectionId, String serverUuid) {
        CommitConnection {
            if (connectionId < 1) throw new IllegalArgumentException("connectionId");
            serverUuid = serverUuid == null || !SERVER_UUID.matcher(serverUuid).matches()
                    ? null : serverUuid.toLowerCase(Locale.ROOT);
        }
    }

    private ImportCommitConnectionIdentity() {
    }

    static CommitConnection currentTransactionConnection() {
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) return null;
            ConnectionHolder holder = null;
            for (Object resource : TransactionSynchronizationManager.getResourceMap().values()) {
                if (resource instanceof ConnectionHolder candidate) {
                    if (holder != null) return null;
                    holder = candidate;
                }
            }
            if (holder == null) return null;
            Connection connection = holder.getConnection();
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) return null;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT CONNECTION_ID(), @@server_uuid")) {
                if (!result.next()) return null;
                long id = result.getLong(1);
                if (result.wasNull() || id < 1) return null;
                return new CommitConnection(id, result.getString(2));
            }
        } catch (Exception | LinkageError unavailable) {
            log.warn("[取込] COMMIT接続のIDを取得できません。結果不明時の照合は時間だけで判定します: exceptionType={}",
                    LogRedaction.exceptionType(unavailable));
            return null;
        }
    }
}
