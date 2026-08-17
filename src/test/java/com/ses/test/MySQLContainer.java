package com.ses.test;

import java.util.Map;

/**
 * テスト専用のMySQL container。
 *
 * <p>破棄前提のDBをtmpfsへ置き、durability目的の同期書き込みを抑止する。SQL方言・制約・
 * transaction semanticsはMySQL 8のまま維持し、Docker Desktopのoverlayfs I/Oを削減する。
 */
public class MySQLContainer<SELF extends MySQLContainer<SELF>>
        extends org.testcontainers.containers.MySQLContainer<SELF> {

    public MySQLContainer(String dockerImageName) {
        super(dockerImageName);
        withTmpFs(Map.of("/var/lib/mysql", "rw"));
        withCommand(
                "--innodb-flush-log-at-trx-commit=2",
                "--sync-binlog=0",
                "--innodb-doublewrite=OFF",
                "--skip-log-bin");
    }
}
