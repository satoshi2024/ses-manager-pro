package com.ses.config;

import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatisの1次cacheがstatement単位であること。
 *
 * <p>既定のSESSIONでは、同じtransaction内で同じ照会・同じ引数を再実行するとDBを読まずに前回の結果が返る。
 * lock取得前の読取りとlock取得後の読み直しが同じ照会のとき、lock待ちの間に他transactionがcommitした変更を
 * 見落とす（W3-16b の M6 `ResignationCompletionLockOrderMySqlTest` で5件が失敗した原因）。
 * lock文ごとの {@code flushCache} は書き忘れを防げないため、全体設定をここで固定する。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MyBatisLocalCacheScopeTest {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void 一次cacheはstatement単位でありtransaction内の再照会はDBを読む() {
        assertThat(sqlSessionFactory.getConfiguration().getLocalCacheScope())
                .as("mybatis-plus.configuration.local-cache-scope は statement にする（application.yml）")
                .isEqualTo(LocalCacheScope.STATEMENT);
    }

    @Test
    void 二次cacheは無効のまま() {
        assertThat(sqlSessionFactory.getConfiguration().isCacheEnabled()).isFalse();
    }
}
