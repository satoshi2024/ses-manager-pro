package com.ses.service.security.epoch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 接続先DBが共有lock（{@code FOR SHARE}）を解釈するかを判定する（NF01細粒度epoch計画 §2.5）。
 *
 * <p>MySQLは {@code FOR SHARE} を解釈するが、H2は解釈しない。H2では同じ行・同じrankの {@code FOR UPDATE} で代える。
 * 排他lockは共有lockの上位互換でlock順も同じなので、fast testで順序と読み直しの振舞いを確かめられ、共有lockの意味
 * （並行するreader・writerを待たせないこと）は実MySQLのM系試験で確かめる。DB製品を判定できない場合も排他lockにする
 * （待ちが増えるだけで、失効漏れにはならない側）。判定は初回に1度だけ行い、判定できなかった場合は結果を保持しない。</p>
 *
 * <p>利用者行（{@link ActorShareLock}）、組織行（{@link OrganizationShareLock}）、lock plan の実行
 * （{@code LifecycleLockExecutor}）が同じ判定を共有する。</p>
 */
@Slf4j
@Component
public class SharedLockSupport {

    private final DataSource dataSource;

    /** 接続先DBが {@code FOR SHARE} を解釈するか（初回に1度だけ判定する）。 */
    private volatile Boolean supported;

    @Autowired
    public SharedLockSupport(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private SharedLockSupport(boolean fixed) {
        this.dataSource = null;
        this.supported = fixed;
    }

    /** DBに接続しない単体試験用の固定値。 */
    public static SharedLockSupport fixed(boolean sharedLockSupported) {
        return new SharedLockSupport(sharedLockSupported);
    }

    /** 接続先DBが {@code FOR SHARE} を解釈するか。判定できない場合は {@code false}（排他lockで代える）。 */
    public boolean supported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        if (dataSource == null) {
            return false;
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            boolean result = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
            supported = result;
            return result;
        } catch (SQLException e) {
            log.warn("[authorization epoch] DB製品を判定できないため共有lockを排他lockで代えます: {}", e.getMessage());
            return false;
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
