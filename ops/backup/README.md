# バックアップ運用手順（HFP-03）

## 概要

MySQL 8 の業務データと uploads を restic repository へ退避し、指定 UTC 時刻以前の
**検証済み整合 checkpoint** へ復元する。

### 復旧目標（RPO / RTO）

| 項目 | 目標値 | 定義 |
|---|---|---|
| RPO | **15 分** | incident / 要求時刻と実効復旧点（checkpoint）の差 |
| RTO | **4 時間** | incident 宣言から read-write 再開承認まで（plan・承認 / download・verify / DB replay / uploads / validation / cutover の区間合計） |

- 2026-11-13 の上線決定（B-NF09-4）で上記を正式な目標値とした。本リポジトリ内の他文書
  （`.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md` を含む）もこの値に揃えている。
- **DBA Lead / Operations の署名は未取得（PENDING）**。署名までは承認済みの SLA として扱わない。
- 代表データ量（HFP-03-PROD-007）の隔離演習（HFP-03-012）で実測するまでは「未達成」扱い。
  CI / integration の合成 fixture で記録される RPO 秒・RTO 秒は production の達成を意味しない。

**現状の注意:** production 固有値（HFP-03-PROD-001〜008）は未確定のため
[`.kiro/specs/database-backup-recovery/baseline.md`](../../.kiro/specs/database-backup-recovery/baseline.md) §4 で BLOCKED。
production 環境への接続・復元は行わないこと。

## 前提

- ツールイメージ（`ops/backup/Dockerfile`）: Oracle MySQL 8.0.46 client + mysqlbinlog + mysqldump + restic 0.17.3。
  private registry は使わず、検証済み build の content ID（image store に依存しない識別子）を記録して
  `docker save` / `docker load` で配布する（「ツールイメージの build・配布」参照）。
- MySQL サーバの時刻帯（2026-11-13 決定）: production の source と、復元先（recovery target）の MySQL サーバは
  いずれも **`time_zone = '+09:00'`**（DBA が設定）とする。復元先が異なる時刻帯だと、cutover 後のアプリの
  `NOW()` / `CURRENT_DATE` と TIMESTAMP 列の表示が変わるため、復元前に `SELECT @@global.time_zone;` で確認する。
  （内容ハッシュは session の time_zone を `+00:00` に固定して計算するため、この値に依存しない。）
- `.env.backup` に接続情報（secret 値は含めない）:
  `BACKUP_REPOSITORY`、`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`
- `secrets/mysql-password`（0600）: MySQL パスワード
- `secrets/restic-password`（0600）: restic repository パスワード
- `secrets/mysql-capath/` : hashed CA ディレクトリ（`openssl rehash` / `c_rehash` 済み）
  ※ MySQL 8.0.46 client は `--ssl-ca` + VERIFY_* が使えない（research.md §4 実測）。
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
`x-ses-backup-tool-content-id` とローカルの `ses-backup-tool:8.0.46` の content ID が一致しない
（または未 pin の）場合は起動を拒否する。`tool-image.sh compose` が渡すのは次の使い方だけで、
`-f`・`-p`・`--pull`・`--build`・`--entrypoint`・`-e`・`-v` などの compose 側 option、別の compose ファイル、
未定義の service は拒否する（終了コード 2）。

| 使い方 | pin 照合 |
|---|---|
| `run --rm <service> [コンテナへ渡す引数...]` | する |
| `up -d binlog` | する |
| `ps [<service>]`・`logs [--tail N] <service>`・`stop <service>` | しない（image を起動しない状態確認・停止） |

`<service>` は `preflight` / `backup` / `binlog` / `checkpoint` / `snapshot-binlog` / `check` / `retention`。

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
- **pin と照合には content ID を使う。** RootFS の layer diff ID 列（非圧縮 layer の digest）と image config の
  実行に関わる項目（Env / Entrypoint / Cmd / WorkingDir / User / Labels）から計算した SHA-256 で、image store に
  依存しない。build 機と配布先の image store が異なっても照合できる（`load` は違いを通知したうえで content ID で照合する）。

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
   - `Dockerfile` → `image/install-toolchain.sh` が、固定値の占位値（`UNPINNED`）を拒否し、MySQL APT 署名鍵の
     SHA-256・primary key が 1 つであること・primary key の full fingerprint・有効期限、restic の SHA-256
     （公式 `SHA256SUMS`）を検証する。1 つでも満たさなければ build が失敗する。lock に記載があれば完全一致も要求する。
   - MySQL APT 署名鍵（`RPM-GPG-KEY-mysql-2025`）の有効期限は **2027-10-23**。期限を過ぎると build が失敗するため、
     期限前に新しい鍵ファイルの SHA-256・fingerprint・期限を確認して `Dockerfile` を更新する。
   - `mysql-apt-config` deb は使わない（旧版 0.8.33-1 の SHA-256 を一次資料で確認できないため）。
     fingerprint を確認した鍵だけで keyring を作り、apt source を直接定義する。
   - MySQL client / server-core は exact version、base image は digest で固定する。tar / util-linux などの
     Essential パッケージは明示的には再インストールしない。lock が空なら Debian パッケージ版は記録だけを行う（build ログに WARN）。
   - `<evidence-dir-2>` に `tool-content-id.txt`（content ID）、`tool-image-id.txt`（image ID。記録のみ）、
     `tool-image-record.txt`（content ID・image ID・image store の種類・storage driver・Docker version）、
     `image-manifest/`（image 内の `toolchain.env` = 固定値、`dpkg-packages.tsv` = 全パッケージ版）を書く。既存の記録は上書きしない。
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

production の `docker-compose.yml` は `build:` を持たず、`pull_policy: never` で同名 image を registry から取得させない。

## 権限分離（RQ-008）

| role | DB 権限 | repository 権限 | 用途 |
|---|---|---|---|
| dump role | 対象 DB read、view/trigger/event/routine の最小権限 | writer（backup 可・delete 不可） | full/checkpoint |
| binlog role | `REPLICATION CLIENT`（+ checkpoint 用 rotation 権限は別 account） | writer | archive/snapshot |
| retention role | なし | **唯一** forget/prune 可（専用 maintenance 時のみ） | retention |
| restore target role | recovery target 新規 DB のみ（source 権限なし。`flyway_schema_history` を含む SELECT が必要） | read | restore/validate |

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

`cutover.sh` と `restore-drill.sh` も validate の report を `jq` で検査し、`state == READY_FOR_CUTOVER` に加えて
`checks.flyway_history_required == true` かつ `checks.flyway == "PASS"` でなければ cutover / drill を失敗させる。

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
- restore target の provision には `RELOAD`（RESET MASTER）・`SYSTEM_VARIABLES_ADMIN`・`REPLICATION_APPLIER`・`LOCK TABLES` が必要
- key rotation は `rotate-key.sh --new-key-file <file>`（旧・新の両キーで restore verify 成功時のみ切替え）。手順は `runbooks/key-rotation.md`

## 演習（restore drill）

四半期ごと、および tool/image/restore 変更時に `restore-drill.sh` を隔離環境で実施する。source/target は別 container/network/credential とし、host port を公開しない。drill は plan → integrity → restore → validate（read-only smoke）→ cutover リハーサル（write-enable せず rollback）まで実 script で実行し、RPO/RTO segment 時間と evidence SHA を記録する。`mysqladmin ping` のみの代替確認は受け付けない。手順は `runbooks/restore-drill.md`、障害モード別対応は `runbooks/restore-failure-modes.md`。

## テスト

- unit: `tests/run-unit-tests.sh`（fake CLI に対する全 script の検証、Docker 必須。ツールイメージを build するため、
  Dockerfile の固定値が占位値のままだと build で失敗する）
- integration: `tests/run-integration.sh`（digest 固定の実 MySQL 8.0.36 ×2 コンテナで実 PITR、before/after marker 照合、
  最小の `flyway_schema_history` による history 存在・version 照合、内容ハッシュ照合。CI の `backup-integration` job と同一）
- ローカルで CI と同じ範囲を確認する場合は `scripts/verify-like-ci.sh`

### integration の作業ディレクトリ（既定 WORK）の扱い

`tests/run-integration.sh` を既定パス（`tests/.integration-work`）で再実行すると、前回分を
`tests/.integration-work.prev-<前回 run ID>` へ退避する。

- 退避するのは、所有マーカー（`.ses-pitr-run-owner`）の `work=` が既定パスと一致し、前回 run の終了を確認できた場合だけ。
  前回 run が終了処理の最後に書く `.ses-pitr-run-ended` があれば、どの実行環境の run でも終了済みと判定する。
  無い場合は、所有マーカーの `pidns=`（PID 名前空間の識別子）が本 run と同じときだけ `kill -0` で判定し、
  別の実行環境（コンテナ・WSL・別の MSYS 等）で作られた・識別子の無い旧形式のマーカーは判定できないため退避しない
  （表示される復旧手順に従って手動で退避する）。**W1-B1 より前の harness が残した既定 WORK は旧形式のため、
  更新後の最初の実行で 1 回だけ手動の退避が必要になる。**
- 固定名のコンテナ（`ses-bkup-int-*`）が残っている場合は、WORK を判定・変更する前に失敗する。
- 失敗診断（`evidence/failure-diagnostics.txt`）を含まない退避分は直近 3 件を残して自動削除し、削除したディレクトリを表示する。
  失敗診断を含む退避分と、本 harness のマーカーを持たないディレクトリ（`.integration-work.manual-*` など）は自動削除しない。
