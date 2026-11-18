-- ProductionSchemaVersionGuardTest 用。artifactだけが持ち、一度も適用されていないRepeatable（H2）。
-- 本番のmigrationではない。guardは本scriptの成功適用が履歴に無いことを検出して起動を止める。
CREATE TABLE IF NOT EXISTS guard_never_applied (id INT PRIMARY KEY);
