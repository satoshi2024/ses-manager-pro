# backup の書込み静止（app 内の静止応答）要件

- 状態: **草案（第 1 段階: spec のみ。第 3 版: `32733d28` の review、`c2e18127` の差分 review、2026-11-21 の利用者の決定 U-1〜U-14、`9b8cc0a5` の差分 review を反映した第 4 版）**。独立 review の PASS 後に、別の指示で実装する。本書の段階では実装・試験の実行は無い。
- branch: `codex/snf-rb-quiesce-20261121`（起点 main `5ddddc2b`）。
- 関連する正本: `.kiro/specs/database-backup-recovery/`（HFP-03。RQ-002 と design §5）、`ops/backup/`、`.kiro/specs/production-launch/deploy-runbook.md`。
- 設計は `design.md`、実装の手順は `tasks.md`。利用者の決定は `design.md` §16（U-1〜U-14、全て決定済み）、review の指摘の対応先は `design.md` §20。

## 1. 背景（確認した事実）

- full（`ops/backup/backup-full.sh:131` の取得〜`:206` の解除。間に mysqldump と uploads の複製）と 15 分 checkpoint（`ops/backup/create-checkpoint.sh:130`〜`:187`。FLUSH BINARY LOGS、archiver の追従待ち、uploads の複製）は、整合点を取る前に書込みの静止を取得する。
- provider は `ops/backup/providers/quiesce-local.sh` だけで、自ら「隔離/ローカル環境向け」と書いている。protocol は file で、heartbeat の file が 1 つも無いと失敗する（`:64-66`）。振り分けの `ops/backup/lib/quiesce.sh` は `quiesce-local` しか知らない。
- app（`src/main`）には heartbeat・静止の ack・scheduler の ack のどれも実装が無い。CI の backup integration は compose の shell の loop が replica と scheduler の代わりをしている（`ops/backup/tests/docker-compose.integration.yml:72-109`）。
- `GET_LOCK('ses_backup_ddl_freeze')` は「規約」だけで、Flyway job も app も守る実装が無い。
- そのため本番の初回 backup（`deploy-runbook.md` §2 の5。§3 の完了後、§4 の app 起動の前）も、その後の full・checkpoint も失敗する。`database-backup-recovery/baseline.md` の HFP-03-PROD-004 は BLOCKED のまま。
- restore は VALID な checkpoint だけを実効復旧点にする（`ops/backup/plan-restore.sh:53-58`）。
- 配備の形: Fargate の単一 task（SRE-5）、全停止の配備、`APP_SCHEDULING_ENABLED`、ShedLock、本番 MySQL 8.4 LTS（8.4.13）。

## 2. 利用者の決定

2026-11-21 の最初の決定:

- **D-1 app 内の静止応答を実装する**: backup の要求を受けた app は scheduler を止め、更新の要求に 503 を返し、処理中の書込みが終わってから ack を返し、backup の取得後に再開する。**信号は DB の表で受け渡し、EFS の mount を増やさない**。
- **D-2 「app が停止していること」を確かめる方式を別に作る**（初回 backup 用）。
- **D-3 RPO 15 分・RTO 4 時間を維持する**（2026-11-13 の決定）。

2026-11-21 の追加の決定（`design.md` §16 の U-1〜U-14）: 静止の上限 checkpoint 300 秒・full 60 分（U-1）、PROCESS は誰にも与えない（U-2）、排水の上限 checkpoint 30 秒・full 600 秒（U-3）、CI は shell の responder と Java の契約試験（U-4）、timer の停止と Flyway の callback（U-5）、外部 API は `ExternalApiErrorWriter` の 503（U-6）、停止口は ticket と組でだけ（U-7）、logout は HTTP session を直ちに無効にし DB の記録は解除の後（U-8）、best-effort の監査は WARN の log（U-9）、download は 503（U-10）、既存の backup account に表単位の権限（U-11）、保存期間 30 日（U-12）、full の最中の新しい instance で full を中止（U-13）、idle の判定の持越し（U-14）。

## 3. 用語

| 用語 | 定義 |
|---|---|
| 信号表 | 静止の要求・instance の heartbeat・ack を受け渡す DB の表（`sys_backup_quiesce_request`・`sys_backup_quiesce_instance`・`sys_backup_quiesce_ack`）。 |
| 静止要求 | backup の provider が信号表へ登録する 1 回分の要求。行の番号（`id`）を epoch とする。 |
| 静止応答（responder） | app の中で信号表を読み書きし、書込み gate を開閉する部品（`BackupQuiesceResponder`）。heartbeat の thread と、要求を処理する塞がない状態機械の thread を持つ。 |
| 書込み gate | app の中の状態（UNARMED・OPEN・CLOSING・CLOSED・FENCED・SHUTDOWN・DISABLED）。OPEN・DISABLED 以外では permit の無い書込みを受け付けない（`BackupWriteGate`）。 |
| permit | 閉じる前に始まった書込み（HTTP の更新・job・async・起動時処理・解除後の書込み）を表す印。 |
| 排水（drain） | gate を閉じた後、permit と書込み可能な業務の接続が全て無くなるのを待つこと。 |
| ack | 排水が終わり gate を閉じたことを instance が信号表へ書いた記録（QUIESCED）。拒否は REFUSED、再開は RESUMED。 |
| lease | 静止要求の有効期限。保持 process が一定間隔で延長する。 |
| 保持 process | backup job 本体が持つ pipe を stdin に受けて動く process。DDL 凍結の lock の session を持ち、lease を延長し、full の最中の新しい instance を調べる。job 本体が終わると pipe の EOF で終わる。 |
| hold 期限 | app が ack に書く「この時刻より前には自動で再開しない」時刻（UTC）。 |
| 手元の期限 | app が自動で再開する時刻（単調時計）。DB に書き得た全ての hold 期限より前にならない。 |
| 必要な instance | 静止区間に書き得る instance（STOPPED でなく、heartbeat が停止済みの閾値より新しいか、要求の後に heartbeat を書いたもの）。 |
| 整合時刻 | provider が必要な全 instance の ack を確かめた時刻（`quiesce.json` の `started_at_utc`）。 |
| 停止口（DISABLED） | prod で変更 ticket と組で静止応答の gate を無効にした状態。登録と heartbeat は続け、backup は全て失敗する。 |
| 解除後の書込み | 静止中の logout の `t_user_session` の失効と監査のように、解除の後に書く記録（memory の一覧）。 |
| 停止確認 provider | app が動いていないことを確かめて backup を許す provider（`quiesce-app-stopped`）。 |
| 制御用・heartbeat 用の接続 | 信号表だけに使う小さな接続 pool。業務の接続 pool とは別で、静止中も書け、短い timeout を持つ。 |

## 4. スコープ

対象:

- app の静止応答（`src/main` の新しい package `com.ses.backup.quiesce` と、既存の scheduler・async・HTTP・logout・DataSource・storage・例外処理・停止の処理への接続）。
- 信号表の migration（V202）と H2 の試験 schema。
- backup tool の provider 2 つ（`quiesce-db`・`quiesce-app-stopped`）と保持 process、`lib/quiesce.sh`・`backup-full.sh`・`create-checkpoint.sh`・`lib/content-hash.sh`・`preflight.sh` の変更。
- Flyway job の DDL 凍結の callback。
- prod の起動時検査、task definition の雛形と照合 script、runbook・README・baseline の更新。
- 試験（unit・H2・MVC・MySQL Testcontainers・backup tool の shell 試験・CI の backup integration）。

対象外（本 batch では扱わない）:

- RPO・RTO の変更、uploads の複製方式の変更、多 tenant 化、EFS の mount の追加。
- 実 AWS の呼出し・実 credential。
- 長時間の job を途中で止める協調停止点。
- NF07 の Journal（別 instance。application DB の restore の対象外）。

## 5. 要件と受入基準

### BWQ-RQ-001: DB の信号表で静止を受け渡す

- 静止の要求・instance の heartbeat・ack を、`ses_manager_db` の 3 つの表で受け渡す。file・EFS は使わない。
- 表ごとに書く者を決める: 要求は provider、heartbeat と ack は app。app は要求の行を更新しない（保存期間を過ぎた行の削除だけをする）。
- 時刻は両側とも `UTC_TIMESTAMP(6)` だけで書き、比較も DB の中で行う。
- 同時に有効な要求は 1 件だけとする。有効枠（`active_slot`）は状態から生成する列とし、一意索引で強制する。
- 表は tenant 列を持たない。保存期間は 30 日（U-12）。
- migration は V202（実装の着手時と統合時に再確認し、衝突したら上の番号へ振り直す）。

受入基準:

- **BWQ-AC-001-01** V202 が空の MySQL 8.4 と H2 の両方で適用でき、`MigrationScriptIntegrityTest`・`DeployRunbookMigrationCountTest` が通る。H2 の試験 schema が V202 と同じ列・生成式を持つことを契約試験が固定する。
- **BWQ-AC-001-02** 有効な要求がある間に 2 件目の REQUESTED を登録すると一意索引で失敗する。`active_slot` へ値を直接書く文は拒否され、REQUESTED で `active_slot` が NULL の行は作れない。
- **BWQ-AC-001-03** 信号表を `CONTENT_HASH_TABLES` に指定すると backup が設定の誤りとして失敗する。
- **BWQ-AC-001-04** app の session と provider の session の `time_zone` が `+09:00` と `+00:00` で違っても、lease・hold 期限・heartbeat の判定が同じ結果になる（実 MySQL）。信号表の SQL に `NOW(`・`CURRENT_TIMESTAMP`・`LOCALTIMESTAMP` が無い。
- **BWQ-AC-001-05** 30 日より古い解除済みの要求とその ack、30 日より古い instance の行が 1 日 1 回消える。

### BWQ-RQ-002: app は全ての DB 書込みと uploads の file の変更を止め、排水してから ack する

- 止める対象は app の全ての DB 書込みと uploads の file の変更である: HTTP（更新の method、書込みを伴う既知の GET、bootstrap・break-glass の session、login）、`@Scheduled`（ShedLock の表を含む）、`@Async`、outbox・会計 worker、起動時の処理。
- 静止中の新しい更新の要求には 503 を返す。API は `ApiResult` の JSON（`code=503`、`Retry-After`、`X-SES-Error-Code: BACKUP_QUIESCED`）、画面は統一エラーページ、PWA の送信は queue に残して自動で再送させる。download は 503（U-10）。外部 API（NF05）は `ExternalApiErrorWriter` の 503（retryable・Retry-After）で、監査行は書かず抑止の metric だけを数え、NF05 を開ける前に NF05 owner の受理を得る（U-6）。
- 静止中の logout は、HTTP session を直ちに無効にして logout を終え、`t_user_session` の失効と logout の監査は解除の後に書く（U-8）。後で書く値（user・tenant・session の hash・時刻・correlation ID 等。接続元の IP は `t_audit_log` に列が無いため対象外）は logout の時点で取っておき、解除後の書込みの間に gate がまた閉じたら捨てずに次の解除へ回す。portal の logout（`/api/portal/auth/logout`。DB の session）は U-8 の対象外で、静止中は 503 にする。
- 参照の要求は止めない。静止中に permit の無い thread が借りる業務の DB 接続は読取り専用にする（最後の防線）。session の最終利用時刻の更新は省き、idle は gate が閉じた時刻で判定し、静止中の最終利用の時刻を解除の後へ持ち越す（U-14。緩和は最大保持時間まで）。静止中の best-effort の監査は DB へ書かず WARN の application log に残す（U-9）。
- scheduler の job は開始を解除まで待たせる（飛ばさない。SHUTDOWN・割込みでは待たない）。async の task は、閉じる前（または permit を持つ thread）の投入を排水の対象にし、閉じた後の投入は thread を待たせずに保留して、開いた時に投入し直す。
- permit と書込み可能な業務の接続の数は「先に増やしてから gate の状態を読む」順で扱い、gate は「先に閉じてから数を読み、確定の前に読み直す」。
- 排水が上限（checkpoint 30 秒・full 600 秒。U-3）までに終わらなければ ack を拒否（REFUSED）して gate を開け直し、排水を止めた処理を log に残す。
- 503 の文言は 4 つの message bundle に置き、それぞれ翻訳する。

受入基準:

- **BWQ-AC-002-01** gate が OPEN 以外の間、更新の method・既知の書込み GET・download・bootstrap/break-glass の session の要求は 503 になり、業務の表の行は増減しない。login の POST は認証の前に 503 になり、`sys_user.failed_count` は変わらない。portal と外部 API の filter chain でも同じで、外部 API の 503 は `ExternalApiErrorWriter` の形（correlation ID を含む）で、監査行は増えず抑止の metric が増える。
- **BWQ-AC-002-02**（HFP-03-AC-002-02 の app 側）実 MySQL 8.4 で、静止中に permit の無い thread が業務の接続 pool から試みた INSERT・UPDATE が成功しない（ERROR 1792）。静止の前に書いた marker は残り、解除の後に書いた marker は静止区間の DB に無い。
- **BWQ-AC-002-03** 閉じる前に始まった書込みと閉じる前に借りられた書込み可能な接続が終わるまで ack が書かれない。排水の上限を超えると REFUSED が書かれ、gate は OPEN に戻り、待っていた処理が log に出る。
- **BWQ-AC-002-04** 静止中に発火した `@Scheduled` は ShedLock の表へ書かずに待ち、解除の後に実行される。閉じた後に投入した async の task は Tomcat の thread を待たせない。
- **BWQ-AC-002-05** 静止中も readiness と liveness は UP のままである。
- **BWQ-AC-002-06** `MessageBundleConsistencyTest` が通り、新しい key は 4 つの bundle で別々に翻訳されている。
- **BWQ-AC-002-07** 多数の thread が業務の接続の借り返しと `setReadOnly` の切替えを続け、gate の開閉を繰り返す負荷の中で、ack を書いた後・開く前に permit の無い thread へ書込み可能な接続が渡されない（unit と実 MySQL）。
- **BWQ-AC-002-08** full の静止（最大保持時間が idle 期限より長い）の間に要求を続けた利用者の session が、静止の間も解除の後の最初の要求でも idle 期限の超過で失効せず、解除の後の touch で `idle_expires_at` が延びる。最大保持時間を超える緩和は無い。
- **BWQ-AC-002-09** 静止中の logout は成功し、同じ JSESSIONID は直ちに使えなくなる。解除の後に `t_user_session` の失効と `LOGOUT` の監査が DB に書かれる。監査の時刻・correlation ID は logout の時点の値である（接続元の IP は `t_audit_log` に列が無いため記録しない）。portal の logout は静止中 503 になる。静止中の best-effort の監査は DB に入らず WARN の log に出る。

### BWQ-RQ-003: 中断された静止を VALID にしない（安全側の自動再開）

- 一度 QUIESCED を書こうとした要求について、app が gate を開けてよいのは「非 QUIESCED の状態を DB に確定した」か「手元の期限に達した」場合だけとする。手元の期限は、新しい hold 期限を DB へ書く前に延ばし、縮めない。
- ack の書込みの結果が不明なら gate は閉じたままにし、REFUSED の確定か手元の期限で開く。
- 静止応答の thread は塞がれない: heartbeat は専用の thread と接続で書き、要求の処理は tick ごとに短い文を実行する状態機械にし、制御用の接続は短い lock 待ち・query・socket の timeout を持つ。
- backup の job が落ちても、更新の停止は lease（既定 60 秒）と最大保持時間の早い方で終わる。lease の延長は job 本体の pipe に結び付けた保持 process が行い、job 本体が終わると止まる。
- provider は、整合点の採取が終わった後・解除の前に、静止が途切れずに続いたことを DB の時刻で確かめる（verify）。確かめられなければ job を失敗させ、checkpoint・full を VALID にしない。
- 解除に失敗した場合は `database-backup-recovery/design.md:122` のとおり重大な alert とする。

受入基準:

- **BWQ-AC-003-01** lease の延長を止めると、app は手元の期限で再開し、ack に `RESUMED`（`LEASE_EXPIRED`）を書く。その後の verify は失敗する。
- **BWQ-AC-003-02** QUIESCED の INSERT が DB に commit された後に app へ例外が返る場合、app は gate を閉じたままにし、手元の期限より前に開かない。REFUSED の INSERT が成功する場合だけ、期限の前に開く。
- **BWQ-AC-003-03** hold 期限の延長の書込みが失敗しても、手元の期限は DB に確定し得る値より前にならない。
- **BWQ-AC-003-04** full の排水（最長 600 秒）の間や、REFUSED の INSERT が他の session の lock を待つ間も heartbeat は止まらず、FENCED にならない。
- **BWQ-AC-003-05** job 本体を kill すると保持 process が終わり、延長が止まり DDL lock が返る。acquire の process が終わっても延長は続く。
- **BWQ-AC-003-06** `backup-full.sh`・`create-checkpoint.sh` は verify の失敗で非 0 終了し、index に VALID を登録しない。
- **BWQ-AC-003-07** 解除の後、その時点で heartbeat が新しい必要な instance が `RESUMED` を書かない場合、provider は重大の message を出して非 0 終了する。区間の中で heartbeat が止まった instance は待たず WARN を出す。

### BWQ-RQ-004: instance の識別と生存を確かめる

- instance の ID は JVM の起動ごとに作る UUID とし、再利用しない。host 名、DB から見た接続元、版、閾値、最大保持時間を一緒に記録する。
- heartbeat は `@Scheduled` を使わない専用の thread で書く。heartbeat を self-fence の閾値（既定 20 秒）より長く書けなければ、app は自分で gate を閉じる。
- provider は要求を先に登録して commit し、その後で、待ちの毎回と verify で、新しい transaction で instance を分類し直す。必要な instance が 1 つ以上で、その全てに QUIESCED を求める。要求の前からの instance は整合時刻より前に ack していることを求める（要求の後に登録した instance だけは後からの ack を受け入れる）。
- app は ack の時・静止中の heartbeat の間隔ごと・provider が verify の前に求めた時に、同じ app account の接続を `information_schema.PROCESSLIST` で観測し、接続元の一覧を ack に書く。provider は verify の直前の観測が ack した instance の接続元だけであることを確かめる。`PROCESSLIST.HOST` だけ末尾の port を外し、`USER()` の host 部はそのまま使う。
- instance の行を STOPPED にするのは、業務の接続 pool を閉じた後だけとする。通常の停止と起動の失敗（refresh の失敗）の両方で行う。

受入基準:

- **BWQ-AC-004-01** graceful shutdown と起動の失敗のどちらでも、instance の行は業務の Hikari が閉じた後に `STOPPED` になる。停止の時に permit・書込み可能な接続が 10 秒で 0 にならなければ STOPPED は書かれない。prod と prod 以外の profile のどちらでも、破棄の順の依存が循環せず context が起動する。停止の記録が無い instance は、状態不明の閾値（既定 60 秒）〜停止済みの閾値（既定 300 秒）の間は ack が無ければ backup を失敗させ、それより古く要求の後の heartbeat も無ければ無視される。
- **BWQ-AC-004-02** heartbeat を書けない状態を self-fence の閾値より長く続けると、gate は FENCED になり更新の要求は 503 になる。
- **BWQ-AC-004-03** provider は、instance が申告した閾値が `QUIESCE_STALE_SECONDS`・lease と整合しない場合と、protocol の版が違う場合に失敗する。
- **BWQ-AC-004-04** 事前の確認と要求の登録の間に登録された instance も ack を求められる。登録の後に唯一の instance が止まり必要な instance が 0 になると失敗する。要求の前からの instance が整合時刻の後に ack すると verify は失敗する。
- **BWQ-AC-004-05** verify は provider が求めた後の観測で判定する。IPv6 の接続元（`PROCESSLIST.HOST` の `[::1]` 型や `fe80::1:port`、`USER()` の `fe80::1`）を正しく解釈する。

### BWQ-RQ-005: app の停止を確かめる provider（初回 backup・保守時間）

- `quiesce-app-stopped` は、APP_STOPPED の保持を先に登録して commit し、その後で信号表の instance が全て停止済みであることを確かめる。STOPPED でない停止口（DISABLED）の行は heartbeat の古さによらず失敗の理由にする（停止口の app は heartbeat が書けないまま書き続けている可能性があるため）。異常に止まった後は DBA が app の停止を確かめてから手の SQL でその行を STOPPED にする（RR-10 の例外の一覧に足す）。
- 保持が有効な間に起動した app は、port を開く前に起動を中止する（停止口の app も同じ）。動いている app が保持を読んだら gate を閉じて ERROR を出す。
- PROCESS は誰にも与えず、DBA の手の processlist の確認を runbook に残す（U-2）。
- 誤用を防ぐ: `backup-full.sh --app-stopped`（と `create-checkpoint.sh --app-stopped`）の明示の引数でだけ使い、環境変数では選べない。provider は repository の固定 executable で、任意の `bash -c` は受け付けない（`ops/backup/README.md:298`。origin/main `42719b10` の行番号）。

受入基準:

- **BWQ-AC-005-01** 新しい heartbeat（停止口の instance を含む）が 1 件でもあれば `--app-stopped` の full は静止を取得せずに失敗する。信号表が空なら取得できる。heartbeat が 300 秒より古くても STOPPED でない停止口の行があれば失敗し、片付けの SQL の後は取得できる。
- **BWQ-AC-005-02** APP_STOPPED の保持が有効な間に app を起動すると、ERROR の log を出して起動を中止する。保持の登録と app の登録が重なっても、どちらか一方が他方を見て失敗する。
- **BWQ-AC-005-03** `QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で指定した場合、full・checkpoint は始まらずに失敗する。

### BWQ-RQ-006: DDL・配備と backup を排他にする

- 配備の間（`deploy-runbook.md` §2〜§4）は backup の timer を止める（U-5）。
- 本番の Flyway job は migration の前に `GET_LOCK('ses_backup_ddl_freeze')` を取り、終わるまで保持する（U-5）。保持 process は静止を求める前に同じ lock を取る。

受入基準:

- **BWQ-AC-006-01** 実 MySQL で、別の session が lock を持つ間、Flyway job は migration を 1 件も適用せずに日本語の message で失敗する。lock が空いていれば適用し、終了後に lock を返す。
- **BWQ-AC-006-02** 保持 process が DDL lock を取れなければ静止要求を登録しない。

### BWQ-RQ-007: 権限を最小にし、付ける時期を決める

- 既存の backup の source account に、`sys_backup_quiesce_request` への表単位の `INSERT`・`UPDATE` だけを足す（U-11）。他の 2 表は既存の schema 単位の `SELECT` で読む。`DELETE`・`PROCESS` は足さない。PROD-002 に記録し、Security と DBA が署名する。
- 表単位の GRANT は §3 の Flyway job の後・初回 backup の前に行い、restore の cutover の後に付け直す。preflight が権限の有無を検査する。
- app account の権限は変えない。制御用・heartbeat 用の接続も同じ app account を使い、app account の host の型は 1 つにする。

受入基準:

- **BWQ-AC-007-01** 実 MySQL で、README の「source 側の account の権限」と同じ権限だけを持つ user で provider の SQL が全て成功し、業務の表への INSERT は拒否される。
- **BWQ-AC-007-02** `ops/backup/README.md`「権限分離」、`deploy-runbook.md` §1.2・§3.4・DBA-1・署名一覧、`ops/backup/runbooks/restore-cutover.md`、baseline の PROD-002 に同じ権限・時期・署名が書かれている。
- **BWQ-AC-007-03** 表単位の権限が無い account で preflight を実行すると exit 19 で止まる。

### BWQ-RQ-008: 設定・停止口と prod の起動時検査

- 設定は `app.backup-quiesce.*`（環境変数 `BACKUP_QUIESCE_*`）にまとめ、値の範囲と相互の関係を全 profile で起動時に検査する。
- prod の `enabled=false` は `BACKUP_QUIESCE_DISABLED_TICKET`（変更 ticket の形式）と組でだけ許し、ERROR と alert を出す（U-7）。停止口の間も instance の登録と heartbeat を続け（DISABLED）、provider は ONLINE・停止確認のどちらも失敗する。prod 以外の `enabled=false` は登録も heartbeat もしない。
- prod では `readOnlyPropagatesToServer=false` の JDBC 設定で起動を中止する。prod の判定は `Environment.acceptsProfiles` で行う。
- 起動時検査（範囲の検査を含む）を足す変更では、同じ変更で `deploy-runbook.md` §1.0、`infra/ecs/task-definition.prod.json`、`infra/ecs/offline/validate-task-definition.ps1` を更新する。照合 script は `startPeriod` と起動の待機の上限、`stopTimeout` と停止の所要時間の関係も確かめる。
- test profile では静止応答を無効にし、`application-test.yml` を使わない試験の context が背景の thread を動かさないことを静的に確かめる。

受入基準:

- **BWQ-AC-008-01** `readOnlyPropagatesToServer=false` を含む `DB_URL`（展開後の属性を含む）のとき、port を開く前に起動が止まる。prod で ticket の無い `enabled=false` は起動が止まり、ticket があれば DISABLED で起動し、ERROR の log と gauge が出て、instance の行の `gate_state` が `DISABLED` になる。
- **BWQ-AC-008-02** 雛形に `BACKUP_QUIESCE_ENABLED=true` と `stopTimeout` があり、照合 script は雛形・実値のどちらの mode でも、値が違う・ticket の無い false・secrets に置いた・正規名で渡した・範囲外・`startPeriod` や `stopTimeout` が足りない場合に FAIL する。

### BWQ-RQ-009: 観測できるようにする

- 日本語の log（固定の `errorCode=BACKUP_QUIESCE_*`）、metric（静止の回数・時間・排水の時間・拒否した要求の数・待たせた job の数・保留した task の数・解除後の書込みの未処理の数・最も古い permit の保持時間・heartbeat の失敗・停止口）、alert の条件を定める。

受入基準:

- **BWQ-AC-009-01** 静止 1 回ごとに、要求・ack（または拒否・結果不明）・再開が log に 1 行ずつ出て、`/actuator/prometheus` に `ses_backup_quiesce_*` の metric が出る。
- **BWQ-AC-009-02** 自動再開・FENCED・停止確認の保持の読取り・停止口は ERROR で、CloudWatch Logs の metric filter で数えられる固定の文字列を含む。REFUSED の log に、待っていた permit の持ち主と保持の時間が出る。

### BWQ-RQ-010: 静止時間に上限を置き、超えたときの挙動を決める

- 最大保持時間は checkpoint 300 秒・full 60 分（U-1）。超えると app は再開し、その backup は VALID にならない。checkpoint は uploads 約 3,000〜5,000 件まで持つ見込みで、本番の前に staging の EFS で uploads の snapshot の時間を測り、上限に届く時期を配備記録に書く。
- provider は静止区間の長さを記録し、警告の閾値（既定 60 秒）を超えたら WARN を出す。
- uploads の全量複製と mysqldump 全体が静止時間を決めることと、uploads が増えたときの限界を既知の制約として書く。

受入基準:

- **BWQ-AC-010-01** 最大保持時間を超える fixture で、app が自動で再開し、checkpoint が VALID にならず、`check-backup.sh` の RPO の判定が進まない。
- **BWQ-AC-010-02** checkpoint・full の結果の JSON と index に静止区間の秒数と内訳が残る。

### BWQ-RQ-011: 配備手順・runbook・baseline を合わせる

- `deploy-runbook.md`（§1.0、§1.2、§1.7、§2、§3・§3.4、§4、§5）、`ops/backup/README.md`、`ops/backup/runbooks/backup-health-monitoring.md`、`ops/backup/runbooks/restore-cutover.md`、`database-backup-recovery/baseline.md` の PROD-002・PROD-004、`privacy-retention-dsar/pii-inventory.md` を更新する。
- PROD-004 を、初回 backup の前に揃える inventory（004a）と、§4 の後の最初の ONLINE checkpoint の VALID の記録（004b）に分ける。
- full の最中に新しい instance が登録された時は provider が full を中止して静止を解き（U-13）、その日の full を手で取り直す手順を runbook に書く。

受入基準:

- **BWQ-AC-011-01** 初回 backup（§2 の5）が `--app-stopped` の full で、§3.4 の表単位の GRANT の後に書かれ、2 回目以降の配備の backup も同じ方式になっている。§2 の初めに backup の timer を止め、§4 の後に再開する手順がある。
- **BWQ-AC-011-02** baseline の PROD-004 が 004a・004b に分かれ、「Backup 開始の前提」の解除の条件が循環しない。
- **BWQ-AC-011-03** 欠けた full の取り直しの手順が README と監視の runbook にある。取り直しの期限（前日の full から 26 時間で full の CRITICAL になるため、当日のおよそ 04:00 まで）が書かれている。

### BWQ-RQ-012: 試験で固定する

- 各 task の試験は `tasks.md` に書く。実 MySQL の挙動は `@Tag("mysql")` の Testcontainers（`new MySQLContainer<>(MySqlTestImages.image())`）で確かめ、`scripts/test-suites/mysql-shard-{1,2,3}.txt` のどれかに入れる。
- backup tool の側は shell の試験で確かめ、CI の backup integration は DB の protocol を話す shell の responder で新しい provider を 1 回通す（U-4）。
- `@SchedulerLock` の method は primitive を返さない（CLAUDE.md）。

受入基準:

- **BWQ-AC-012-01** 既定の高速 suite・`-Pmysql-tests`・`-Pperformance-tests`・backup integration の 4 つの gate が skip 0 で通る（全 gate の実行の時期は統括が決める）。
- **BWQ-AC-012-02** `SchedulingDisabledDrainTest` の既知一覧に、静止応答の 2 つの thread と起動・停止の hook が理由つきで載っている。

### BWQ-RQ-013: uploads の file の変更を storage 層でも拒否する

- upload root の下の file を変える storage の入口は、gate が OPEN・DISABLED でなく permit の無い thread からの呼出しを例外で拒否する。
- main の code の file の変更の呼出しを 3 つの分類（file を変える・directory を作るだけ・upload root の外）で一覧にして試験で固定し、一覧に無い呼出しが増えたら失敗させる。

受入基準:

- **BWQ-AC-013-01** gate が閉じている間、permit の無い thread からの保存・移動・削除は例外になり、file は変わらない。permit を持つ thread は変えられる。
- **BWQ-AC-013-02** `UploadsMutationInventoryTest` が、既知の一覧に無い file の変更の呼出しを検出して失敗する。
- **BWQ-AC-013-03** prod の profile で context が起動する（初期化の間の directory の作成が拒否されない）。

### BWQ-RQ-014: ONLINE の full の最中の新しい instance で full を中止する（U-13）

- `mode=ONLINE` かつ `job_kind=FULL` の静止の間だけ、保持 process は 5 秒ごとに要求の後に登録された instance を調べ、見つけたら要求を `ABORTED`（`NEW_INSTANCE`）にして静止を解く。新しい instance は起動の待ちを終えて起動する。job は中止の印を見て mysqldump を止め、早く失敗する。APP_STOPPED の full（配備の前の backup）は中止しない（保持の間に起動した task は起動を中止し続ける）。

受入基準:

- **BWQ-AC-014-01** full の最中に新しい instance を登録すると、10 秒以内に要求が `ABORTED`（`NEW_INSTANCE`）になり、app の gate が開き、新しい instance が起動し、`backup-full.sh` は非 0 で終わり、WARN `BACKUP_QUIESCE_FULL_ABORTED` が出る。checkpoint の最中の新しい instance は ack して checkpoint は続く。release は ack の無い新しい instance の RESUMED を待たない（重大の誤報を出さない）。
- **BWQ-AC-014-02** APP_STOPPED の full の最中に起動しようとした task では、要求は `ABORTED` にならず、その task は起動を中止する。
