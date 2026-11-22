# バックアップ運用手順（HFP-03）

## 概要

MySQL 8 の業務データと uploads を restic repository へ退避し、指定 UTC 時刻以前の
**検証済み整合 checkpoint** へ復元する。

### 復旧目標（RPO / RTO）

| 項目 | 目標値 | 定義 |
|---|---|---|
| RPO | **15 分** | incident / 要求時刻と実効復旧点（checkpoint）の差 |
| RTO | **4 時間** | incident 宣言から read-write 再開承認まで（plan・承認 / download・verify / DB replay / uploads / validation / cutover の区間合計） |

- 2026-11-13 の本番投入決定（B-NF09-4）で上記を正式な目標値とした。本リポジトリ内の他文書
  （`.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md` を含む）もこの値に揃えている。
- **DBA Lead / Operations の署名は未取得（PENDING）**。署名までは承認済みの SLA として扱わない。
- 代表データ量（HFP-03-PROD-007）の隔離演習（HFP-03-012）で実測するまでは「未達成」扱い。
  CI / integration の合成 fixture で記録される RPO 秒・RTO 秒は production の達成を意味しない。

**現状の注意:** production 固有値（HFP-03-PROD-001〜008）は未確定のため
[`.kiro/specs/database-backup-recovery/baseline.md`](../../.kiro/specs/database-backup-recovery/baseline.md) §4 で BLOCKED。
production 環境への接続・復元は行わないこと。初回本番配備で backup を始める前に確定する値と BLOCKED の解除条件は `.kiro/specs/production-launch/deploy-runbook.md` §1.7 の「Backup 開始の前提」に書く。

## 前提

- ツールイメージ（`ops/backup/Dockerfile`）: Oracle MySQL 8.4 LTS（8.4.13）client + mysqlbinlog + mysqldump + restic 0.17.3。
  production の MySQL server は 8.4 LTS だけで（2026-11-13 決定）、`preflight.sh` は client・mysqlbinlog・mysqldump と
  server がすべて 8.4 系列であることを求める（RB-W 8.4 対応）。`image/install-toolchain.sh` は client の版と repo の
  component を列挙した値（`8.4.13`・`mysql-8.4-lts`）だけ受け付け、8.0・列挙していない patch・innovation 系では何も
  ダウンロードせずに失敗する。patch を上げるときは repo の `Packages` で client と server-core の版を確かめ、Dockerfile の
  ARG と同じ変更で列挙に足す。
  private registry は使わず、検証済み build の content ID（image store に依存しない識別子）を記録して
  `docker save` / `docker load` で配布する（「ツールイメージの build・配布」参照）。
- MySQL 8.4 で変わった点への対応（RB-W 8.4 対応）: mysqldump の `--source-data=2` が書く
  `CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE=…, SOURCE_LOG_POS=…` から full の起点を読む。8.0 の旧形式
  `CHANGE MASTER TO … MASTER_LOG_FILE` は受け付けない（production は 8.4 だけで、8.4 の server・mysqldump 8.4.13 は旧形式を
  使わない。旧形式だけの dump は起点を取れずに backup が失敗する = fail-closed）。復元前の GTID・binlog のリセットは `RESET BINARY LOGS AND GTIDS`（8.4 で `RESET MASTER` は削除）。
  `binlog_format` は 8.4 で非推奨（既定の ROW だけを使う）のため、integration の server 起動 option から外した。preflight は
  変数があり ROW でなければ exit 12 で止まる（変数そのものが無い版では判定しない。RB-W P3-6）。`SHOW MASTER STATUS` などの 8.4 で削除された構文は使わない（`tests/script-contract-test.sh` が本番 script と
  integration を走査する）。mysqldump 8.4.13 が `--source-data=2` で 8.4 の server に発行する構文（`SHOW BINARY LOG STATUS`
  の想定）と、その出力の形式は CI（実 MySQL 8.4.13 の integration）で確認した（2026-11-21。full が dump の起点を読み、その起点からの replay で full の後の更新が復元先に入った）。
  preflight の server の変数は `SHOW GLOBAL VARIABLES` で 1 つずつ有無を見て、無い変数は名前を挙げて失敗する。
  TLS は `@@have_ssl`（8.4 で残るかは CI で確認）を使わず、この接続の `Ssl_cipher` で判定する。
  mysqlbinlog の `--read-from-remote-server --raw --stop-never` と `MYSQL_PWD`（integration の root 操作だけが使う）が 8.4 でも
  使えることは CI（実 MySQL 8.4.13）で確認した（2026-11-21。binlog の archive と restore の replay が PASS）。
- MySQL サーバの時刻帯（2026-11-13 決定）: production の source と、復元先（recovery target）の MySQL サーバは
  いずれも **`time_zone = '+09:00'`**（DBA が設定）とする。復元先が異なる時刻帯だと、cutover 後のアプリの
  `NOW()` / `CURRENT_DATE` と TIMESTAMP 列の表示が変わるため、復元前に `SELECT @@global.time_zone;` で確認する。
  （内容ハッシュは session の time_zone を `+00:00` に固定して計算するため、この値に依存しない。）
- `.env.backup` に接続情報（secret 値は含めない）:
  `BACKUP_REPOSITORY`、`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`
- `secrets/mysql-password`（0600）: MySQL パスワード
- `secrets/restic-password`（0600）: restic repository パスワード
- `secrets/mysql-capath/` : hashed CA ディレクトリ（`openssl rehash` / `c_rehash` 済み）
  ※ MySQL 8.0.46 client は `--ssl-ca` + VERIFY_* が使えなかった（research.md §4 実測）。8.4 client で同じかは未確認のため
  （CI で確認）、引き続き capath を使う。
  CA は hashed capath で提供し、`MYSQL_SSL_CAPATH` で指定する。

## 設定（`.env.backup`）

| 変数 | 説明 |
|---|---|
| `FLYWAY_VERSION` | 適用済みの最新 Flyway version を **Flyway 形式**（`V`・`_` を使わない。例: `185`、`185.1`）で設定する。checkpoint metadata の `flyway_max_success` になり、復元検証で `flyway_schema_history` の成功行の版番号順の最大と照合する。未設定（`unknown`）の checkpoint は復元検証を通らない |
| `CONTENT_HASH_TABLES` | 内容ハッシュ（`lib/content-hash.sh`。主キー順に並べた全列を、SQL の NULL と文字列 `'NULL'` を区別して SHA-256）を記録する主要 table を `,` 区切りで指定する。**主キーの無い table は指定できない**（指定すると backup が失敗する）。full / checkpoint の書込み静止区間内に計算するため、静止時間が延びすぎない範囲に絞る（`flyway_schema_history` を含めることを推奨）。指定した table を 1 つでも計算できなければ backup を VALID にしない。復元検証も同じ値を参照し、指定 table の記録が checkpoint に無ければ（`NOT_RECORDED` を含む）FAIL にする |
| `CRITICAL_TABLES` | full 時に件数を記録する主要 table（従来どおり） |
| `UPLOADS_ROOT` | uploads の根（container 内の path。production は `/app/uploads`）。その下に版の directory（`v0`、`v1`、…）と pointer の symlink がある（下の「uploads の配置と mount」）。full / checkpoint は必須、preflight は任意（指定すると pointer を解決できることを確かめる） |
| `UPLOADS_POINTER` | pointer の名前（既定 `current`）。`current -> v<n>` の相対・1 段の symlink であること |
| `UPLOADS_STAGING_PARENT` | uploads snapshot の staging の親（production は `/var/lib/ses-uploads-staging`）。`UPLOADS_ROOT` と同じ mount（`stat -L -c %d` の st_dev が同じ）でなければ provider が拒否する（preflight も pointer の指す版と比べ、違えば exit 16）。`backup-data`（`/var/lib/ses-backup`）の下に置かない（別の filesystem） |

`FLYWAY_HISTORY_REQUIRED` は廃止した（W1-B1 r2）。`flyway_schema_history` の照合は常に必須で、
この変数が 1 以外の値で残っていると `validate-restore.sh` は検証を始めずに失敗する。
`UPLOADS_DIR` は廃止した（RB-W 追加修正 C）。残っていると full・checkpoint・preflight は失敗する（版の directory や根を
pointer を介さずに backup しないため）。`UPLOADS_ROOT` と `UPLOADS_POINTER` に置き換える。

### uploads の配置と mount（RB-W 追加修正 C。2026-11-18 利用者決定: 方式 A・同じ uid）

- **配置**: uploads の access point の根（app の container では `/app/uploads`）の下に、版の directory `v0`、`v1`、… と、
  相対・1 段の symlink `current -> v<n>` を置く。app は `UPLOAD_BASE_PATH=/app/uploads/current` を使う。app の初回の起動前に
  `v0` と `current -> v0` を作る（`current` が無いと app が実 directory として作り、pointer が効かなくなる。手順は deploy-runbook）。
- **pointer の解決**（`lib/uploads-pointer.sh`）: backup は `current` を `find` にも host の mount にも辿らせない。uploads の根を
  `:ro` で bind した container の中で `readlink`（`-f` なし）で値だけを読み、`^[A-Za-z0-9][A-Za-z0-9._-]*$` の 1 段の名前だけを
  受け付けて `<根>/<名前>` を snapshot の src にする。根が symlink・pointer が無い・pointer が実 directory や file・指す先が
  絶対 path・`/` や `..` を含む・無い・symlink・file の場合は失敗する（fail-closed）。file が 1 つも無い版は inventory を `[]` と
  して成功する（初回 deploy 直後の空の uploads）。
- **記録**: snapshot の JSON（`pointer`・`pointer_target`）、full / checkpoint の `metadata.json`・index・結果 JSON の
  `uploads_pointer: {name, target}` に、どの版を backup したかを残す。
- **host の mount と bind**: backup host は backup 用の access point（根に `uploads/` と `backup-staging/` がある。trust は
  含めない）を **1 回だけ** `/mnt/ses-files` に mount する。`docker-compose.yml` はその `uploads/` を `/app/uploads` に
  `:ro` で、`backup-staging/` を `/var/lib/ses-uploads-staging` に bind する。`.env.backup` は `UPLOADS_ROOT=/app/uploads`・
  `UPLOADS_POINTER=current`・`UPLOADS_STAGING_PARENT=/var/lib/ses-uploads-staging`。
- **「同一 filesystem」の意味**: provider は src と staging の st_dev（`stat -L -c %d`）を比べる。同じ host mount の bind 同士は
  同じ値になる。同じ EFS でも access point ごとに別々に mount したもの（efs-utils の TLS mount を含む）は st_dev が違い、
  拒否される（backup が止まる。fail-closed）。uploads と staging を別の mount にしない。`:ro` の bind は mount の
  属性だけを変え、st_dev は変えない（provider は pointer の指す版の directory と staging の親を比べる）。
- **SRE の確認方法**（backup host。初回 backup の前と、mount を変えたとき）:
  1. host で `stat -L -c %d /mnt/ses-files/uploads/current /mnt/ses-files/backup-staging` の 2 行が同じ値であること
     （`current` は pointer。`-L` で指す版の directory の値になる）。
  2. container の中でも同じであること: `ops/backup/image/tool-image.sh compose run --rm preflight` の JSON の
     `checks.uploads` が `status: "ok"`、`version` が今の版（初回は `v0`）、`staging_same_filesystem: true` であること。
     preflight は `UPLOADS_STAGING_PARENT` が設定されていると、pointer の指す版と staging の親の st_dev を provider と同じ
     `stat -L -c %d` で比べ、違う・取れない場合は exit 16 で止まる。
  値が違う・exit 16 の場合は backup を始めない（mount を 1 回にまとめる。pointer が無ければ deploy-runbook の手順で作る）。
- **uid の模型**: backup の access point と app の access point は同じ POSIX uid/gid を使う（2026-11-18 利用者決定）。
  backup による uploads の改変を防ぐのは compose の `:ro` bind だけである（Security の受理事項）。同じ uid のため、app が 0600 で
  作る file（取込センターの台帳・隔離中の file）も backup が読め、`cp -a` の所有者の保持は同じ uid への変更（no-op）になり、
  ops/backup の script の `umask 077` で restore が作る 0700 の directory も app が読み書きできる。mode の正規化はしない
  （uid が分かれると成り立たない。分ける場合は provider・restore の所有者と mode の扱いを見直す）。

### 書込み静止の provider と設定（RB-QUIESCE。`.kiro/specs/backup-write-quiesce/design.md` §7・§9・§12.4）

full と checkpoint は整合点を取る前に書込みの静止を取り、静止区間の終わりに verify してから解く。provider は次の 3 つだけで、
`lib/quiesce.sh` が振り分ける（どれも repository の固定の executable。任意の `bash -c` は受け付けない）。

| provider | 選び方 | 使う場面 | 静止の確かめ方 |
|---|---|---|---|
| `quiesce-db` | `.env.backup` の `QUIESCE_PROVIDER=quiesce-db`（本番） | 定期の checkpoint・full（app が動いている時。ONLINE） | DB の信号表（V202 の `sys_backup_quiesce_request`・`_instance`・`_ack`）に静止要求を登録し、全ての app の instance が排水して応答（QUIESCED）したことを確かめる。要求の前に保持 process が DDL 凍結の lock（`GET_LOCK('ses_backup_ddl_freeze')`）を取り、静止区間の間持つ。区間の終わりの verify（応答の時刻・app の hold 期限・接続元の観測し直し）が通らなければ VALID にしない |
| `quiesce-app-stopped` | `backup-full.sh --app-stopped`・`create-checkpoint.sh --app-stopped` の明示の引数だけ（`QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で渡すと job は始まらない） | app が動いていない時（初回 backup・配備の時・app を止めた保守。deploy-runbook §2 の5） | APP_STOPPED の保持を登録してから、全ての instance が停止済み（`STOPPED`、または heartbeat が `QUIESCE_DEAD_SECONDS` より古い）であることを確かめる。保持の間に起動した app は起動を中止する。heartbeat を止めたまま書き続ける instance は機械では見つけられないため、DBA が processlist でアプリ接続 0 件を確かめてから実行する（deploy-runbook §2 の4） |
| `quiesce-local` | 既定（`QUIESCE_PROVIDER` 未設定） | 隔離・ローカル環境の試験だけ | file の heartbeat（`REPLICA_HEARTBEAT_DIR`）と scheduler の ack（`SCHEDULER_ACK_DIR`）。本番では使わない |

- どちらの DB の provider も、停止口（app の `BACKUP_QUIESCE_ENABLED=false`。deploy-runbook §1.0）で動いている instance の行（STOPPED でない
  `gate_state = 'DISABLED'`。heartbeat の古さによらない）があれば失敗する。停止口の間は backup が全て失敗する。
- ONLINE の full の最中に新しい app の instance が登録されたら（task の置き換え等）、provider はその full を中止して静止を解く（U-13。
  下の「欠けた full の取り直し」）。APP_STOPPED の full は中止しない（起動しようとした instance の方が起動を中止する）。checkpoint の
  最中に登録した instance は、排水して応答するのを待つ。
- 保持 process: DB の provider の job は、provider の `hold` を job 本体の pipe に結び付けて起動し（process substitution。bash 4.4 以上）、
  DDL 凍結の lock の session を持ち、15 秒ごとに要求の lease を延ばす。job 本体が終わる（正常・異常・SIGKILL）と pipe が閉じて
  保持 process も終わり、lock が返る（app は lease の期限で再開する）。保持 process の出力は state dir の `hold.log` に出る（結果の
  JSON に混ぜない）。job が起動する長い子 process（mysqldump・`cp -a`・restic 等）は pipe の書き口を閉じて起動する
  （`tests/script-contract-test.sh` が確かめる）。DDL 凍結の lock を待つ秒数は 30 秒の固定値（変数は無い）。
- 静止区間の秒数と内訳は `quiesce.json` の `window_seconds`・`window_breakdown_seconds`（metadata.json と index にも入る）と、結果の
  JSON の `quiesce.window_seconds` に残り、`QUIESCE_WINDOW_WARN_SECONDS` を超えると WARN を出す。

`.env.backup` の変数（既定のままでよい。app 側の設定（deploy-runbook §1.0 の33〜41）と式が合わなければ provider と preflight が
失敗する）:

| 変数 | 既定 | 内容 |
|---|---|---|
| `QUIESCE_PROVIDER` | `quiesce-local` | 本番は `quiesce-db`。`quiesce-app-stopped` は環境変数では拒否する |
| `QUIESCE_STALE_SECONDS` | 60 | heartbeat がこれより古く応答の無い instance は状態不明として失敗する。app の `fence-after` + 2 × `heartbeat-interval` 以上 |
| `QUIESCE_DEAD_SECONDS` | 300 | heartbeat がこれより古い instance は停止済みとして無視する（停止口の行を除く）。`QUIESCE_STALE_SECONDS` より大きい |
| `QUIESCE_DEADLINE_SECONDS` | checkpoint 90・full 660 | 応答を待つ上限（app の排水の上限 checkpoint 30 秒・full 600 秒より長くする） |
| `QUIESCE_LEASE_SECONDS` | 60 | 要求の lease。app の `fence-after` + `poll-interval` < `QUIESCE_LEASE_SECONDS` − `QUIESCE_LEASE_RENEW_SECONDS` |
| `QUIESCE_LEASE_RENEW_SECONDS` | 15 | 保持 process が lease を延ばす間隔（`QUIESCE_LEASE_SECONDS` より短い） |
| `QUIESCE_LEASE_MARGIN_SECONDS` | 5 | verify は、今が app の hold 期限よりこの秒数以上前であることを求める |
| `QUIESCE_LEASE_MAX_SECONDS` | checkpoint 300・full 3600 | 延長の上限（app の最大保持時間と小さい方を使う） |
| `QUIESCE_FULL_NEW_INSTANCE_CHECK_SECONDS` | 5 | ONLINE の full の最中に新しい instance を調べる間隔（U-13） |
| `QUIESCE_VERIFY_OBSERVE_SECONDS` | 10 | verify の前に app の接続元の観測し直しを待つ上限 |
| `QUIESCE_RESUME_CONFIRM_SECONDS` | 15 | 解除の後に app の再開（RESUMED）を待つ上限。確かめられなければ exit 3（重大。`runbooks/backup-health-monitoring.md`） |
| `QUIESCE_WINDOW_WARN_SECONDS` | 60 | 静止区間がこれを超えたら WARN |
| `QUIESCE_REQUESTED_BY` | `<job>:<hostname>` | 要求の `requested_by`（`^[A-Za-z0-9._:-]{1,64}$`。個人名・秘密を書かない） |

- `REPLICA_HEARTBEAT_DIR`・`SCHEDULER_ACK_DIR` は `quiesce-local` のときだけ必須で、本番（`quiesce-db`）では書かない。
- preflight の `checks.quiesce`（DB の provider の時だけ。読むだけ）: 信号表の 3 表（`signal_tables`）、要求の表への表単位の
  `INSERT`・`UPDATE`（`SHOW GRANTS FOR CURRENT_USER()`。`request_table_grant`）、停止口の instance の行が無いこと、新しい instance の
  protocol の版と閾値の式。不合格は exit 19。`quiesce-local` では `status: "skipped"`。
- `CONTENT_HASH_TABLES` に信号表（`sys_backup_quiesce_` で始まる表）を書くと backup が失敗する（静止区間の中でも heartbeat・観測・
  lease の延長で書かれ、復元検証が必ず FAIL になるため。design.md §3.7）。`CRITICAL_TABLES` に書くことも勧めない。

### 書込み静止の記録（quiesce.json）

- **`quiesce.json` の全体は job の state dir にだけある**: 静止を取った job が state dir（`QUIESCE_STATE_DIR`。既定は container の中の
  `/tmp/ses-quiesce-<pid>`）に `quiesce.json`（0600）と `hold.log` を書く。state dir は job の container とともに消え、`run --rm` では job
  の終わりに消える（compose の command そのものが強制終了されて container が止まったまま残った場合は、その container を消すまで残る。
  `tool-image.sh compose ps` で確かめ、SRE が消す）。`QUIESCE_STATE_DIR` を volume 等の残る場所へ向けない。
- **内容（state dir の全体）**: provider・mode・job_kind・要求の番号（`request_id`）・`request_token`（要求ごとの 16 byte の乱数の hex。
  秘密ではないが要求を特定する値）・時刻（`requested_at_utc`・整合時刻 `started_at_utc`・`verified_at_utc`・`released_at_utc`）・静止区間の
  秒数と内訳・DDL lock の名前・state dir の path、instance ごとの ID・`host_name`（app の container の host 名）・`db_client_host`（DB から
  見た app の接続元の IP）・時刻。個人データは含まないが、内部の host 名と IP を含む。
- **backup の metadata には投影だけを入れる**（2d review P3-4）: full・checkpoint の `metadata.json` の `quiesce`（restic repository の
  snapshot と、backup-data の volume の index `$BACKUP_WORK_DIR/index/full-*.json`・`checkpoint-*.json` に、backup の保存期間まで残る。
  snapshot は下の「retention」（PITR 30 日と代表）で消え、index の file は volume の上に残る）は、`lib/metadata.sh` の
  `metadata::quiesce_projection` が作る投影で、provider・mode・job_kind・`request_id`・時刻（requested・started・verified・released）・
  `release_reason`・静止区間の秒数と内訳・instance の件数（`instance_count`・`verified_instance_count`）と ID（`instance_ids`）だけを持つ。
  `host_name`・`db_client_host`・`request_token`・`state_dir`・instance ごとの時刻は入れない（`tests/full-backup-test.sh` が確かめる）。
- **最小化したのは metadata だけ**: DB の full の dump と binlog には信号表（`sys_backup_quiesce_*`）の行そのものが入る（mysqldump に
  `--ignore-table` を付けない。行は `host_name`・`db_client_host`・`observed_app_hosts`・`request_token` を含み、app の保存期間（既定
  30 日）の分がある）。これらは backup そのものの保存期間まで残る。個人データではない（`.kiro/specs/backup-write-quiesce/design.md`
  §3.9）。restore の後の扱いは `runbooks/restore-cutover.md` §4.6。
- **ticket・chat・配備記録に state dir の `quiesce.json` をそのまま貼らない**。記録には job の結果の JSON（stdout）の `quiesce` の欄
  （provider・mode・`request_id`・時刻・`window_seconds`）か、index の `quiesce`（上の投影。`jq '.quiesce' <index の file>`）を使う。
  CI の backup integration の証跡（`quiesce-full.json`・`quiesce-checkpoint.json`）も同じ考え方の投影である。障害の調査で instance ごとの
  host 名・接続元が要るときは、job の stderr（provider の失敗の message には instance ごとの host 名が出る）か job の最中の state dir を
  backup host の上で見て、ticket には instance ID と結論だけを書く（preflight の JSON の理由は instance ID と件数だけを出す）。

## 実行

job は必ず `image/tool-image.sh compose` 経由で起動する。docker-compose.yml の
`x-ses-backup-tool-content-id` とローカルの `ses-backup-tool:8.4.13` の content ID が一致しない
（または未 pin の）場合は起動を拒否する。`tool-image.sh compose` が渡すのは次の使い方だけで、
`-f`・`-p`・`--pull`・`--build`・`--entrypoint`・`-e`・`-v` などの compose 側 option、別の compose ファイル、
未定義の service は拒否する（終了コード 2）。

| 使い方 | pin 照合 |
|---|---|
| `run --rm <service> [コンテナへ渡す引数...]` | する |
| `up -d binlog` | する |
| `ps [<service>]`・`logs [--tail N] <service>`・`stop <service>` | しない（image を起動しない状態確認・停止） |

`<service>` は `preflight` / `backup` / `binlog` / `checkpoint` / `snapshot-binlog` / `check` / `retention`。

pin 照合（`tool-image.sh check`。RB-W W-3）の内容:

- `docker-compose.yml` を自前で解釈する。扱うのは block style の YAML（`key: スカラー`・`key:` + 子 block・
  `- スカラー`、トップレベルの `x-` key の anchor と、`x-` mapping・service 直下の `<<: *anchor`）だけで、
  flow style（`{ }` / `[ ]`）・tag（`!`）・block scalar（`|` `>`）・それ以外の anchor / alias・引用符付き key・
  document marker・indent の tab・key の重複・複数行の値・sequence の中の mapping は、理由を示して拒否する。
  compose を変えるときも block style で書く。
- service の集合が上の 7 つと一致し、**service ごとに** merge を解決した実効値で、`image` が同じツールイメージの tag、
  `pull_policy: never`、`build` / `extends` が無いことを確かめる（トップレベルの `include` も拒否する）。
- tag を **1 回だけ** image ID へ解決し、その image ID から content ID を計算して pin と照合する。
  `run` / `up` は、照合した image ID を全 service の `image` に固定する一時 override（本プロセス専用の一時ディレクトリに
  作り、終了時に削除する）を 2 つ目の `-f` として渡して起動する。照合の後に tag を別の image へ付け替えられても、
  起動するのは照合した image だけである（image ID は内容から決まり、付け替えられない）。

```bash
# 1. 環境契約検査（read-only）: 0 でなければ先へ進まない
ops/backup/image/tool-image.sh compose run --rm preflight

# 2. 日次 full（02:00 Asia/Tokyo 想定）
ops/backup/image/tool-image.sh compose run --rm backup

# 3. 継続 binlog archive（常駐）・15 分 checkpoint・閉じた binlog snapshot
ops/backup/image/tool-image.sh compose up -d binlog
ops/backup/image/tool-image.sh compose run --rm checkpoint     # cron 等で 15 分ごと
ops/backup/image/tool-image.sh compose run --rm snapshot-binlog

# 4. 監視（watermark 基準。alert は外部へ routing）
ops/backup/image/tool-image.sh compose run --rm check --json

# 5. app が動いていない時の full（初回 backup・配備の時・app を止めた保守。deploy-runbook §2 の5）。
#    app の停止を DB の信号表で確かめる（provider quiesce-app-stopped）。定期の job には付けない
ops/backup/image/tool-image.sh compose run --rm backup --app-stopped
```
`MYSQL_PWD` 環境変数は使わない。秘密は 0600 の一時 option file 経由で渡す。

- 定期の full・checkpoint は `.env.backup` の `QUIESCE_PROVIDER=quiesce-db`（ONLINE）で動く。`--app-stopped` は app を止めた後にだけ
  手で付ける（app が動いている間に流すと、app は更新を止め（ERROR `BACKUP_QUIESCE_STOPPED_HOLD_SEEN`）、その backup は VALID に
  ならない）。app を止めた保守の checkpoint も `create-checkpoint.sh --app-stopped`（`tool-image.sh compose run --rm checkpoint --app-stopped`）で取れる。
- 配備の間は backup の定期起動を止める（deploy-runbook §2 の0。Flyway job と DBA の DDL は同じ DDL 凍結の lock を取るため重ならないが、
  止めてから進める）。§4 の後に再開し、最初の ONLINE の checkpoint が VALID になることを確かめる（deploy-runbook §4.7）。

### 欠けた full の取り直し（U-13）

- ONLINE の full（02:00）の最中に新しい app の instance が登録されると（task の置き換え・再起動）、provider はその full を中止して静止を
  解く（要求は `ABORTED`・`release_reason = 'NEW_INSTANCE'`。backup tool と app の両方が WARN `BACKUP_QUIESCE_FULL_ABORTED` を出し、
  `backup-full.sh` は mysqldump を止めて非 0 で終わる）。その日の full は欠ける。PITR の基点は前日の full のままで、RPO は checkpoint で
  保たれる。
- 取り直し: app の起動が落ち着いた（readiness が UP で、heartbeat の新しい instance が 1 つ。deploy-runbook §4.7 の1）後に、運用担当が
  手で流す（app は動いているので `--app-stopped` は付けない）。
  ```bash
  ops/backup/image/tool-image.sh compose run --rm backup
  ```
- **期限: 中止を知ったら 2 時間以内に取り直す**。full の CRITICAL の閾値は最新の VALID な full から 26 時間（`lib/health.sh` の
  `HEALTH_FULL_CRITICAL_SECONDS=93600`）で、前日の 02:00 の full から数えるため、当日のおよそ 04:00 までに取り直しが VALID に
  ならなければ `check-backup.sh` が CRITICAL になる。間に合わなければ CRITICAL を計画外の既知の事象として記録し、取り直しの VALID で
  解消を確かめる（閾値は変えない）。
- 取り直しの最中にも新しい instance が登録されれば同じく中止される。task の置き換えが続く間は、落ち着くのを待ってから流す。
- 対応の手順の正本は `runbooks/backup-health-monitoring.md` の「WARN: full の中止（取り直し）」。

## ツールイメージの build・配布（registry を使わない運用）

決定（XB-10）: private registry が無いため、**ローカル build + ダウンロードの SHA-256 検証 + 識別子の記録 +
`docker save` / `docker load` による配布** とする。

### 識別子と image store の前提

- **image ID（`docker image inspect` の `.Id`）は Docker の image store に依存する。** classic（graphdriver）store では
  image config の digest、containerd image store（containerd snapshotter。Docker Desktop の新規インストールなどの既定）では
  image index / manifest の digest になり、store の異なるホスト間で `docker save` / `docker load` すると一致しない。
  そのため image ID と image store の種類（`docker info` の `DriverStatus` に `io.containerd.snapshotter` があれば
  containerd、無ければ classic）は**記録だけ**に使う。
- **pin と照合には content ID を使う。** os / arch、RootFS の layer diff ID 列（非圧縮 layer の digest）と image config の
  実行に関わる項目（Env / Entrypoint / Cmd / Shell / OnBuild / WorkingDir / User / StopSignal / Volumes / ExposedPorts /
  Labels / Healthcheck。RB-W W-7）から計算した SHA-256 で、image store に依存しない。build 機と配布先の image store が
  異なっても照合できる（`load` は違いを通知したうえで content ID で照合する）。算出方式は `ses-backup-tool-content-id-v2`。
- content ID の正規化: 1 回の `docker image inspect` で項目を 1 行ずつ描画する。文字列は要素ごとに `{{json}}`
  （Go の encoding/json で改行等を escape し、Docker の版で変わらない）、list は要素を順序どおり、map（Volumes・
  ExposedPorts・Labels）は Go template が key の昇順で回す。`null` と空の list / map は同じ（行を出さない）。
  Config 全体を 1 つの json にする出力は使わない（Docker の版で Hostname などの項目が増減するため）。Id・Created・
  RepoTags・RepoDigests・GraphDriver・Metadata・DockerVersion など、内容に関わらず store や版で変わる項目も使わない。
  描画結果の CR（Windows の docker.exe）は除く。
- なお fail-closed になる場合: Docker の版が古く（または新しく）上の項目のどれかを描画できない、描画に想定外の行・
  単一項目の重複・途中の空行がある、layer が無い。いずれも content ID を計算できず `check` / `load` / `record` が失敗する。
  v1（r2 まで）の content ID で pin した値は v2 と一致しないため、pin し直す（production の compose は未 pin）。

### 手順

1. **lock の確定（Debian パッケージ版も固定する場合）**: 検証済みの build 機で一度 build し、image 内の
   `dpkg-packages.tsv` を確認する。
   ```bash
   ops/backup/image/tool-image.sh build-record --out <evidence-dir-1>
   ```
   内容を確認した `<evidence-dir-1>/image-manifest/dpkg-packages.tsv` を `image/apt-packages.lock` へ反映して **commit する**。
   lock は image に COPY されるため、lock を変えた commit から build し直した image が配布対象になる
   （lock を固定しない場合はこの手順を省き、2. から行う）。
2. **配布対象の build と記録**（1. の commit から）:
   ```bash
   ops/backup/image/tool-image.sh build-record --out <evidence-dir-2>
   ```
   - `Dockerfile` → `image/install-toolchain.sh` が、固定値の仮置きの値（`UNPINNED`）を拒否し、MySQL APT 署名鍵の
     SHA-256・primary key が 1 つであること・primary key の full fingerprint・有効期限、restic の SHA-256
     （公式 `SHA256SUMS`）を検証する。1 つでも満たさなければ build が失敗する。lock に記載があれば完全一致も要求する。
   - MySQL APT 署名鍵（`RPM-GPG-KEY-mysql-2025`）の有効期限は **2027-10-23**。期限を過ぎると build が失敗するため、
     期限前に新しい鍵ファイルの SHA-256・fingerprint・期限を確認して `Dockerfile` を更新する。
   - `mysql-apt-config` deb は使わない（旧版 0.8.33-1 の SHA-256 を一次資料で確認できないため）。
     fingerprint を確認した鍵だけで keyring を作り、apt source を直接定義する。
   - MySQL client / server-core は exact version、base image は digest で固定する。tar / util-linux などの
     Essential パッケージは明示的には導入・再インストールしないが、**依存解決で base image の版から更新されることがある**
     （「base image の版を保つ」とは限らない。RB-W W-4）。実際の版は image 内の `dpkg-packages.tsv` が正本で、Essential の
     base image からの変化は `essential-packages.tsv`（パッケージ名・base の版・導入後の版・same/changed/added/removed）に
     記録され、変化があれば build ログに WARN が出る。Essential を含む全パッケージの版を固定するのは lock で、
     lock が空なら Debian パッケージ版は記録だけを行う（build ログに WARN）。
   - lock の照合（記載あり = enforced・不一致は失敗・空 = record-only）は `tests/tool-image-test.sh` が、keyring・apt source・
     restic・apt lists を書く system path を `INSTALL_TOOLCHAIN_SYSROOT`（単体試験だけが一時ディレクトリを指定する。既定は空 =
     実際の path で、Dockerfile は設定しない）で一時ディレクトリへ向けて、導入経路の最後まで通して確かめる（RB-W W-5）。
   - 機械的な照合: Dockerfile の最後の RUN（`install-toolchain.sh --verify-manifest`）が image の実際の dpkg の版と
     `dpkg-packages.tsv`・`toolchain.env` の SHA-256 を照合し、一致しなければ build が失敗する。`record` も build 後の image で
     network なしの一時コンテナの `dpkg-query` を実行して同じ照合を行い、一致しなければ何も記録しない。
   - `<evidence-dir-2>` に `tool-content-id.txt`（content ID）、`tool-image-id.txt`（image ID。記録のみ）、
     `tool-image-record.txt`（content ID・image ID・image store の種類・storage driver・Docker version）、
     `image-manifest/`（image 内の `toolchain.env` = 固定値、`dpkg-packages.tsv` = 全パッケージ版、`essential-packages.tsv` =
     Essential の base image からの変化）を書く。既存の記録は上書きしない。
3. **pin**: `docker-compose.yml` の `x-ses-backup-tool-content-id` を 2. の `tool-content-id.txt` の値に書き換えて commit する
   （compose は image に COPY されないため、この commit で image は変わらない）。
4. **配布 tar の作成**（2. の build 機で）:
   ```bash
   ops/backup/image/tool-image.sh save --out <dist-dir>
   ```
   `ses-backup-tool-<content ID 先頭 12 桁>.tar` と、その `.sha256`・`.content-id`・`.image-id`・`.image-store` を作る。
5. **各ホストでの読み込み**（tar の SHA-256 → `docker load` → content ID を照合）:
   ```bash
   ops/backup/image/tool-image.sh load --tar <tar> --sha256 <hex> --content-id sha256:<hex>
   ops/backup/image/tool-image.sh check      # compose の pin とローカル image の一致確認
   ```

**image に入る file を変えたときの pin のし直し（RB-QUIESCE で該当）**: content ID は image の layer（`COPY` した `lib/`・`providers/`・
job の script を含む）から計算するため、それらを変えた commit から build した image は content ID が変わる。RB-QUIESCE は `providers/`
（`quiesce-db.sh`・`quiesce-app-stopped.sh`・`quiesce-db.d/*.sql`）・`lib/`（`quiesce.sh`・`quiesce-db-common.sh`・`content-hash.sh`）・
`backup-full.sh`・`create-checkpoint.sh`・`preflight.sh`・`tests/integration-pitr.sh` を変えた。それより前の commit から build・記録した
image（RB-W の検証で作ったもの等）は使わない（content ID が pin と合わず `check`・`compose` が拒否し、DB の provider も持たない）。
配備する commit から上の手順の 2（`build-record`）・3（pin の commit）・4（`save`）・5（各 host で `load` と `check`）をやり直す
（lock を変える場合だけ 1 から）。3 の commit は compose だけを変え image は変わらないため、2 で作った image がそのまま配布の対象に
なる。記録した content ID と tar の SHA-256 は配備記録に残す（deploy-runbook §1.7 の「Backup 開始の前提」）。

production の `docker-compose.yml` は `build:` を持たず、全 service が `pull_policy: never` で同名 image を registry から
取得させない（`check` が service ごとに確かめる）。

## 権限分離（RQ-008）

| role | DB 権限 | repository 権限 | 用途 |
|---|---|---|---|
| dump role | 下の「source 側の account の権限」のうち full・checkpoint の行（`SELECT`・`SHOW VIEW`・`TRIGGER`・`EVENT`・`SHOW_ROUTINE`・`RELOAD`・`REPLICATION CLIENT`、書込み静止の要求の表への表単位の `INSERT`・`UPDATE`） | writer（backup 可・delete 不可） | full/checkpoint |
| binlog role | 下の「source 側の account の権限」のうち archive・snapshot・check の行（`REPLICATION SLAVE`・`REPLICATION CLIENT`） | writer | archive/snapshot |
| retention role | なし | **唯一** forget/prune 可（専用 maintenance 時のみ） | retention |
| restore target role | recovery target 新規 DB のみ（source 権限なし）。下の「restore target role の権限」を全部持つこと | read | restore/validate |

#### source 側の account の権限（RB-W。script が source に発行する文と mysqldump の option から）

| 権限 | 範囲 | 使う job | 依拠する文・option |
|---|---|---|---|
| `SELECT` | 対象 DB（`<db>.*`） | full・checkpoint・preflight | mysqldump の table の読み取り、`CRITICAL_TABLES` の `COUNT(*)`（backup-full）、内容ハッシュ（`lib/content-hash.sh`）。schema fingerprint と preflight の engine の検査は information_schema を読み、見える範囲はこの権限で決まる |
| `SHOW VIEW` | 対象 DB | full・checkpoint | mysqldump の view（`SHOW CREATE VIEW`）、schema fingerprint の view 定義 |
| `TRIGGER` | 対象 DB | full・checkpoint | mysqldump `--triggers`、schema fingerprint の trigger |
| `EVENT` | 対象 DB | full | mysqldump `--events`（このアプリは event を使わないが、option を付けているため） |
| `SHOW_ROUTINE` | global | full・checkpoint | mysqldump `--routines` の `SHOW CREATE FUNCTION / PROCEDURE`、schema fingerprint の routine 本体。mysqldump の文書は `--routines` に global の `SELECT` を挙げるため、`SHOW_ROUTINE` だけで足りるかは本番相当の権限での実測で確かめる（未取得。integration は root のため CI では確かめられない） |
| `RELOAD` | global | full・checkpoint | mysqldump `--source-data=2`（起点を取る間の `FLUSH TABLES` / `FLUSH TABLES WITH READ LOCK`）、create-checkpoint の `FLUSH BINARY LOGS` |
| `REPLICATION CLIENT` | global | full・checkpoint・archive・snapshot・check | mysqldump `--source-data=2` が 8.4 で発行する `SHOW BINARY LOG STATUS`、`SHOW BINARY LOGS`（create-checkpoint・archive-binlog・snapshot-binlog・check-backup） |
| `REPLICATION SLAVE` | global | archive | `mysqlbinlog --read-from-remote-server`（source から binlog を受け取る） |
| `INSERT`・`UPDATE` | 書込み静止の要求の表（`<db>.sys_backup_quiesce_request`）だけ。**表単位**で、deploy-runbook §3.4（V202 の Flyway の後・初回 backup の前）に付け、restore で新しい source にした後は付け直す（RB-QUIESCE。U-11） | full・checkpoint（`quiesce-db`・`quiesce-app-stopped`）。preflight は有無を検査する | 静止要求の登録・lease の延長・verify の要求・解除（`providers/quiesce-db.d/*.sql`）。信号表の他の 2 表（`sys_backup_quiesce_instance`・`sys_backup_quiesce_ack`）は対象 DB の `SELECT` で読む。backup account が業務 DB の 1 表の書き手になることは baseline の PROD-002 に記録し、Security と DBA が署名する（deploy-runbook §1.7 の BQ-1。未取得） |

要らないもの: `PROCESS`（mysqldump に `--no-tablespaces` を付けるため不要。付けないと mysqldump 8.0.21 以降は tablespace の情報を
読むのに `PROCESS` が要る。2026-11-18 利用者決定で上線前に付けた。書込み静止も使わない: app の接続元は app 自身が観測して応答に
書き、app の停止は heartbeat と DBA の processlist の確認で確かめる。U-2）、`LOCK TABLES`（`--single-transaction` のため table lock を
取らない）、`SUPER`、DDL 凍結の `GET_LOCK`（権限不要）、`SELECT @@server_uuid`・`SHOW GLOBAL VARIABLES`・`SHOW SESSION STATUS`
（権限不要）、`DELETE`（静止要求の行は消さない。保存期間（30 日）を過ぎた行は app が消す）、要求の表以外への書込み。

- `--no-tablespaces` の前提: production に入る migration（`src/main/resources/db/migration`・`db/migration-prod` の SQL と
  `src/main/java/db/migration` の Java migration）は `CREATE TABLESPACE` などの general tablespace を使わない
  （`tests/script-contract-test.sh` の `case_migrations_do_not_use_tablespace` が `TABLESPACE` の不在を確かめ、
  `case_mysqldump_invocations_use_no_tablespaces` が本番 script の mysqldump がすべて `--no-tablespaces` を付けることを
  確かめる）。使い始めるときは dump の option と source 側の account の権限を見直す（`--no-tablespaces` のままだと tablespace の
  定義が dump に入らず、復元先に同じ tablespace が無ければ import が失敗する）。`--no-tablespaces` と `--single-transaction`・
  `--source-data=2`・`--routines`・`--triggers`・`--events` の組合せを mysqldump 8.4.13 が受け付けることは CI（backup
  integration）で確認した（2026-11-21。この option の組合せの `backup-full.sh` の full が PASS）。
- dump role と binlog role の分離（account を分ける）は上線後に行う（2026-11-18 利用者決定）。現状は一つの account が上の表の
  全権限を持つ（`docker-compose.yml` の全 service が同じ `.env.backup` の `MYSQL_USER` を使う。分けるには service ごとの env が要る）。
- preflight が検査する権限は、書込み静止の要求の表への表単位の `INSERT`・`UPDATE` だけ（`QUIESCE_PROVIDER` が DB の provider の
  とき。無ければ exit 19）。他の権限は検査せず、不足は最初の full・checkpoint・archive の失敗で分かる（fail-closed）。integration は
  source 側を root で動かすため、この表で足りることは CI の integration では確かめられない（**本番相当の権限での実測は未取得**。DBA が
  本番の account で初回の full・checkpoint・archive を流して確かめる）。書込み静止の provider の SQL がこの表の権限（対象 DB の
  `SELECT` と要求の表への表単位の `INSERT`・`UPDATE`）だけで動き、業務の表と応答の表へは書けないことは、Java の MySQL 契約試験
  `BackupQuiesceProviderContractMySqlTest`（RB-QUIESCE BWQ-011。mysql-test の shard 3）が確かめる（Runner の証跡は未取得）。

#### restore target role の権限（RB-W P2-2。integration の `restore-svc` はこれだけで復元・検証する）

| 範囲 | 権限 | 理由 |
|---|---|---|
| 対象 DB（`<db>.*`） | `CREATE`・`DROP`・`ALTER`・`INDEX`・`REFERENCES`・`SELECT`・`INSERT`・`UPDATE`・`DELETE`・`LOCK TABLES` | dump の import（`REFERENCES` は外部キーの作成）、`ALTER DATABASE` による既定の照合順序の設定、復元検証 |
| 対象 DB | `CREATE VIEW`・`SHOW VIEW`・`TRIGGER`・`CREATE ROUTINE`・`ALTER ROUTINE`・`EXECUTE`・`EVENT` | dump の view・trigger・routine の再現。`SHOW VIEW`・`TRIGGER` は schema fingerprint で定義を読むためにも要る |
| `ses_recovery_control.*` | `ALL` | target guard の marker |
| global | `RELOAD` | 復元前の GTID・binlog のリセット |
| global | `SYSTEM_VARIABLES_ADMIN`・`REPLICATION_APPLIER` | binlog replay（session 変数・GTID の適用） |
| global | `SHOW_ROUTINE` | schema fingerprint で routine の本体を読む（無いと計算が失敗する） |
| global | DEFINER の再現: `SET_ANY_DEFINER`（MySQL 8.4。8.0 の `SET_USER_ID` は使わない。DEFINER の account が復元先に無い場合は `ALLOW_NONEXISTENT_DEFINER` も） | dump の `CREATE DEFINER=… TRIGGER / VIEW / FUNCTION` を元の DEFINER のまま作る。DEFINER を外して復元すると schema fingerprint が一致しない（DEFINER は照合の対象） |
| global | `SYSTEM_USER`（DEFINER の account が `SYSTEM_USER` を持つ場合だけ） | `SYSTEM_USER` を持つ account を DEFINER にした stored object を作るには、作る側も `SYSTEM_USER` が要る（MySQL の文書の規則からの推断。**実測は未取得**）。restore role に `SYSTEM_USER` を与えると system account を操作できるようになるため、DEFINER の account（migrator）には `SYSTEM_USER` を与えないことを推奨する |

前提（DBA が復元先で用意する。**DBA の確認は未取得**）: 復元先で binlog が有効なら `log_bin_trust_function_creators = ON`（restore role は
SUPER を持たないため。stored function と trigger の作成に要る）。DEFINER の account（production では migrator）を復元先に作るか、
`ALLOW_NONEXISTENT_DEFINER` を与える（account が無い trigger・view は実行時に失敗するため、作ることを推奨）。DEFINER の
account が `SYSTEM_USER` を持つなら restore role にも `SYSTEM_USER` が要る（上の表。持たせないことを推奨）。
復元先の MySQL server は backup 元と同じ版（patch を含む）・同じ `lower_case_table_names` にする（違うと schema fingerprint が
一致しない場合がある）。

- repository の S3 versioning/immutability を有効化し、通常 backup credential だけで全世代を削除できないようにする。
- 書込み静止（quiesce）の provider は version 管理された executable の `providers/quiesce-db.sh`（本番の定期の job）・
  `providers/quiesce-app-stopped.sh`（`--app-stopped` の明示だけ）・`providers/quiesce-local.sh`（隔離環境）の 3 つだけ（「設定」の
  「書込み静止の provider と設定」）。任意 `bash -c` の `APP_STOP_COMMAND` は受け付けない。
- 静止 protocol（`quiesce-db`。RB-QUIESCE）: DB の信号表で静止要求を受け渡し、MySQL `GET_LOCK('ses_backup_ddl_freeze')` を保持 process が
  持った上で、全ての app の instance の応答（排水の後の QUIESCED）を bounded deadline 内に確かめ、静止区間の終わりに verify する。
  app 側の正本は `.kiro/specs/backup-write-quiesce/design.md`。本番の Flyway job は同じ lock を callback（`ProductionBackupDdlFreezeFlywayCallback`）
  で取り、DBA の手作業の DDL も同じ lock を先に取る（deploy-runbook §3.3・§3.5）。`quiesce-local` は従来の file の protocol（全 replica の
  heartbeat fresh・scheduler ack・同じ lock の三者）。

## 復元

復元は **production source とは別の recovery target** へ行う。手順は `runbooks/restore-cutover.md`。
復元した DB に残る書込み静止の信号表の行の扱いと、新しい source での backup account と要求の表への表単位の権限の付け直し（backup を
再開する前に行う）は同 §4.6（RB-QUIESCE）。

```bash
# plan（read-only）: 要求時刻は RFC3339 UTC（末尾 Z）
./plan-restore.sh --target 2026-08-14T02:30:00Z

# apply は target guard と二者承認を通過した plan だけ
./restore.sh   --plan <plan-id> --approval <file1> --approval <file2>
./validate-restore.sh --plan <plan-id> --uploads-dir <staging> --smoke <script>
# uploads: 検証した staging を新しい版として本番の uploads の根へ複製する（今の版と pointer は変えない）
./uploads-cutover.sh stage --from <staging> --root <uploads の根> --version <新しい版>
# ここから先は app と scheduler を止めてから。順序の正本は runbooks/restore-cutover.md §4.5.1
# uploads: pointer を 1 回の rename で切り替え、status で確かめる（cutover.sh の前）
./uploads-cutover.sh switch --root <uploads の根> --to <新しい版> --expect <今の版>
./uploads-cutover.sh status --root <uploads の根>
# DB: read-only smoke は新しい DB と新しい版の組合せで行われる
./cutover.sh   --plan <plan-id> --approval <file1> --approval <file2>
# write-enable 前に戻すとき（手順は runbooks/restore-cutover.md §4.5.1 の 5）:
#  - cutover.sh が read-only smoke の失敗で exit 3（state=rolled-back）のときは rollback-cutover.sh を実行せず、
#    逆向きの switch だけを行い、OLD_ENV_SMOKE_SCRIPT を手で実行して旧環境を確かめる
#  - cutover.sh が state を書く前に失敗した（CUTOVER_STATE_FILE が無い）ときは DB は切り替わっていないので、
#    rollback-cutover.sh は使わず逆向きの switch だけを行う
#  - それ以外（staged / read-only-smoke-passed / single-writer）から戻すときは、次の 2 つを必ず対で行う
./rollback-cutover.sh --plan <plan-id>
./uploads-cutover.sh switch --root <uploads の根> --to <旧版> --expect <新しい版>
# app と scheduler を起動する前に、DB（CUTOVER_STATE_FILE の state・plan_id）と pointer（status）の対を確かめる
```

### uploads の版の切替え（`uploads-cutover.sh`。RB-W 追加修正 C）

- **stage**: `restore.sh` の `uploads_ready`（`validate-restore.sh` が hash と DB の参照を照合した staging）を、uploads の根の
  下の新しい版の directory（例 `v1`）に複製する。今の版・pointer・既にある名前は変えない（既にある名前は拒否）。複製は
  `lib/safe-extract.sh` の規則で根の下の一時 directory（`.stage-<版>.XXXXXX`）へ行い、全 entry の相対 path と SHA-256 が
  複製元と一致してから 1 回の rename で `<根>/<版>` にする。失敗したら一時 directory を消し、作りかけの版は現れない。
  `restore.sh` の出力の `uploads_backup_pointer` は backup した時の pointer と版で、新しい版はこれと別の名前にする。
- **switch**: pointer が `--expect` の版を指していることを確かめてから、切替え先を指す相対・1 段の一時 symlink を作り、
  `mv -T`（rename(2) 1 回）で pointer と置き換える。pointer は前後とも必ずどちらかの版を指し、無い瞬間は無い。app を止めて
  から行う（app は起動時に pointer の下の directory を作るため、切替えの途中で起動させない）。
- **rollback**: 逆向きの switch（`--to <旧版> --expect <新しい版>`）。旧版は消さないため 1 回の rename で戻る。switch が
  成功すると、そのまま使える rollback の command（`rollback=`）を出力する。`status` で pointer の指す版・版の一覧・
  残った一時 directory を確かめる（読むだけ）。
- **実行する主体**: restore 主体（uploads の access point を app と同じ uid で読み書きで mount する。backup の container は
  `:ro` のため実行できない）。umask 077 のため新しい directory は 0700 になるが、app と同じ uid のため読み書きできる。
- **残る窓**: stage の直前の確認と rename の間に、同じ名前の空の directory を作られると rename がそれを置き換える
  （directory の rename は、宛先が中身のある directory なら ENOTEMPTY、file・symlink なら ENOTDIR で失敗する）。switch の
  `--expect` の確認と rename の間に別の主体が pointer を変えると後の rename が勝つ。どちらも同じ change の中で 1 名が
  実行する前提で受け入れ、切替え後に `status` で確かめる。
- **順序**: DB の cutover・rollback と uploads の switch の順序、app・scheduler の停止、起動前の DB と pointer の対の確認は
  `runbooks/restore-cutover.md` §4.5.1 を正本とする（deploy-runbook §1.6 の11 (f) もそこを引く）。switch は `cutover.sh` の前、
  rollback は `rollback-cutover.sh` と逆向きの switch を対で行い、`write-enabled` の後は uploads も戻さない。
- **旧版と残りの削除**: `uploads-cutover.sh` は何も消さない。旧版の directory（`v<n>`）と `.stage-*`・`.<pointer>.switch-*` の
  削除は人手の物理削除で、deploy-runbook §4.4 の D15 の手順（保持・hold・DSAR の扱い）に従う。

`validate-restore.sh` は、従来の項目に加えて次を満たさなければ `READY_FOR_CUTOVER` を返さない:

- 復元 DB の `flyway_schema_history` を参照でき（無い・権限不足は FAIL）、成功行の版番号順の最大が
  checkpoint の `flyway_max_success` と一致すること。history table の無い DB を Flyway 有効なアプリで起動すると、
  `baseline-on-migrate` により管理外の既存 schema として baseline され、migration が誤って再適用され得るため（G-15）。
  照合を省略する設定は無い（report の `checks.flyway_history_required` は常に `true`）。
- checkpoint に内容ハッシュ（`content_hashes`）が記録されていれば、全 table で一致すること（`checks.content_hash`）。
  `CONTENT_HASH_TABLES` が設定されていれば、その全 table の記録があること（記録が無い・欠けている場合は FAIL）。
- checkpoint の schema fingerprint（`schema_fingerprint`）と、復元 DB で再計算した値が一致すること
  （`checks.schema_fingerprint`。設定によらず常に必須で、記録が無い `NOT_RECORDED`・算出方式の違い・不一致は FAIL）。

### schema fingerprint（G-04）

内容ハッシュは主要 table の行しか見ないため、table・列・索引・制約・trigger・routine・view の定義や DB の既定の
照合順序が backup 時と違う復元を検出できない。full / checkpoint は書込み静止区間内に、対象 DB の schema を
information_schema から正規化して SHA-256 にかけ（`lib/schema-fingerprint.sh`、algorithm `mysql-information-schema-sha256-v2`）、
metadata.json と index の `schema_fingerprint` に記録する。計算できなければ backup を VALID にしない。
あわせて DB の既定の文字集合・照合順序（`schema_fingerprint.database`）と、診断用に server の版・`lower_case_table_names`
（`schema_fingerprint.server`。hash には入れない）を記録する。`restore.sh` は復元 DB を `database` の値で作り
（`CREATE DATABASE … CHARACTER SET … COLLATE …` と、既にある空の DB への `ALTER DATABASE`）、記録が無い full は復元しない。
この確認は target を変える最初の操作（`RESET BINARY LOGS AND GTIDS`）より前に行い、記録が無ければ target を一切変えずに
止まる（RB-W P3-4。snapshot の取得・manifest と binlog の SHA の照合も同じく先に済ませる）。
mysqldump は、routine・trigger の `DATABASE_COLLATION` が DB の既定と違うと、その定義の前後に元の DB 名を書いた
`` ALTER DATABASE `<backup 時の DB 名>` … `` を出力する。`TARGET_DATABASE` が backup 時の DB 名と違うと、この文は
存在しない DB を指して import が失敗する（fail-closed）。この文が出るのは、source で routine・trigger を作った後に DB の
既定を変えた場合に限る（repository の migration に `ALTER DATABASE` / `ALTER SCHEMA` は無い）。復元先は backup 時と同じ
DB 名にする。
v1（r2・RB-W 初版。DB の既定値と `DATABASE_COLLATION` を含まない）の記録は「算出方式が異なります」で FAIL になる
（production に v1 の記録は無い。開発・試験の v1 の backup は取り直す）。

正規化の規則:

- 対象は接続先の `DATABASE()` の schema だけで、schema 名そのものは含めない（復元先の DB 名が違ってもよい）。
  外部キーの参照先が同じ schema なら `=` に、view 定義中の `` `<schema>`. `` は `` `=`. `` に置き換える。
- 1 行 = 1 要素（区分名 + 項目）。各項目は `IF(x IS NULL, 'NULL', QUOTE(x))` で文字列化し（SQL の NULL と文字列
  `'NULL'` を区別する）、行を `LC_ALL=C` の sort で並べてから、算出方式名の 1 行を先頭に置いて SHA-256 にかける。
  server が返す順序・照合順序には依存しない。区分ごとの件数と SHA-256 も記録し、不一致時は違う区分を表示する。
- 含めるもの: DB の既定の文字集合・照合順序（schema 区分。後から文字集合を書かずに作る table・routine が使う）、
  table（種別・engine・照合順序・`CREATE_OPTIONS`）、列（序数＝並び順・`COLUMN_TYPE`・NULL 可否・
  既定値・文字集合・照合順序・`EXTRA`・生成式・`SRS_ID`）、索引（一意性・列の序数・prefix 長・昇降順・種別・関数索引の式・
  可視性）、制約（種別・`ENFORCED`）、外部キーの列・参照先・`MATCH`/`ON UPDATE`/`ON DELETE`、CHECK の式、
  trigger（event・timing・`ACTION_ORDER`・本体・`SQL_MODE`・`DEFINER`・client の文字集合と照合順序・`DATABASE_COLLATION`）、
  routine と引数（本体・戻り値・`DETERMINISTIC`・`SQL DATA ACCESS`・`SQL SECURITY`・`SQL_MODE`・`DEFINER`・client の
  文字集合と照合順序・`DATABASE_COLLATION`）、view（定義・`CHECK OPTION`・`DEFINER`・`SQL SECURITY`・client の文字集合と照合順序）。
- 含めないもの: コメント（table・列・索引・routine）、`AUTO_INCREMENT` の現在値（`information_schema_stats_expiry` の
  cache で古い値が見え得る）・行数・容量などの統計・`CARDINALITY`、作成・更新日時、`PRIVILEGES`（照会した利用者の権限で
  変わる）、`COLUMN_KEY`（索引から導出）、`ROW_FORMAT` の実値（明示した値は `CREATE_OPTIONS` に入る）、
  event と partition の詳細（このアプリは event も table の partition も使わない。migration に `CREATE EVENT` と
  `PARTITION BY`（window 関数以外）は無い。`CREATE_OPTIONS` の `partitioned` は入る。使い始めるときは区分を足す）、
  利用者と権限（server 単位の設定で、DB の schema ではない）。
- 境界の期待値（`tests/schema-fingerprint-test.sh` と integration の実 MySQL で固定）: **列コメント・table コメントだけの
  変更では変わらない。列の並び順の変更では変わる**（`SELECT *` や列名の無い `INSERT` の意味が変わるため。column 区分だけが
  変わり、元に戻すと元の値に戻る）。索引の追加は index 区分、既定値の変更は column 区分、trigger の本体は trigger 区分、
  view の定義は view 区分、外部キーの `ON DELETE` は fk_rule 区分、DB の既定の照合順序は schema 区分だけが変わる。
- 次の場合は計算を失敗させる（fail-closed）: table が 1 つも見えない、DB の既定値が 1 行でない、routine の本体が NULL
  （照会する利用者に `SHOW_ROUTINE` が無い）、view の定義が空（対象 DB の `SHOW VIEW` が無い）、想定外の出力、mysql の失敗。
- 不一致の通知には、違う区分と、記録時と現在の server の版・`lower_case_table_names`・DB の既定値を並べて出す。
- 次の場合も不一致として FAIL になる（fail-closed）: backup 時と復元検証時で MySQL server の版（8.4 の patch を含む）や
  `lower_case_table_names` が違い information_schema の表示が変わる、trigger の本体などが schema 名を明示している状態で
  別名の DB へ復元する、復元検証の利用者に `TRIGGER` 権限が無く trigger が見えない（件数の違いとして現れる）。
  いずれも原因を直してから再検証する（照合を省略する設定は無い）。

`cutover.sh` と `restore-drill.sh` も validate の report を `jq` で検査し、`state == READY_FOR_CUTOVER` に加えて
`checks.flyway_history_required == true` かつ `checks.flyway == "PASS"`、および `checks.schema_fingerprint == "PASS"`
（RB-W P3-3）でなければ cutover / drill を失敗させる。

**禁止:** 稼働中 DB への in-place import、`CONFIRM_RESTORE=YES` 固定文字列での実行、
`--target` 表示のみの検証完了扱い、復元先 MySQL の時刻帯を確認しないままの cutover。

## 監視

`check-backup.sh --json` は最新 watermark（full/checkpoint/closed binlog の repository 到達点）と
source 現行 coordinate の差で判定する。古い file の存在だけでは FAIL にしない。

app 側の書込み静止の log（`errorCode=BACKUP_QUIESCE_*`）と metric（`ses_backup_quiesce_*`）の alert の条件と対応は
`runbooks/backup-health-monitoring.md` の「書込み静止（RB-QUIESCE）」。checkpoint が静止で続けて失敗すると、ここの checkpoint age が
30 分を超えて CRITICAL（RPO 不達）になる。

## retention（依存グラフに基づく削除）

- `retention.sh --dry-run`（変更なし。PITR 30 日 + 日次/週次/月次代表 + full-only）
- `retention.sh --apply --report <report> --approval <c1> --approval <c2>`
  （report 再計算で一致確認 → 二者承認 → maintenance lock → forget --prune）
- `RETENTION_ROLE=retention|admin` 以外では実行不可（writer は削除できない）
- archiver の初回起点 `FULL_COORDINATE_FILE` は `backup-full` が書き出す（.env.backup で backup-data 上の共有 path を指定。欠如時は archiver が fail する）
- restore target の provision に要る権限は「権限分離」の「restore target role の権限」の表を参照（DEFINER の再現と schema fingerprint の分を含む）
- key rotation は `rotate-key.sh --new-key-file <file>`（旧・新の両キーで restore verify 成功時のみ切替え）。手順は `runbooks/key-rotation.md`

## 演習（restore drill）

四半期ごと、および tool/image/restore 変更時に `restore-drill.sh` を隔離環境で実施する。source/target は別 container/network/credential とし、host port を公開しない。drill は plan → integrity → restore → validate（read-only smoke）→ cutover リハーサル（write-enable せず rollback）まで実 script で実行し、RPO/RTO segment 時間と evidence SHA を記録する。`mysqladmin ping` のみの代替確認は受け付けない。手順は `runbooks/restore-drill.md`、障害モード別対応は `runbooks/restore-failure-modes.md`。

## テスト

- unit: `tests/run-unit-tests.sh`（fake CLI に対する全 script の検証、Docker 必須。ツールイメージを build するため、
  Dockerfile の固定値が仮置きの値のままだと build で失敗する）
- integration: `tests/run-integration.sh`（digest 固定の実 MySQL 8.4.13 ×2 コンテナで実 PITR、before/after marker 照合、
  最小の `flyway_schema_history` による history 存在・version 照合、内容ハッシュ照合、schema fingerprint 照合と
  その正規化規則（コメントだけの変更・列の並び順・索引・既定値・trigger の本体・view の定義・外部キーの `ON DELETE`・
  DB の既定の照合順序）の実 MySQL での確認、migrator 相当の利用者が作った trigger・view・routine・外部キー・CHECK を
  restore role の権限だけで DEFINER ごと復元できること。CI の `backup-integration` job と同一）
- 書込み静止の integration（RB-QUIESCE BWQ-012）: app の代わりに DB の protocol を話す shell の responder
  （`tests/integration-responder.sh`。compose の service `responder`。app と同じ SQL を session の time_zone `+09:00` で実行する）を動かし、
  source に V202 の file を適用し、最初の full を `--app-stopped`、checkpoint を `quiesce-db` で取る。responder が応答の後に止まる負例で
  checkpoint が VALID にならないこと、STORED の生成列 `active_slot` の dump・復元・schema fingerprint の往復も確かめる。証跡の
  `quiesce-full.json`・`quiesce-checkpoint.json` は `quiesce.json` から host 名・接続元・`request_token` を外した投影（「書込み静止の記録」）、
  `full-dump-start-lock.txt`（mysqldump 8.4 の起点の lock の待ち）は記録だけで判定しない。
- **integration の backup account は root で、最小権限は確かめない**。preflight が表単位の GRANT の行を求めるため、本番と同じ要求の表への
  表単位の `GRANT INSERT, UPDATE` を root にも付けるが、root は全権限を持つ。provider の SQL が「source 側の account の権限」の表の権限
  だけで動くことは `BackupQuiesceProviderContractMySqlTest`（BWQ-011。mysql-test の shard 3）が確かめる。本番は専用の backup account
  （上の表の権限）に、deploy-runbook §3.4（Flyway の後）で表単位の GRANT を付けてから初回 backup を取る。responder の合成 user
  （`ses-int-app`）は信号表の表単位の権限だけを持ち、PROCESS を持たない（自分の接続だけを観測する）。
- 書込み静止の unit（tool image の中）: `tests/quiesce-db-test.sh`（fake の mysql で provider の手順・保持 process・verify・release・
  U-13 の中止・停止口）、`full-backup-test.sh`・`binlog-checkpoint-test.sh`（verify を解除の前に呼ぶ、verify の失敗で VALID にしない、
  `--app-stopped`、full の中止）、`preflight-test.sh`（exit 19）、`tool-image-test.sh`（bash 4.4 以上、`--app-stopped` の引数の受け渡し）、
  `script-contract-test.sh`（長い子 process の `{QUIESCE_HOLD_FD}>&-`、信号表の SQL に `NOW(` が無いこと等）。fake の mysql は provider の
  SQL を解釈しないため、SQL の判定（`VERDICT` の行）は `BackupQuiesceProviderContractMySqlTest` が同じ SQL file を実 MySQL 8.4 で実行して
  固定する。
- ローカルで CI と同じ範囲を確認する場合は `scripts/verify-like-ci.sh`
- unit の fake の限界（RB-W P3-4・P3-7）: fake の mysql は schema fingerprint の SQL を解釈せず、fake の docker は
  content ID の template を解釈しない。そのため「SQL から列（例: `column_default`）を消す」「template から項目
  （例: `.Config.Volumes`）を消す」変更は、unit では静的な検査（`case_schema_fp_sql_rule`・`case_static_image_contract`）
  だけが検出する。実際の挙動は integration の step 13（実 MySQL）と、実 Docker で VOLUME・EXPOSE・HEALTHCHECK・
  STOPSIGNAL だけを足した派生 image の content ID が変わることの確認（Runner。手動）で確かめる。
- script を書くときの規則（`tests/script-contract-test.sh` が本番の script・lib・providers・image を走査する。RB-W P1-1）:
  本番 script は `set -Eeuo pipefail` で動くため、パイプの後段に途中で読むのをやめ得る command（`grep -q`・`grep -m`・
  `head`・`exit` を含む awk・sed の `q`・`read`・`cmp`）を置かない。入力が pipe の容量（Linux 64 KiB）を超えると前段が
  SIGPIPE で終わり、パイプ全体が失敗に見える（例: binlog の一覧が長いと archive / checkpoint が止まる）。入力は
  here-string（`<<< "$x"`）で渡すか、読み切る command（`jq` の `first`、`tail` 等）を使う。
  行末の `|` で次の行に読み手を書く形、`| while … done` の中の `break`・`return`・`exit`、読み手の前の
  `LC_ALL=C` 等の代入・`command`・`env`、`|&`、`dd` の `count=`・`xxd -l` も同じ扱いで検出する（RB-W P3-1）。
  `common::trap_add` で登録した EXIT の handler は `common::trap_remove` で外し、`trap - EXIT` は書かない（RB-W P3-2。
  dispatcher だけが外れ、配列に残った handler が後の `trap_add` で戻るため）。
- uploads の pointer と版の切替えの試験（`tests/uploads-pointer-test.sh`・`tests/uploads-cutover-test.sh`）は symlink を
  作れる環境が要る。Linux（tool image）ではそのまま動き、Git for Windows では `MSYS=winsymlinks:sys` を設定して実行する
  （設定しないと `ln -s` が複製を作り、`ln -s ..` は親を再帰的に複製して終わらない。そのため両 suite は最初に
  `require_symlink_capable_environment`（`tests/lib/test-framework.sh`）で確かめ、満たさなければ以降の case を実行せずに失敗する）。
  `quiesce-lock-test.sh`・`full-backup-test.sh`・`binlog-checkpoint-test.sh`・`preflight-test.sh` も uploads の fixture に
  `ln -s v0 current` を使うため symlink が要る（この 4 つは前提を確かめず、symlink を作れない環境では pointer の解決の失敗として
  落ちる。止まりはしない。jq も要るため、通常は tool image の中で動かす）。

### integration の作業ディレクトリ（既定 WORK）の扱い

`tests/run-integration.sh` を既定パス（`tests/.integration-work`）で再実行すると、前回分を
`tests/.integration-work.prev-<前回 run ID>` へ退避する。

- 退避するのは、所有マーカー（`.ses-pitr-run-owner`）の `work=` が既定パスと一致し、前回 run の終了を確認できた場合だけ。
  前回 run が終了処理の最後に書く `.ses-pitr-run-ended` があれば、どの実行環境の run でも終了済みと判定する。
  無い場合は、所有マーカーの `pidns=`（PID 名前空間の識別子）・`owner=`（`uid:<id -u>`）・`session=`（Linux は `n/a`、
  Git for Windows / Cygwin は `win:<Windows の session ID>:<global|local>`）がすべて本 run と同じときだけ `kill -0` で判定する。
  Cygwin のプロセス表は Windows の session と昇格状態（`SeCreateGlobalPrivilege` の有無）で分かれ得るため、別の利用者・
  session・昇格状態の run の PID は `kill -0` で見えないことがある（RB-W W-6）。`kill -0` の失敗は `ESRCH`（C locale の
  `No such process`）だけを終了済みとし、`EPERM`（別の利用者のプロセスは存在する）やそれ以外の失敗は実行中として扱う。
  別の実行環境（コンテナ・WSL・別の MSYS・別の利用者・別の session 等）で作られた・識別子の無い旧形式のマーカーは
  判定できないため退避しない（表示される復旧手順に従って手動で退避する）。**RB-W より前の harness が残した既定 WORK は
  `owner=` / `session=` の無い旧形式のため、終了記録が無い場合は更新後の最初の実行で 1 回だけ手動の退避が必要になる。**
- 固定名のコンテナ（`ses-bkup-int-*`）が残っている場合は、WORK を判定・変更する前に失敗する。
- 所有マーカー・終了記録・合成 secret は、事前検査をせず `dd` の `conv=excl`（1 回の `open(O_CREAT|O_EXCL)`）で
  排他作成する。名前に何か（通常ファイル・symlink・FIFO・device・directory）があれば辿らず・止まらずに失敗するため、
  検査と作成の間の窓は無い（RB-W W-2）。`conv=excl` と `iflag=nonblock` を持つ `dd`（GNU coreutils）が無い環境では BLOCKED（2）。
- 失敗診断（`evidence/failure-diagnostics.txt`）を含まない退避分は直近 3 件を残して自動削除し、削除したディレクトリを表示する。
  失敗診断を含む退避分と、本 harness のマーカーを持たないディレクトリ（`.integration-work.manual-*` など）は自動削除しない。
