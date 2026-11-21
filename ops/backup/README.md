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
production 環境への接続・復元は行わないこと。

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
  `binlog_format` は 8.4 で非推奨（既定の ROW だけを使う）のため、integration の server 起動 option から外し、preflight は
  記録だけに使う。`SHOW MASTER STATUS` などの 8.4 で削除された構文は使わない（`tests/script-contract-test.sh` が本番 script と
  integration を走査する）。mysqldump 8.4.13 が `--source-data=2` で 8.4 の server に発行する構文（`SHOW BINARY LOG STATUS`
  の想定）と、その出力の形式は CI（実 MySQL 8.4.13 の integration）で確認する（未取得）。
  preflight の server の変数は `SHOW GLOBAL VARIABLES` で 1 つずつ有無を見て、無い変数は名前を挙げて失敗する。
  TLS は `@@have_ssl`（8.4 で残るかは CI で確認）を使わず、この接続の `Ssl_cipher` で判定する。
  mysqlbinlog の `--read-from-remote-server --raw --stop-never` と `MYSQL_PWD`（integration の root 操作だけが使う）が 8.4 でも
  使えることは CI（実 MySQL 8.4.13）で確認する（未取得）。
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

`FLYWAY_HISTORY_REQUIRED` は廃止した（W1-B1 r2）。`flyway_schema_history` の照合は常に必須で、
この変数が 1 以外の値で残っていると `validate-restore.sh` は検証を始めずに失敗する。

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
```
`MYSQL_PWD` 環境変数は使わない。秘密は 0600 の一時 option file 経由で渡す。

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

production の `docker-compose.yml` は `build:` を持たず、全 service が `pull_policy: never` で同名 image を registry から
取得させない（`check` が service ごとに確かめる）。

## 権限分離（RQ-008）

| role | DB 権限 | repository 権限 | 用途 |
|---|---|---|---|
| dump role | 下の「source 側の account の権限」のうち full・checkpoint の行（`SELECT`・`SHOW VIEW`・`TRIGGER`・`EVENT`・`SHOW_ROUTINE`・`RELOAD`・`REPLICATION CLIENT`） | writer（backup 可・delete 不可） | full/checkpoint |
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

要らないもの: `PROCESS`（mysqldump に `--no-tablespaces` を付けるため不要。付けないと mysqldump 8.0.21 以降は tablespace の情報を
読むのに `PROCESS` が要る。2026-11-18 利用者決定で上線前に付けた）、`LOCK TABLES`（`--single-transaction` のため table lock を
取らない）、`SUPER`、DDL 凍結の `GET_LOCK`（権限不要）、`SELECT @@server_uuid`・`SHOW GLOBAL VARIABLES`・`SHOW SESSION STATUS`
（権限不要）。

- `--no-tablespaces` の前提: production に入る migration（`src/main/resources/db/migration`・`db/migration-prod` の SQL と
  `src/main/java/db/migration` の Java migration）は `CREATE TABLESPACE` などの general tablespace を使わない
  （`tests/script-contract-test.sh` の `case_migrations_do_not_use_tablespace` が `TABLESPACE` の不在を確かめ、
  `case_mysqldump_invocations_use_no_tablespaces` が本番 script の mysqldump がすべて `--no-tablespaces` を付けることを
  確かめる）。使い始めるときは dump の option と source 側の account の権限を見直す（`--no-tablespaces` のままだと tablespace の
  定義が dump に入らず、復元先に同じ tablespace が無ければ import が失敗する）。`--no-tablespaces` と `--single-transaction`・
  `--source-data=2`・`--routines`・`--triggers`・`--events` の組合せを mysqldump 8.4.13 が受け付けることは CI（backup
  integration）で確認する（未取得）。
- dump role と binlog role の分離（account を分ける）は上線後に行う（2026-11-18 利用者決定）。現状は一つの account が上の表の
  全権限を持つ（`docker-compose.yml` の全 service が同じ `.env.backup` の `MYSQL_USER` を使う。分けるには service ごとの env が要る）。
- preflight は権限を検査しない。不足は最初の full・checkpoint・archive の失敗で分かる（fail-closed）。integration は source 側を
  root で動かすため、この表で足りることは CI でも確かめられない（**本番相当の権限での実測は未取得**。DBA が本番の account で
  初回の full・checkpoint・archive を流して確かめる）。

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
- 書込み静止（quiesce）は `providers/quiesce-local.sh`（version 管理された executable）のみ。任意 `bash -c` の `APP_STOP_COMMAND` は受け付けない。
- 静止 protocol: 全 replica の heartbeat fresh / scheduler ack / MySQL `GET_LOCK('ses_backup_ddl_freeze')` の三者を bounded deadline 内に確認。app の DDL/deploy は同じ lock 名を尊重する規約。

## 復元

復元は **production source とは別の recovery target** へ行う。手順は `runbooks/restore-cutover.md`。

```bash
# plan（read-only）: 要求時刻は RFC3339 UTC（末尾 Z）
./plan-restore.sh --target 2026-08-14T02:30:00Z

# apply は target guard と二者承認を通過した plan だけ
./restore.sh   --plan <plan-id> --approval <file1> --approval <file2>
./validate-restore.sh --plan <plan-id> --uploads-dir <staging> --smoke <script>
./cutover.sh   --plan <plan-id> --approval <file1> --approval <file2>
# rollback（write-enable 前のみ。旧環境の read-only smoke が PASS した場合だけ）
./rollback-cutover.sh --plan <plan-id>
```

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
