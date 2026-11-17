package com.ses.importcenter.metadata;

import com.ses.common.util.LogRedaction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/**
 * 現在の transaction が使う JDBC 接続の server 側 ID（MySQL の {@code CONNECTION_ID()}）を取得する（b-14。W2-NF06 review P3-1）。
 *
 * <p>取込 metadata の COMMIT が結果不明になったとき、照合（{@link ImportStageReconciler}）はこの ID の server thread が
 * まだ文を実行している間は判定しない。COMMIT が server 側で長く止まり（disk full・FTWRL 待ち等）、その間に client 側の
 * 接続だけが L4 LB に切られた場合でも、時間だけで未commitと決めて原本を消さないためである。
 *
 * <p>transaction に束縛された {@link ConnectionHolder} がちょうど1つで、DB が MySQL の場合だけ取得する。それ以外
 * （H2 等の開発・試験 DB、束縛が無い・複数ある）と取得の失敗は null を返し、照合は従来どおり時間だけで判定する。
 * 例外は投げない（取込 commit 自体を失敗させない）。取得は COMMIT の前に行う（COMMIT の失敗後は接続が使えないことがある）。
 */
final class ImportCommitConnectionIdentity {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportCommitConnectionIdentity.class);

    private ImportCommitConnectionIdentity() {
    }

    static Long currentTransactionConnectionId() {
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
                 ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                if (!result.next()) return null;
                long id = result.getLong(1);
                return result.wasNull() || id < 1 ? null : id;
            }
        } catch (Exception | LinkageError unavailable) {
            log.warn("[取込] COMMIT接続のIDを取得できません。結果不明時の照合は時間だけで判定します: exceptionType={}",
                    LogRedaction.exceptionType(unavailable));
            return null;
        }
    }
}
