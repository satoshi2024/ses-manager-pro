# 配備前の DBA 確認の草案（DBA-1〜DBA-4 の確認 SQL と DBA-6 の `CONTENT_HASH_TABLES`）

- 状態: **草案（DBA の確認前）。値は DBA が確定する**。本書の候補・期待値は統括が repository の記述から起こしたもので、DBA の確認・署名を経ていない（**未取得**）。
- 位置づけ: `deploy-runbook.md` §1.7 の DBA-1〜DBA-6 の作業用の下書きである（2026-11-22 利用者決定: 統括が先に起草し、DBA の確認の後に定稿にする）。手順と根拠の正本は runbook の各節・`ops/backup/README.md`・`ops/backup/runbooks/restore-cutover.md` で、本書は正文を写さずに節を引く。runbook に SQL が無い確認だけを本書で補う。
- `<migrator>`・`<アプリaccount>`・`<backup account>`・`<restore target role>`・`<host>` は実際の account に置き換える。password・hash 等の値は本書にも配備記録にも書かない（runbook §1.8）。

## 1. DBA-6: `CONTENT_HASH_TABLES` の候補（`.env.backup`）

### 1.1 満たすこと（正本の要約）

- 主キーを持つ表だけを指定できる。主キーの無い表を書くと backup が失敗する（`ops/backup/README.md`「設定」、`lib/content-hash.sh`）。表名・列名は `^[A-Za-z0-9_]{1,64}$` に限る。
- 内容ハッシュは full・checkpoint の書込み静止区間の中で計算するため、少数の表に絞る。`flyway_schema_history` を含めることを推奨する（README「設定」）。静止区間の見積りは内容ハッシュで1〜5秒（`backup-write-quiesce/design.md` §10.1）、checkpoint の最大保持は300秒（U-1）。
- 指定した表を1つでも計算できなければ、その backup は VALID にならない。復元検証は同じ値を参照し、記録が無い表があれば FAIL にする（README「復元」の `validate-restore.sh` の項）。
- production では `CONTENT_HASH_TABLES` を設定して backup と復元検証を行い、`checks.content_hash` の PASS が cutover の前提になる（`restore-cutover.md` §1）。
- 書込み静止の信号表（`sys_backup_quiesce_` で始まる3表）は書けない。書くと backup が失敗する（強制の除外。静止区間の中でも heartbeat・観測・lease の延長で書かれ、復元検証が必ず FAIL になるため。`backup-write-quiesce/design.md` §3.7）。
- 記録するのは表ごとの SHA-256（と並びの主キー・算出方式）だけで、行の値は backup の metadata に入らない（`lib/content-hash.sh`）。

### 1.2 選び方（統括の案。決定は DBA）

1. 主キーがある（1.1）。
2. 静止区間の中で書く者がいない。app の書込みは静止の間 503 と読取り専用の接続で止まる（`backup-write-quiesce/design.md` §6）。app 以外の writer は baseline の PROD-002 で確かめる（`deploy-runbook.md` §1.7「Backup 開始の前提」）。
3. 復元の誤りが致命的な表を選ぶ: schema の版、認証・認可、NF07 の hold（初回リリースで ON。2026-11-22 利用者決定）、金額。
4. 行が少なく、伸びが遅い表を選ぶ。行が多く伸び続ける表（監査 `t_audit_log`・通知 `t_notification`・session `t_user_session`・作業記録 `t_work_record` 等）は静止区間を延ばすため選ばない。scheduler の lock の表 `shedlock` は業務の値を持たないため選ばない。

### 1.3 候補

| 候補 | 値（`.env.backup` にそのまま書く形） | 表ごとの理由 | 利点 | 欠点 |
|---|---|---|---|---|
| A（最小） | `CONTENT_HASH_TABLES=flyway_schema_history,sys_user,m_system_config` | `flyway_schema_history`（主キー `installed_rank`。README の推奨。schema の版と各 migration の checksum の行。復元検証の `flyway_max_success` の照合は最大の版だけを見るため、途中の行の違いはここで分かる）。`sys_user`（主キー `id`。認証の起点で、break-glass・OIDC 管理者・privacy-admin の判定の元。RR-13 の anchor 表の1つ）。`m_system_config`（主キー `config_key`。初回 bootstrap の完了 marker `security.bootstrap.completed-at`（失うと承認 ticket 無しで bootstrap を再開できる。`deploy-runbook.md` §5.5）、NF05 outbound の状態 `integration.hub.outbound.runtime.v1`、手数料の既定等） | 静止区間がほとんど延びない。3表とも小さい | 認可（権限 group と deny 行）・hold・金額の表の内容の違いは件数（`CRITICAL_TABLES`）と schema fingerprint でしか分からない |
| B（推奨） | `CONTENT_HASH_TABLES=flyway_schema_history,sys_user,m_system_config,m_permission_group,t_permission_group_action,t_user_permission_group,t_legal_hold,t_contract,t_invoice` | A の3表に加えて、`m_permission_group`・`t_permission_group_action`・`t_user_permission_group`（主キー `id`。baseline＋deny の権限（V66・V66_1）と privacy-admin の割当。deny 行が1つ欠けた復元は権限を黙って広げる。group と action の数に比例して小さい）、`t_legal_hold`（主キー `id`。NF07 の hold。初回リリースで ON。行が欠けた復元は hold の対象を守らない。Journal は別 instance で restore の対象外（`deploy-runbook.md` §3.6））、`t_contract`・`t_invoice`（主キー `id`。契約と請求の金額。行は契約数と月次の請求に比例する） | 認可・hold・金額の復元の誤りを cutover の前に止められる | 9表になり、`t_contract`・`t_invoice` の行数に比例して静止区間が延びる（staging で測る）。表が増えるほど、表の構成を変える release ごとに値の見直しが要る |

- 追加の候補（DBA が判断する）: `m_identity_provider`・`t_user_external_identity`（OIDC の provider と外部 identity の link。起動時検査の「OIDCでログインできる管理者」の判定に使う）。
- どちらの候補でも、表を足す・消す migration を入れた release では値を見直す（`FLYWAY_VERSION` と同じく、release ごとに §3 の後で確かめる）。

### 1.4 DBA の確認 SQL（§3.4 の後、本番の DB で。PROCESS は要らない）

候補 B の表で書く。候補 A なら `IN` の中を3表にする。

```sql
-- (1) 表があり、InnoDB で、主キーがあること。規模（table_rows は推定値）
SELECT t.table_name, t.engine, t.table_rows AS approx_rows,
       ROUND((t.data_length + t.index_length) / 1024 / 1024, 1) AS size_mb,
       (SELECT GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position)
          FROM information_schema.key_column_usage k
         WHERE k.table_schema = t.table_schema AND k.table_name = t.table_name
           AND k.constraint_name = 'PRIMARY') AS pk_columns
  FROM information_schema.tables t
 WHERE t.table_schema = 'ses_manager_db'
   AND t.table_name IN ('flyway_schema_history', 'sys_user', 'm_system_config',
                        'm_permission_group', 't_permission_group_action', 't_user_permission_group',
                        't_legal_hold', 't_contract', 't_invoice')
 ORDER BY t.table_name;
-- 期待: 候補の表の数だけ行がある（B は9行、A は3行）。engine は全て InnoDB。
--       pk_columns は flyway_schema_history が installed_rank、m_system_config が config_key、他は id。

-- (2) 正確な行数（table_rows は統計の推定値のため）
SELECT 'flyway_schema_history' AS table_name, COUNT(*) AS row_count FROM flyway_schema_history
UNION ALL SELECT 'sys_user', COUNT(*) FROM sys_user
UNION ALL SELECT 'm_system_config', COUNT(*) FROM m_system_config
UNION ALL SELECT 'm_permission_group', COUNT(*) FROM m_permission_group
UNION ALL SELECT 't_permission_group_action', COUNT(*) FROM t_permission_group_action
UNION ALL SELECT 't_user_permission_group', COUNT(*) FROM t_user_permission_group
UNION ALL SELECT 't_legal_hold', COUNT(*) FROM t_legal_hold
UNION ALL SELECT 't_contract', COUNT(*) FROM t_contract
UNION ALL SELECT 't_invoice', COUNT(*) FROM t_invoice;
-- 期待: 新規導入の直後は flyway_schema_history が §3.4 の適用件数と Repeatable の行の合計、業務の表は seed の件数だけ。
--       数値は配備記録に残し、運用後の見込み（契約数・月次の請求数）と合わせて DBA が規模を判断する。

-- (3) 列名が backup tool の受け付ける形であること
SELECT table_name, column_name FROM information_schema.columns
 WHERE table_schema = 'ses_manager_db'
   AND table_name IN ('flyway_schema_history', 'sys_user', 'm_system_config',
                      'm_permission_group', 't_permission_group_action', 't_user_permission_group',
                      't_legal_hold', 't_contract', 't_invoice')
   AND column_name NOT REGEXP '^[A-Za-z0-9_]{1,64}$';
-- 期待: 0行
```

- 静止区間の確かめ方: staging（BQ-4 と同じ機会でよい）で決めた値の checkpoint を取り、`quiesce.json`・結果の JSON の静止区間の内訳の `content_hash` の秒数を見る（CI の小さい fixture では 0.529 秒。`backup-write-quiesce/design.md` §10.2）。checkpoint の静止区間の全体が最大保持300秒より十分短いこと。延びすぎるなら候補 A へ戻す（**未取得**）。
- 決めた値は `.env.backup` に書き、配備記録に「値・決めた者・日付・上の (1)〜(3) の結果」を残す（`deploy-runbook.md` §1.7 の DBA-6）。

## 2. DBA-1〜DBA-4 の確認 SQL の一覧

runbook に SQL がある確認は節を引くだけにする（正文は runbook）。「補う」と書いた行は runbook に SQL が無いため本書で補った。

| # | 確かめること | SQL（所在） | 期待値 |
|---|---|---|---|
| DBA-1 (a) | migrator の権限・role・`mandatory_roles`・binlog の設定 | `deploy-runbook.md` §1.2「migrator account」の「確認」の SQL（`SHOW GRANTS`・`mysql.role_edges`・`@@global.mandatory_roles`・`@@global.log_bin` 等） | §1.2 の SQL の注釈のとおり（`ses_manager_db`.* への14権限だけ、role 0、`mandatory_roles` 空、`binlog_format = 'ROW'`、`log_bin = 1` なら `log_bin_trust_function_creators = 1` か DBA-1 で決めた SUPER 相当） |
| DBA-1 (b) | migrator に `SYSTEM_USER` が無い（復元先の restore target role に `SYSTEM_USER` を要らなくするため。DBA-1 の「recovery target の前提」） | 補う: `SELECT PRIV FROM mysql.global_grants WHERE USER = '<migrator>' AND HOST = '<host>' AND PRIV = 'SYSTEM_USER';` | 0行 |
| DBA-1 (c) | アプリ account の権限・role・host の型 | §1.2「アプリ account」の「確認」の SQL と `SELECT user, host FROM mysql.user WHERE user = '<アプリaccount>';` | §1.2 の注釈のとおり（`SELECT, INSERT, UPDATE, DELETE` だけ、role 0、host の行は1つ） |
| DBA-1 (d) | backup account の権限（要求の表への表単位の権限は §3.4 の後） | §1.2「backup 用の account」の `SHOW GRANTS FOR '<backup account>'@'<host>';` と `mysql.role_edges`。§3.4 の「backup account への表単位の GRANT」の `SHOW GRANTS` | README「権限分離」の「source 側の account の権限」の表のとおりで `PROCESS` が無い。§3.4 の後は `sys_backup_quiesce_request` への `INSERT, UPDATE` の行が1つ増える（表を作る前は GRANT できない。CI で実測。`backup-write-quiesce/design.md` §19） |
| DBA-1 (e) | 適用後の routine・trigger の件数 | §3.4 の確認 SQL（routine・trigger の COUNT） | §3.4 の注釈の件数（routine 0。trigger は §1.2・§3.4 に書いた件数） |
| DBA-1 (f) | trigger の DEFINER が migrator だけ（migrator を残す前提。§1.2「DEFINER」） | 補う: `SELECT DEFINER, COUNT(*) FROM information_schema.triggers WHERE trigger_schema = 'ses_manager_db' GROUP BY DEFINER;` | 1行（`<migrator>@<host>`。件数は §3.4 の trigger の件数と同じ） |
| DBA-1 (g) | recovery target の binlog と `log_bin_trust_function_creators`（DBA-1 の「recovery target の前提」） | 補う（recovery target で）: `SELECT @@global.log_bin, @@global.log_bin_trust_function_creators;` | `log_bin = 1` なら `log_bin_trust_function_creators = 1`（restore target role は SUPER を持たない） |
| DBA-1 (h) | recovery target の DEFINER の account（作る方針）または `ALLOW_NONEXISTENT_DEFINER`（作らない方針） | 補う（recovery target で）: `SELECT user, host FROM mysql.user WHERE user = '<migrator>';` と `SHOW GRANTS FOR '<restore target role>';` | 作る方針（推奨）: migrator の行が1つ。作らない方針: restore target role の global の行に `SET_ANY_DEFINER` と `ALLOW_NONEXISTENT_DEFINER` がある。どちらの方針でも restore target role は README「restore target role の権限」の表のとおり |
| DBA-1 (i) | 配備前の検証（binlog 有効・決めた `log_bin_trust_function_creators`・root でない migrator で job を1回通す。本番ではない MySQL） | §3.3 の job と §3.4 の確認 SQL（runbook の DBA-1「配備前の検証」） | §3.4 の注釈のとおり（**未実施**） |
| DBA-2 (a) | server のタイムゾーン | `deploy-runbook.md` §1.1 の `SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();` | `@@global.time_zone = '+09:00'`、`NOW()` が `UTC_TIMESTAMP()` の9時間後 |
| DBA-2 (b) | recovery target のタイムゾーン | `restore-cutover.md` §1 の `SELECT @@global.time_zone;` | `+09:00` |
| DBA-2 (c) | 対象 schema が空、DB の文字コード | §1.1 の「対象schemaが空であること」の3つの COUNT と `information_schema.schemata` の SQL | 0・0・0、`utf8mb4` / `utf8mb4_unicode_ci` |
| DBA-3 | `binlog_format` | `deploy-runbook.md` §1.7 の DBA-3 の `SELECT @@global.binlog_format;`（§1.2 の SQL にも含まれる） | `'ROW'` |
| DBA-4 (a) | 版と server の文字コード | §1.1 の `SELECT VERSION(), @@global.character_set_server, @@global.collation_server;` | 版は 8.4 LTS の決めた exact 版（候補 8.4.13。§1.6 の3） |
| DBA-4 (b) | 本番と recovery target の exact 版と `lower_case_table_names` が同じ | 補う（本番と recovery target の両方で）: `SELECT VERSION(), @@global.lower_case_table_names;` | 両方で同じ値（違うと schema fingerprint が一致せず cutover できない。§1.1・DBA-4） |
| DBA-4 (c) | `innodb_print_all_deadlocks` | §1.1 の `SELECT @@global.innodb_print_all_deadlocks;` | `1` |

- DBA-5（RPO・RTO の署名）と DBA-6 の確定は SQL の確認ではなく署名・決定で、どちらも**未取得**（`deploy-runbook.md` §1.7）。
- 結果は配備記録に、実行した SQL・結果・確かめた者・日付を残す（値に password・hash を含めない）。
