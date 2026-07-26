-- ShedLock ロックテーブル（V58 のH2版）。
-- テストは Flyway 無効のため、@SpringBootTest でバッチが発火してもロック取得に失敗しないよう用意する。
DROP TABLE IF EXISTS shedlock;
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at  TIMESTAMP(3) NOT NULL,
  locked_by  VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
);
