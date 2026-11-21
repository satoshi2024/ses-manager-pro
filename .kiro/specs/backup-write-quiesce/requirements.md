# backup の書込み静止（app 内の静止応答）要件

- 状態: **草案（第 1 段階: spec のみ）**。独立 review の PASS 後に、別の指示で実装する。本書の段階では実装・試験の実行は無い。
- branch: `codex/snf-rb-quiesce-20261121`（起点 main `5ddddc2b`）。
- 関連する正本: `.kiro/specs/database-backup-recovery/`（HFP-03。RQ-002 と design §5）、`ops/backup/`、`.kiro/specs/production-launch/deploy-runbook.md`。
- 設計は `design.md`、実装の手順は `tasks.md`。利用者の決定待ちの事項は `design.md` §16 にまとめ、ここでは「未決」と書いて参照だけする。

## 1. 背景（確認した事実）

- full（`ops/backup/backup-full.sh:131` の取得〜`:206` の解除。間に mysqldump と uploads の複製）と 15 分 checkpoint（`ops/backup/create-checkpoint.sh:130`〜`:187`。FLUSH BINARY LOGS、archiver の追従待ち、uploads の複製）は、整合点を取る前に書込みの静止を取得する。
- provider は `ops/backup/providers/quiesce-local.sh` だけで、自ら「隔離/ローカル環境向け」と書いている。protocol は file（`REPLICA_HEARTBEAT_DIR/<name>.heartbeat`・`.quiesce-requested`・`.quiesced`、`SCHEDULER_ACK_DIR/scheduler.stopped`）で、heartbeat の file が 1 つも無いと失敗する（`:64-66`）。振り分けの `ops/backup/lib/quiesce.sh` は `quiesce-local` しか知らない。
- app（`src/main`）には heartbeat・静止の ack・scheduler の ack のどれも実装が無い。CI の backup integration は compose の shell の loop が replica と scheduler の代わりをしている（`ops/backup/tests/docker-compose.integration.yml:72-109`）。
- `GET_LOCK('ses_backup_ddl_freeze')` は「規約」だけで、Flyway job も app も守る実装が無い。
- そのため本番の初回 backup（`deploy-runbook.md` §2 の5。§3 の完了後、§4 の app 起動の前で、instance は 1 つも無い）も、その後の full・checkpoint も失敗する。`database-backup-recovery/baseline.md:57` の HFP-03-PROD-004 は BLOCKED のまま。
- restore は VALID な checkpoint だけを実効復旧点にする（`ops/backup/plan-restore.sh:53-58`）。checkpoint が取れなければ PITR が成り立たない。
- 配備の形: Fargate の単一 task（SRE-5。rolling しない）、全停止の配備、`APP_SCHEDULING_ENABLED` による scheduler の停止、ShedLock、本番 MySQL 8.4 LTS（8.4.13）。

## 2. 利用者の決定（2026-11-21）

- **D-1 app 内の静止応答を実装する**: backup の要求を受けた app は scheduler を止め、更新の要求に 503 を返し、処理中の書込みが終わってから ack を返し、backup の取得後に再開する。**信号は DB の表で受け渡し、EFS の mount を増やさない**。
- **D-2 「app が停止していること」を確かめる方式を別に作る**: app がまだ起動していない初回 backup に使う。
- **D-3 RPO 15 分・RTO 4 時間を維持する**（2026-11-13 の決定）。

## 3. 用語

| 用語 | 定義 |
|---|---|
| 信号表 | 静止の要求・instance の heartbeat・ack を受け渡す DB の表（`sys_backup_quiesce_request`・`sys_backup_quiesce_instance`・`sys_backup_quiesce_ack`）。 |
| 静止要求 | backup の provider が信号表へ登録する 1 回分の要求。行の番号（`id`）を epoch とする。 |
| 静止応答（responder） | app の中で信号表を読み書きし、書込み gate を開閉する部品（`BackupQuiesceResponder`）。 |
| 書込み gate | app の中の状態（OPEN・CLOSING・CLOSED・FENCED）。OPEN 以外では新しい書込みを受け付けない（`BackupWriteGate`）。 |
| 排水（drain） | gate を閉じた後、閉じる前に始まった書込み（HTTP・scheduler・async・起動時処理）と、書込み可能な DB 接続が全て終わるのを待つこと。 |
| ack | 排水が終わり gate が CLOSED になったことを instance が信号表へ書いた記録。 |
| lease | 静止要求の有効期限。provider が一定間隔で延長する。延長が止まると期限切れになる。 |
| hold 期限 | app が ack に書いた「この時刻に自動で再開する」時刻（DB の時刻）。lease と app 側の上限（最大保持時間）の早い方。 |
| self-fence | app が heartbeat を一定時間書けないとき、自分で gate を閉じること。 |
| 停止確認 provider | app が動いていないことを確かめて backup を許す provider（`quiesce-app-stopped`）。初回 backup と保守時間に使う。 |
| 制御用 pool | 信号表だけに使う小さな接続 pool。業務の接続 pool とは別で、静止中も書ける。 |

## 4. スコープ

対象:

- app の静止応答（`src/main` の新しい package `com.ses.backup.quiesce` と、既存の scheduler・async・HTTP・DataSource・例外処理への接続）。
- 信号表の migration（V202）と H2 の試験 schema。
- backup tool の provider 2 つ（`quiesce-db`・`quiesce-app-stopped`）と、`lib/quiesce.sh`・`backup-full.sh`・`create-checkpoint.sh`・`lib/content-hash.sh`・`preflight.sh` の変更。
- Flyway job の DDL 凍結（利用者の決定による。`design.md` §16 の U-5）。
- prod の起動時検査、task definition の雛形と照合 script、runbook・README・baseline の更新。
- 試験（unit・H2・MVC・MySQL Testcontainers・backup tool の shell 試験・CI の backup integration）。

対象外（本 batch では扱わない）:

- RPO・RTO の変更、uploads の複製方式の変更（hardlink・volume snapshot・差分複製）、多 tenant 化、EFS の mount の追加。
- 実 AWS の呼出し・実 credential。
- 長時間の job を途中で止める協調停止点（`design.md` §16 の U-3 で後の batch とした場合）。

## 5. 要件と受入基準

### BWQ-RQ-001: DB の信号表で静止を受け渡す

- 静止の要求・instance の heartbeat・ack を、`ses_manager_db` の 3 つの表で受け渡す。file・EFS は使わない。
- 表ごとに書く者を 1 つに決める: 要求は provider、heartbeat と ack は app。app は要求の行を更新しない（保存期間を過ぎた行の削除だけをする）。
- 時刻は全て DB の `NOW(6)` で書き、期限の比較も DB の中で行う。JVM の時刻で書いた値と DB の時刻を比べない。
- 要求の行の番号を epoch とし、ack は要求ごとに別の行にする。古い要求の ack を新しい要求の ack と取り違えない。
- 同時に有効な要求は 1 件だけとする（一意索引で強制する）。
- 表は tenant 列を持たない（理由は `design.md` §3.4）。
- migration は V202（本書作成時点の main と全 branch で未使用。実装の着手時と統合時に再確認し、衝突したら上の番号へ振り直す。空き番号を埋めない）。

受入基準:

- **BWQ-AC-001-01** V202 が空の MySQL 8.4 と H2 の両方で適用でき、`MigrationScriptIntegrityTest`・`DeployRunbookMigrationCountTest` が通る。H2 の試験 schema（`application-test.yml` の `schema-locations` と `sql/engineer-schema-h2.sql`）が V202 と同じ列を持つことを契約試験が固定する。
- **BWQ-AC-001-02** 有効な要求がある間に 2 件目を登録すると一意索引で失敗する。状態と有効枠の組合せが矛盾する行は CHECK で拒否される。
- **BWQ-AC-001-03** 信号表を `CONTENT_HASH_TABLES` に指定すると backup が設定の誤りとして失敗する（`lib/content-hash.sh`）。

### BWQ-RQ-002: app は全ての DB 書込みを止め、排水してから ack する

- 止める対象は app の**全ての** DB 書込みと uploads の変更である: HTTP（更新の method、書込みを伴う既知の GET、bootstrap・break-glass の session、login・logout）、`@Scheduled`（ShedLock の表を含む）、`@Async`（取込の解析を含む）、outbox・会計 worker、起動時の処理。
- 新しい更新の要求には 503 を返す。API は `ApiResult` の JSON（`code=503`、header `Retry-After` と `X-SES-Error-Code: BACKUP_QUIESCED`）、画面は統一エラーページ、PWA の送信は既存の 5xx と同じく queue に残して自動で再送させる。
- 参照の要求（上の例外以外の GET・HEAD）は止めない。ただし静止中に借りる業務の DB 接続は読取り専用にし（最後の防線）、session の最終利用時刻の更新は省く。読取り専用に当たって失敗した書込みは 503 にする。
- scheduler の job は開始を解除まで待たせる（飛ばさない）。async の task は、gate を閉じる前に投入された分は排水の対象にし、閉じた後に投入された分は開始を待たせる。
- 排水が上限（checkpoint・full で別の値）までに終わらなければ ack を拒否（REFUSED）して gate を開け直す。backup は失敗し、整合しない snapshot は作られない。
- 503 の文言は 4 つの message bundle に置き、それぞれ翻訳する（日本語の値を他の bundle へ写さない）。

受入基準:

- **BWQ-AC-002-01** gate が CLOSING・CLOSED の間、更新の method・既知の書込み GET・bootstrap/break-glass の session の要求は 503 になり、業務の表の行は増減しない。login の POST は認証の前に 503 になり、`sys_user.failed_count` は変わらない。
- **BWQ-AC-002-02**（HFP-03-AC-002-02 の app 側）実 MySQL 8.4 で、静止中に業務の接続 pool から試みた INSERT・UPDATE が成功しない（MySQL の ERROR 1792）。静止の前に書いた marker は残り、解除の後に書いた marker は静止区間の DB に無い。
- **BWQ-AC-002-03** 閉じる前に始まった書込み（HTTP・job・async・起動時処理）が終わるまで ack が書かれない。排水の上限を超えると REFUSED が書かれ、gate は OPEN に戻る。
- **BWQ-AC-002-04** 静止中に発火した `@Scheduled` は ShedLock の表へ書かずに待ち、解除の後に実行される。cron の job が 1 日分飛ばない。
- **BWQ-AC-002-05** 静止中も readiness（`/actuator/health/readiness`）と liveness は UP のままである。
- **BWQ-AC-002-06** `MessageBundleConsistencyTest` が通り、新しい key は 4 つの bundle で別々に翻訳されている。

### BWQ-RQ-003: 中断された静止を VALID にしない（安全側の自動再開）

- app は hold 期限に達したら自動で再開し、ack に再開の理由を書く。backup の job が落ちても、更新の停止は lease（既定 60 秒）と最大保持時間の早い方で終わる。
- provider は、整合点の採取が終わった後・解除の前に、静止が途切れずに続いたことを DB の時刻で確かめる（verify）。確かめられなければ job を失敗させ、checkpoint・full を VALID にしない。
- 解除に失敗した場合は `database-backup-recovery/design.md:122` のとおり重大な alert とする。app 側は hold 期限で再開し、その事実を ERROR の log と metric に残す。

受入基準:

- **BWQ-AC-003-01** lease の延長を止めると、app は延長が止まった lease の期限で再開し、ack に `RESUMED`（`LEASE_EXPIRED`）を書く。その後の verify は失敗する。
- **BWQ-AC-003-02** 静止中に instance が再起動した・新しい instance が登録された・heartbeat が古くなった・同じ app account の接続元に ack していない host が現れた、のどれかで verify が失敗する。
- **BWQ-AC-003-03** `backup-full.sh`・`create-checkpoint.sh` は verify の失敗で非 0 終了し、index に VALID を登録しない。
- **BWQ-AC-003-04** 解除の後、app が `RESUMED` を書かない（再開を確かめられない）場合、provider は重大の message を出して非 0 終了する。

### BWQ-RQ-004: instance の識別と生存を確かめる

- instance の ID は JVM の起動ごとに作る UUID とし、再利用しない。host 名、DB から見た接続元（`USER()` の host 部）、版、heartbeat の間隔、self-fence の閾値を一緒に記録する。
- heartbeat は `@Scheduled` を使わない専用の thread で書く（`APP_SCHEDULING_ENABLED=false` でも動く）。
- heartbeat を self-fence の閾値（既定 20 秒）より長く書けなければ、app は自分で gate を閉じる。
- provider は heartbeat の古さで instance を 3 つに分ける: 新しい（静止を求める）・状態不明（backup を失敗させる）・停止済み（無視する）。止まった instance が残した古い行で backup が永久に止まることも、生きていて ack しない instance を黙って無視することも無いようにする。
- app は ack の時と静止中の heartbeat ごとに、同じ app account の接続を `information_schema.PROCESSLIST` で数え（PROCESS 権限なしで同じ user の thread が見える）、接続元の一覧を ack に書く。provider は、その一覧が ack した instance の接続元だけであることを確かめる。

受入基準:

- **BWQ-AC-004-01** graceful shutdown した instance の行は `STOPPED` になり、次の静止で待たれない。停止の記録が無い instance は、最後の heartbeat から状態不明の閾値（既定 60 秒）〜停止済みの閾値（既定 300 秒）の間は backup を失敗させ、それより古ければ無視される。
- **BWQ-AC-004-02** heartbeat を書けない状態を self-fence の閾値より長く続けると、gate は FENCED になり更新の要求は 503 になる。heartbeat が戻ると OPEN に戻る（有効な要求があれば閉じたまま）。
- **BWQ-AC-004-03** provider は、instance が申告した self-fence の閾値と heartbeat の間隔が `QUIESCE_STALE_SECONDS` と整合しない場合と、protocol の版が違う場合に失敗する。

### BWQ-RQ-005: app の停止を確かめる provider（初回 backup・保守時間）

- `quiesce-app-stopped` は、信号表の instance が全て停止済み（`STOPPED` か、停止済みの閾値より古い heartbeat）で、有効な静止要求が無いことを確かめ、APP_STOPPED の保持（lease 付きの要求）を登録する。
- 保持が有効な間に起動した app は、port を開く前に起動を中止する。
- 誤用を防ぐ: 環境変数（`.env.backup`）では選べず、`backup-full.sh --app-stopped`（と `create-checkpoint.sh --app-stopped`）の明示の引数でだけ使う。verify で、保持の間に instance の登録・heartbeat が無かったことを確かめる。
- provider は repository の固定 executable で、明示の引数だけを受け付ける。任意の `bash -c` は受け付けない（`ops/backup/README.md:298`）。

受入基準:

- **BWQ-AC-005-01** 新しい heartbeat が 1 件でもあれば `--app-stopped` の full は静止を取得せずに失敗する。信号表が空（app が一度も起動していない）なら取得できる。
- **BWQ-AC-005-02** APP_STOPPED の保持が有効な間に app を起動すると、ERROR の log を出して起動を中止する。保持が期限切れか解除済みなら起動する。
- **BWQ-AC-005-03** `QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で指定した場合、full・checkpoint は始まらずに失敗する。

### BWQ-RQ-006: DDL・配備と backup を排他にする

- 配備の間（`deploy-runbook.md` §2〜§4）は backup の timer を止める。
- 本番の Flyway job は migration の前に `GET_LOCK('ses_backup_ddl_freeze')` を取り、終わるまで保持する（実装するかは `design.md` §16 の U-5）。provider は静止を求める前に同じ lock を取る。

受入基準:

- **BWQ-AC-006-01**（U-5 で実装する場合）実 MySQL で、別の session が lock を持つ間、Flyway job は migration を 1 件も適用せずに日本語の message で失敗する。lock が空いていれば適用し、終了後に lock を返す。
- **BWQ-AC-006-02** provider は DDL lock を取れなければ静止要求を登録しない（app の更新を止めない）。

### BWQ-RQ-007: 権限を最小にする

- backup の source account に足す権限は、`sys_backup_quiesce_request` への表単位の `INSERT`・`UPDATE` だけとする。他の 2 表は既存の schema 単位の `SELECT` で読む。`DELETE`・`PROCESS` は足さない（`PROCESS` は U-2 の決定による）。
- app account の権限は変えない（schema 単位の DML で足りる）。制御用 pool も同じ app account を使う（別の user を作らない。SRE-5 の確認と NF06 の照合が同じ user を前提にする）。

受入基準:

- **BWQ-AC-007-01** 実 MySQL で、README の「source 側の account の権限」と同じ権限だけを持つ user で provider の SQL が全て成功し、業務の表への INSERT は拒否される。
- **BWQ-AC-007-02** `ops/backup/README.md`「権限分離」、`deploy-runbook.md` §1.2「backup 用の account」と DBA-1 に同じ権限が書かれている。

### BWQ-RQ-008: 設定と prod の起動時検査

- 設定は `app.backup-quiesce.*`（環境変数 `BACKUP_QUIESCE_*`）にまとめ、値の範囲と相互の関係を全 profile で起動時に検査する。
- prod では `app.backup-quiesce.enabled=false` と、業務の接続の読取り専用を server に伝えない JDBC 設定（`readOnlyPropagatesToServer=false`）で起動を中止する。prod の判定は `Environment.acceptsProfiles` で行う。
- 起動時検査を足す変更では、同じ変更で `deploy-runbook.md` §1.0、`infra/ecs/task-definition.prod.json`、`infra/ecs/offline/validate-task-definition.ps1` を更新する（CLAUDE.md の規則）。
- test profile では静止応答を無効にする（背景の thread が共有の試験 DB を書かない）。

受入基準:

- **BWQ-AC-008-01** prod で `enabled=false`、または `readOnlyPropagatesToServer=false` を含む `DB_URL` のとき、port を開く前に起動が止まる。
- **BWQ-AC-008-02** 雛形に `BACKUP_QUIESCE_ENABLED=true` があり、照合 script は雛形・実値のどちらの mode でも、値が違う・secrets に置いた・正規名（`APP_BACKUPQUIESCE_*` 等）で渡した場合に FAIL する。

### BWQ-RQ-009: 観測できるようにする

- 日本語の log（固定の `errorCode=BACKUP_QUIESCE_*` を含む）、metric（静止の回数・時間・排水の時間・拒否した要求の数・待たせた job の数・heartbeat の失敗）、alert の条件（自動再開・FENCED・連続の拒否）を定める。

受入基準:

- **BWQ-AC-009-01** 静止 1 回ごとに、要求・ack（または拒否）・再開が INFO/WARN/ERROR の log に 1 行ずつ出て、`/actuator/prometheus` に `ses_backup_quiesce_*` の metric が出る。
- **BWQ-AC-009-02** 自動再開と FENCED は ERROR で、CloudWatch Logs の metric filter で数えられる固定の文字列を含む。

### BWQ-RQ-010: 静止時間に上限を置き、超えたときの挙動を決める

- checkpoint・full のそれぞれに app 側の最大保持時間を置く（値は U-1）。超えると app は再開し、その backup は VALID にならない。
- provider は静止区間の長さを記録し、警告の閾値（既定 60 秒）を超えたら WARN を出す。
- uploads の全量複製（`providers/uploads-local.sh` の `cp -a`）が静止時間を決めることと、uploads が増えたときの限界を既知の制約として書く（方式の変更は本 batch の対象外）。

受入基準:

- **BWQ-AC-010-01** 最大保持時間を超える fixture で、app が自動で再開し、checkpoint が VALID にならず、`check-backup.sh` の RPO の判定が進まない。
- **BWQ-AC-010-02** checkpoint・full の結果の JSON と index に静止区間の秒数が残る。

### BWQ-RQ-011: 配備手順・runbook・baseline を合わせる

- `deploy-runbook.md`（§1.0、§1.2、§1.7「Backup 開始の前提」、§2、§3、§4）、`ops/backup/README.md`、`ops/backup/runbooks/backup-health-monitoring.md`、`ops/backup/runbooks/restore-cutover.md`、`database-backup-recovery/baseline.md` の PROD-004、`privacy-retention-dsar/pii-inventory.md` を更新する。
- PROD-004 は実装の完了後も、deployment owner の inventory が揃うまで BLOCKED とする。

受入基準:

- **BWQ-AC-011-01** 初回 backup（§2 の5）が `--app-stopped` の full で書かれ、2 回目以降の配備の backup も同じ方式になっている。§2 の初めに backup の timer を止め、§4 の後に再開する手順がある。
- **BWQ-AC-011-02** baseline の PROD-004 が「実装済み・deployment owner の inventory 待ち（BLOCKED）」になり、要る証跡が列挙されている。

### BWQ-RQ-012: 試験で固定する

- 各 task の試験は `tasks.md` に書く。実 MySQL の挙動（読取り専用の接続、lock、並行）は `@Tag("mysql")` の Testcontainers（`new MySQLContainer<>(MySqlTestImages.image())`）で確かめ、`scripts/test-suites/mysql-shard-{1,2,3}.txt` のどれかに入れる。
- backup tool の側は shell の試験（`ops/backup/tests/quiesce-lock-test.sh` と同じ形）で確かめ、CI の backup integration は新しい provider で DB の protocol を 1 回通す（方式は U-4）。
- `@SchedulerLock` の method は primitive を返さない（CLAUDE.md）。

受入基準:

- **BWQ-AC-012-01** 既定の高速 suite・`-Pmysql-tests`・`-Pperformance-tests`・backup integration の 4 つの gate が skip 0 で通る（全 gate の実行の時期は統括が決める）。
- **BWQ-AC-012-02** `SchedulingDisabledDrainTest` の既知一覧に、静止応答の thread と起動時の hook が理由つきで載っている。
