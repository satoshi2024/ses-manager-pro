# backup の書込み静止（app 内の静止応答）要件

- 状態: **草案（第 1 段階: spec のみ。第 2 版: `32733d28` の独立 review の指摘を反映）**。独立 review の PASS 後に、別の指示で実装する。本書の段階では実装・試験の実行は無い。
- branch: `codex/snf-rb-quiesce-20261121`（起点 main `5ddddc2b`）。
- 関連する正本: `.kiro/specs/database-backup-recovery/`（HFP-03。RQ-002 と design §5）、`ops/backup/`、`.kiro/specs/production-launch/deploy-runbook.md`。
- 設計は `design.md`、実装の手順は `tasks.md`。利用者の決定待ちの事項は `design.md` §16（U-1〜U-12）にまとめ、ここでは「U-n」と書いて参照だけする。review の指摘の対応先は `design.md` §20。

## 1. 背景（確認した事実）

- full（`ops/backup/backup-full.sh:131` の取得〜`:206` の解除。間に mysqldump と uploads の複製）と 15 分 checkpoint（`ops/backup/create-checkpoint.sh:130`〜`:187`。FLUSH BINARY LOGS、archiver の追従待ち、uploads の複製）は、整合点を取る前に書込みの静止を取得する。
- provider は `ops/backup/providers/quiesce-local.sh` だけで、自ら「隔離/ローカル環境向け」と書いている。protocol は file（`REPLICA_HEARTBEAT_DIR/<name>.heartbeat`・`.quiesce-requested`・`.quiesced`、`SCHEDULER_ACK_DIR/scheduler.stopped`）で、heartbeat の file が 1 つも無いと失敗する（`:64-66`）。振り分けの `ops/backup/lib/quiesce.sh` は `quiesce-local` しか知らない。
- app（`src/main`）には heartbeat・静止の ack・scheduler の ack のどれも実装が無い。CI の backup integration は compose の shell の loop が replica と scheduler の代わりをしている（`ops/backup/tests/docker-compose.integration.yml:72-109`）。
- `GET_LOCK('ses_backup_ddl_freeze')` は「規約」だけで、Flyway job も app も守る実装が無い。
- そのため本番の初回 backup（`deploy-runbook.md` §2 の5。§3 の完了後、§4 の app 起動の前で、instance は 1 つも無い）も、その後の full・checkpoint も失敗する。`database-backup-recovery/baseline.md` の HFP-03-PROD-004 は BLOCKED のまま。
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
| 書込み gate | app の中の状態（UNARMED・OPEN・CLOSING・CLOSED・FENCED・SHUTDOWN）。OPEN 以外では permit の無い書込みを受け付けない（`BackupWriteGate`）。 |
| permit | 閉じる前に始まった書込み（HTTP の更新・job・async・起動時処理）を表す印。permit を持つ処理は閉じた後も最後まで書ける。 |
| 排水（drain） | gate を閉じた後、permit と書込み可能な業務の接続が全て無くなるのを待つこと。 |
| ack | 排水が終わり gate を閉じたことを instance が信号表へ書いた記録（QUIESCED）。拒否は REFUSED、再開は RESUMED。 |
| lease | 静止要求の有効期限。provider が一定間隔で延長する。延長が止まると期限切れになる。 |
| hold 期限 | app が ack に書く「この時刻より前には自動で再開しない」時刻（UTC の DB の時刻）。lease と app 側の最大保持時間の早い方。 |
| 手元の期限 | app が自動で再開する時刻（単調時計）。DB に書き得た全ての hold 期限より前にならないように、書く前に延ばす。 |
| 必要な instance | 静止区間に書き得る instance（STOPPED でなく、heartbeat が停止済みの閾値より新しいか、要求の後に heartbeat を書いたもの）。provider はこの全てに QUIESCED を求める。 |
| self-fence | app が heartbeat を一定時間書けないとき、自分で gate を閉じること。 |
| 停止確認 provider | app が動いていないことを確かめて backup を許す provider（`quiesce-app-stopped`）。初回 backup と保守時間に使う。 |
| 制御用 pool | 信号表だけに使う小さな接続 pool。業務の接続 pool とは別で、静止中も書ける。 |

## 4. スコープ

対象:

- app の静止応答（`src/main` の新しい package `com.ses.backup.quiesce` と、既存の scheduler・async・HTTP・DataSource・storage・例外処理への接続）。
- 信号表の migration（V202）と H2 の試験 schema。
- backup tool の provider 2 つ（`quiesce-db`・`quiesce-app-stopped`）と、`lib/quiesce.sh`・`backup-full.sh`・`create-checkpoint.sh`・`lib/content-hash.sh`・`preflight.sh` の変更。
- Flyway job の DDL 凍結（U-5）。
- prod の起動時検査、task definition の雛形と照合 script、runbook・README・baseline の更新。
- 試験（unit・H2・MVC・MySQL Testcontainers・backup tool の shell 試験・CI の backup integration）。

対象外（本 batch では扱わない）:

- RPO・RTO の変更、uploads の複製方式の変更（hardlink・volume snapshot・差分複製）、多 tenant 化、EFS の mount の追加。
- 実 AWS の呼出し・実 credential。
- 長時間の job を途中で止める協調停止点（U-3 で後の batch とした場合）。
- NF07 の Journal（別 instance。application DB の restore の対象外）。

## 5. 要件と受入基準

### BWQ-RQ-001: DB の信号表で静止を受け渡す

- 静止の要求・instance の heartbeat・ack を、`ses_manager_db` の 3 つの表で受け渡す。file・EFS は使わない。
- 表ごとに書く者を決める: 要求は provider、heartbeat と ack は app。app は要求の行を更新しない（保存期間を過ぎた行の削除だけをする）。
- 時刻は両側とも `UTC_TIMESTAMP(6)` だけで書き、比較も DB の中で行う。`NOW()` など session の時刻帯に従う関数と、JVM の時刻で作った値を使わない。
- 要求の行の番号を epoch とし、ack は要求ごとに別の行にする。
- 同時に有効な要求は 1 件だけとする。有効枠（`active_slot`）は状態から生成する列とし、一意索引で強制する。
- 表は tenant 列を持たない（理由は `design.md` §3.4）。
- migration は V202（実装の着手時と統合時に再確認し、衝突したら上の番号へ振り直す。空き番号を埋めない）。

受入基準:

- **BWQ-AC-001-01** V202 が空の MySQL 8.4 と H2 の両方で適用でき、`MigrationScriptIntegrityTest`・`DeployRunbookMigrationCountTest` が通る。H2 の試験 schema（`application-test.yml` の `schema-locations` と `sql/engineer-schema-h2.sql`）が V202 と同じ列・生成式を持つことを契約試験が固定する。
- **BWQ-AC-001-02** 有効な要求がある間に 2 件目の REQUESTED を登録すると一意索引で失敗する。`active_slot` へ値を直接書く文は拒否され、REQUESTED で `active_slot` が NULL の行は作れない。
- **BWQ-AC-001-03** 信号表を `CONTENT_HASH_TABLES` に指定すると backup が設定の誤りとして失敗する（`lib/content-hash.sh`）。
- **BWQ-AC-001-04** app の session と provider の session の `time_zone` が `+09:00` と `+00:00` で違っても、lease・hold 期限・heartbeat の判定が同じ結果になる（実 MySQL）。信号表の SQL に `NOW(`・`CURRENT_TIMESTAMP`・`LOCALTIMESTAMP` が無い。

### BWQ-RQ-002: app は全ての DB 書込みと uploads の変更を止め、排水してから ack する

- 止める対象は app の**全ての** DB 書込みと uploads の変更である: HTTP（更新の method、書込みを伴う既知の GET、bootstrap・break-glass の session、login）、`@Scheduled`（ShedLock の表を含む）、`@Async`（取込の解析を含む）、outbox・会計 worker、起動時の処理。logout・download・外部 API の扱いは U-8・U-10・U-6 で決める。
- 新しい更新の要求には 503 を返す。API は `ApiResult` の JSON（`code=503`、header `Retry-After` と `X-SES-Error-Code: BACKUP_QUIESCED`）、画面は統一エラーページ、PWA の送信は既存の 5xx と同じく queue に残して自動で再送させる。
- 参照の要求は止めない。静止中に permit の無い thread が借りる業務の DB 接続は読取り専用にする（最後の防線）。session の最終利用時刻の更新は省き、idle 期限は gate が閉じた時刻で判定する。読取り専用に当たって失敗した書込みは 503 にする。
- scheduler の job は開始を解除まで待たせる（飛ばさない）。async の task は、閉じる前（または permit を持つ thread）の投入を排水の対象にし、閉じた後の投入は thread を待たせずに保留して、開いた時に投入し直す。
- permit と書込み可能な業務の接続の数は「先に増やしてから gate の状態を読む」順で扱い、gate は「先に閉じてから数を読み、確定の前に読み直す」。
- 排水が上限（checkpoint・full で別の値。U-3）までに終わらなければ ack を拒否（REFUSED）して gate を開け直す。backup は失敗し、整合しない snapshot は作られない。
- 503 の文言は 4 つの message bundle に置き、それぞれ翻訳する。

受入基準:

- **BWQ-AC-002-01** gate が OPEN 以外の間、更新の method・既知の書込み GET・bootstrap/break-glass の session の要求は 503 になり、業務の表の行は増減しない。login の POST は認証の前に 503 になり、`sys_user.failed_count` は変わらない。portal と外部 API の filter chain でも同じ（外部 API の形は U-6）。
- **BWQ-AC-002-02**（HFP-03-AC-002-02 の app 側）実 MySQL 8.4 で、静止中に permit の無い thread が業務の接続 pool から試みた INSERT・UPDATE が成功しない（ERROR 1792）。静止の前に書いた marker は残り、解除の後に書いた marker は静止区間の DB に無い。
- **BWQ-AC-002-03** 閉じる前に始まった書込み（HTTP・job・async・起動時処理）と閉じる前に借りられた書込み可能な接続が終わるまで ack が書かれない。排水の上限を超えると REFUSED が書かれ、gate は OPEN に戻る。
- **BWQ-AC-002-04** 静止中に発火した `@Scheduled` は ShedLock の表へ書かずに待ち、解除の後に実行される。cron の job が 1 日分飛ばない。閉じた後に投入した async の task は Tomcat の thread を待たせない。
- **BWQ-AC-002-05** 静止中も readiness（`/actuator/health/readiness`）と liveness は UP のままである。
- **BWQ-AC-002-06** `MessageBundleConsistencyTest` が通り、新しい key は 4 つの bundle で別々に翻訳されている。
- **BWQ-AC-002-07** 多数の thread が業務の接続の借り返しと `setReadOnly` の切替えを続け、gate の開閉を繰り返す負荷の中で、ack を書いた後・開く前に permit の無い thread へ書込み可能な接続が渡されない（unit と実 MySQL の両方）。
- **BWQ-AC-002-08** full の静止（最大保持時間が idle 期限より長い）の間に要求を続けた利用者の session が、idle 期限の超過で失効しない。

### BWQ-RQ-003: 中断された静止を VALID にしない（安全側の自動再開）

- 一度 QUIESCED を書こうとした要求について、app が gate を開けてよいのは「非 QUIESCED の状態（RESUMED・REFUSED）を DB に確定した」か「手元の期限に達した」場合だけとする。手元の期限は、新しい hold 期限を DB へ書く前に延ばし、縮めない。
- ack の書込みの結果が不明（例外・接続の切断）なら gate は閉じたままにし、REFUSED の確定（主キーの一意性で QUIESCED が確定していないことが分かる）か手元の期限で開く。
- backup の job が落ちても、更新の停止は lease（既定 60 秒）と最大保持時間の早い方で終わる。lease の延長の process は親の provider が消えたら止まる。
- provider は、整合点の採取が終わった後・解除の前に、静止が途切れずに続いたことを DB の時刻で確かめる（verify）。確かめられなければ job を失敗させ、checkpoint・full を VALID にしない。
- 解除に失敗した場合は `database-backup-recovery/design.md:122` のとおり重大な alert とする。

受入基準:

- **BWQ-AC-003-01** lease の延長を止めると、app は手元の期限で再開し、ack に `RESUMED`（`LEASE_EXPIRED`）を書く。その後の verify は失敗する。
- **BWQ-AC-003-02** QUIESCED の INSERT が DB に commit された後に app へ例外が返る場合、app は gate を閉じたままにし、手元の期限より前に開かない。REFUSED の INSERT が成功する場合（QUIESCED が確定していない）だけ、期限の前に開く。
- **BWQ-AC-003-03** hold 期限の延長の書込みが失敗しても、手元の期限は DB に確定し得る値より前にならない。
- **BWQ-AC-003-04** 静止中に instance が再起動・追加された（要求の登録の前後どちらでも）、heartbeat の無い接続元が現れた、のどれかで、その instance が QUIESCED を書いていなければ verify が失敗する。
- **BWQ-AC-003-05** `backup-full.sh`・`create-checkpoint.sh` は verify の失敗で非 0 終了し、index に VALID を登録しない。
- **BWQ-AC-003-06** 解除の後、app が `RESUMED` を書かない場合、provider は重大の message を出して非 0 終了する。

### BWQ-RQ-004: instance の識別と生存を確かめる

- instance の ID は JVM の起動ごとに作る UUID とし、再利用しない。host 名、DB から見た接続元（`USER()` の host 部）、版、読取り・heartbeat の間隔、self-fence の閾値を一緒に記録する。
- heartbeat は `@Scheduled` を使わない専用の thread で書く（`APP_SCHEDULING_ENABLED=false` でも動く）。
- heartbeat を self-fence の閾値（既定 20 秒）より長く書けなければ、app は自分で gate を閉じる。provider は `fence_after + poll_interval < lease − 延長間隔` を確かめる。
- provider は要求を先に登録して commit し、その後で、待ちの毎回と verify で、新しい transaction で instance を分類し直す。必要な instance の全てに QUIESCED を求める。止まった instance の古い行で backup が永久に止まることも、生きていて ack しない instance を黙って無視することも無いようにする。
- app は ack の時・静止中の heartbeat ごと・provider が verify の前に求めた時に、同じ app account の接続を `information_schema.PROCESSLIST` で観測し（PROCESS 権限なしで同じ account の thread が見える）、接続元の一覧を ack に書く。provider は verify の直前の観測が ack した instance の接続元だけであることを確かめる。host は末尾の port だけを外して解釈する。
- instance の行を STOPPED にするのは、業務の接続 pool を閉じた後だけとする。

受入基準:

- **BWQ-AC-004-01** graceful shutdown した instance の行は、業務の接続 pool が閉じた後に `STOPPED` になり、次の静止で待たれない。停止の記録が無い instance は、最後の heartbeat から状態不明の閾値（既定 60 秒）〜停止済みの閾値（既定 300 秒）の間は ack が無ければ backup を失敗させ、それより古く要求の後の heartbeat も無ければ無視される。
- **BWQ-AC-004-02** heartbeat を書けない状態を self-fence の閾値より長く続けると、gate は FENCED になり更新の要求は 503 になる。heartbeat が戻ると、有効な要求と ack の状態に応じた状態へ戻る。
- **BWQ-AC-004-03** provider は、instance が申告した閾値が `QUIESCE_STALE_SECONDS`・lease と整合しない場合と、protocol の版が違う場合に失敗する。
- **BWQ-AC-004-04** provider の事前の確認と要求の登録の間に登録された instance も、ack を求められる（fake の mysql と実 MySQL の両方）。
- **BWQ-AC-004-05** verify は、provider が求めた後の観測で判定する。IPv6 の接続元の host を正しく解釈する。

### BWQ-RQ-005: app の停止を確かめる provider（初回 backup・保守時間）

- `quiesce-app-stopped` は、APP_STOPPED の保持（lease 付きの要求）を先に登録して commit し、その後で信号表の instance が全て停止済み（`STOPPED`、または停止済みの閾値より古い heartbeat で要求の後の heartbeat も無い）であることを確かめる。
- 保持が有効な間に起動した app は、port を開く前に起動を中止する。動いている app が保持を読んだら gate を閉じて ERROR を出す。
- 誤用を防ぐ: 環境変数（`.env.backup`）では選べず、`backup-full.sh --app-stopped`（と `create-checkpoint.sh --app-stopped`）の明示の引数でだけ使う。verify で、保持の間に instance の登録・heartbeat が無かったことを確かめる。
- provider は repository の固定 executable で、明示の引数だけを受け付ける。任意の `bash -c` は受け付けない（`ops/backup/README.md:298`。origin/main `42719b10` の行番号）。

受入基準:

- **BWQ-AC-005-01** 新しい heartbeat が 1 件でもあれば `--app-stopped` の full は静止を取得せずに失敗する。信号表が空（app が一度も起動していない）なら取得できる。
- **BWQ-AC-005-02** APP_STOPPED の保持が有効な間に app を起動すると、ERROR の log を出して起動を中止する。保持が期限切れか解除済みなら起動する。保持の登録と app の登録が重なっても、どちらか一方が他方を見て失敗する。
- **BWQ-AC-005-03** `QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で指定した場合、full・checkpoint は始まらずに失敗する。

### BWQ-RQ-006: DDL・配備と backup を排他にする

- 配備の間（`deploy-runbook.md` §2〜§4）は backup の timer を止める。
- 本番の Flyway job は migration の前に `GET_LOCK('ses_backup_ddl_freeze')` を取り、終わるまで保持する（U-5）。provider は静止を求める前に同じ lock を取る。

受入基準:

- **BWQ-AC-006-01**（U-5 で実装する場合）実 MySQL で、別の session が lock を持つ間、Flyway job は migration を 1 件も適用せずに日本語の message で失敗する。lock が空いていれば適用し、終了後に lock を返す。
- **BWQ-AC-006-02** provider は DDL lock を取れなければ静止要求を登録しない（app の更新を止めない）。

### BWQ-RQ-007: 権限を最小にし、付ける時期を決める

- backup の source account に足す権限は、`sys_backup_quiesce_request` への表単位の `INSERT`・`UPDATE` だけとする。他の 2 表は既存の schema 単位の `SELECT` で読む。`DELETE`・`PROCESS` は足さない（`PROCESS` は U-2）。backup account が書き手になることの受理は U-11。
- 表単位の GRANT は §3 の Flyway job の後・初回 backup の前に行い、restore の cutover の後に付け直す。preflight が権限の有無を検査する。
- app account の権限は変えない。制御用 pool も同じ app account を使い、app account の host の型は 1 つにする（PROCESSLIST で同じ account の thread だけが見えるため）。

受入基準:

- **BWQ-AC-007-01** 実 MySQL で、README の「source 側の account の権限」と同じ権限だけを持つ user で provider の SQL が全て成功し、業務の表への INSERT は拒否される。
- **BWQ-AC-007-02** `ops/backup/README.md`「権限分離」、`deploy-runbook.md` §1.2「backup 用の account」・§3.4・DBA-1、`ops/backup/runbooks/restore-cutover.md` に同じ権限と付ける時期が書かれている。
- **BWQ-AC-007-03** 表単位の権限が無い account で preflight を実行すると exit 19 で止まる。

### BWQ-RQ-008: 設定と prod の起動時検査

- 設定は `app.backup-quiesce.*`（環境変数 `BACKUP_QUIESCE_*`）にまとめ、値の範囲と相互の関係を全 profile で起動時に検査する。
- prod では、業務の接続の読取り専用を server に伝えない JDBC 設定（`readOnlyPropagatesToServer=false`）で起動を中止する。`app.backup-quiesce.enabled=false` の扱いは U-7 で決める（推奨は変更 ticket の明示と組でだけ許す）。prod の判定は `Environment.acceptsProfiles` で行う。
- 起動時検査（範囲の検査を含む）を足す変更では、同じ変更で `deploy-runbook.md` §1.0、`infra/ecs/task-definition.prod.json`、`infra/ecs/offline/validate-task-definition.ps1` を更新する（CLAUDE.md の規則）。照合 script は `startPeriod` と起動の待機の上限の関係も確かめる。
- test profile では静止応答を無効にする。`application-test.yml` を使わない試験の context が背景の thread を動かさないことを静的に確かめる。

受入基準:

- **BWQ-AC-008-01** `readOnlyPropagatesToServer=false` を含む `DB_URL`（Connector/J の展開後の属性を含む）のとき、port を開く前に起動が止まる。U-7 の決定どおりに `enabled=false` が扱われる。
- **BWQ-AC-008-02** 雛形に `BACKUP_QUIESCE_ENABLED=true` があり、照合 script は雛形・実値のどちらの mode でも、値が違う・secrets に置いた・正規名（`APP_BACKUPQUIESCE_*` 等）で渡した・範囲外・`startPeriod` が足りない場合に FAIL する。

### BWQ-RQ-009: 観測できるようにする

- 日本語の log（固定の `errorCode=BACKUP_QUIESCE_*` を含む）、metric（静止の回数・時間・排水の時間・拒否した要求の数・待たせた job の数・保留した task の数・最も古い permit の保持時間・heartbeat の失敗）、alert の条件を定める。

受入基準:

- **BWQ-AC-009-01** 静止 1 回ごとに、要求・ack（または拒否・結果不明）・再開が INFO/WARN/ERROR の log に 1 行ずつ出て、`/actuator/prometheus` に `ses_backup_quiesce_*` の metric が出る。
- **BWQ-AC-009-02** 自動再開・FENCED・停止確認の保持の読取りは ERROR で、CloudWatch Logs の metric filter で数えられる固定の文字列を含む。REFUSED の log に、待っていた permit の持ち主と保持の時間が出る。

### BWQ-RQ-010: 静止時間に上限を置き、超えたときの挙動を決める

- checkpoint・full のそれぞれに app 側の最大保持時間を置く（U-1）。超えると app は再開し、その backup は VALID にならない。U-1 を決める前に、staging の EFS で uploads の snapshot の時間を測る。
- provider は静止区間の長さを記録し、警告の閾値（既定 60 秒）を超えたら WARN を出す。
- uploads の全量複製と mysqldump 全体が静止時間を決めることと、uploads が増えたときの限界を既知の制約として書く（方式の変更は本 batch の対象外）。

受入基準:

- **BWQ-AC-010-01** 最大保持時間を超える fixture で、app が自動で再開し、checkpoint が VALID にならず、`check-backup.sh` の RPO の判定が進まない。
- **BWQ-AC-010-02** checkpoint・full の結果の JSON と index に静止区間の秒数と内訳が残る。

### BWQ-RQ-011: 配備手順・runbook・baseline を合わせる

- `deploy-runbook.md`（§1.0、§1.2、§1.7「Backup 開始の前提」、§2、§3・§3.4、§4、§5）、`ops/backup/README.md`、`ops/backup/runbooks/backup-health-monitoring.md`、`ops/backup/runbooks/restore-cutover.md`、`database-backup-recovery/baseline.md` の PROD-002・PROD-004、`privacy-retention-dsar/pii-inventory.md` を更新する。
- PROD-004 を、初回 backup の前に揃える inventory（004a）と、§4 の後の最初の ONLINE checkpoint の VALID の記録（004b）に分ける。実装の完了後も、それぞれの証跡が揃うまで BLOCKED とする。

受入基準:

- **BWQ-AC-011-01** 初回 backup（§2 の5）が `--app-stopped` の full で、§3.4 の表単位の GRANT の後に書かれ、2 回目以降の配備の backup も同じ方式になっている。§2 の初めに backup の timer を止め、§4 の後に再開する手順がある。
- **BWQ-AC-011-02** baseline の PROD-004 が 004a・004b に分かれ、「Backup 開始の前提」の解除の条件が 004a だけを初回 backup の前に求め、004b を §4 の後に求める（循環しない）。

### BWQ-RQ-012: 試験で固定する

- 各 task の試験は `tasks.md` に書く。実 MySQL の挙動（読取り専用の接続、lock、並行、結果不明の commit、時刻帯）は `@Tag("mysql")` の Testcontainers（`new MySQLContainer<>(MySqlTestImages.image())`）で確かめ、`scripts/test-suites/mysql-shard-{1,2,3}.txt` のどれかに入れる。
- backup tool の側は shell の試験（`ops/backup/tests/quiesce-lock-test.sh` と同じ形）で確かめ、CI の backup integration は新しい provider で DB の protocol を 1 回通す（U-4）。
- `@SchedulerLock` の method は primitive を返さない（CLAUDE.md）。

受入基準:

- **BWQ-AC-012-01** 既定の高速 suite・`-Pmysql-tests`・`-Pperformance-tests`・backup integration の 4 つの gate が skip 0 で通る（全 gate の実行の時期は統括が決める）。
- **BWQ-AC-012-02** `SchedulingDisabledDrainTest` の既知一覧に、静止応答の thread と起動時の hook が理由つきで載っている。

### BWQ-RQ-013: uploads の変更を storage 層でも拒否する

- upload root の下を変える storage の入口は、gate が OPEN でなく permit の無い thread からの呼出しを例外で拒否する（DB の書込みを伴わない file の変更（取込の台帳・stage の削除など）にも最後の防線を置く）。
- main の code の file の変更の呼出しを一覧にして試験で固定し、一覧に無い呼出しが増えたら失敗させる。

受入基準:

- **BWQ-AC-013-01** gate が閉じている間、permit の無い thread からの保存・移動・削除（`FileStorageServiceImpl`・`LocalDocumentStorage`・`ImportStageReconciliationLedger` 等）は例外になり、file は変わらない。permit を持つ thread は変えられる。
- **BWQ-AC-013-02** `UploadsMutationInventoryTest` が、既知の一覧に無い file の変更の呼出しを検出して失敗する。
