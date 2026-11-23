# NF07 Journal / Witness インスタンスの設定契約（W-6）と最小 SQL 権限の GRANT 設計案（W-12 / S-13）

状態: **設計案 R2（Plan Review 再提出用）。インスタンスの作成、DB user の作成、GRANT の実行、設定値の投入は行わない。**
作成日: 2026-11-14。基準: `1be03bea`。親計画: [`production-bridge-phased-plan.md`](production-bridge-phased-plan.md)（§3.3 NF07-A1J、§3.4.4、§4 B2）。
R2 の変更: Journal 接続を `DataSource` 型の Bean にしない（P2-7、§2.1）。権限の自己検査を information_schema ベースにし role を禁止（P3-7、§2.3）。Journal 用 Flyway job の profile・設定・設定 test（P3-7、§2.5）。pending の timeout と監視項目（P2-6・P3-6、§2.1・§2.6）。未使用 tenant の判定優先（P3-6、§2.3）。
R3 の変更（NF07-A1J 独立 Review `9d7d1b4e` の反映、2026-11-16）: 「append の timeout」の定義と `APPEND_TRANSACTION_TIMEOUT`（10秒）・socket timeout（20秒）の明記、`pending-timeout` の下限（P2-1、§2.1・§2.4）。principal は1つとする方式A（§2.1・§3.1）。起動失敗は prod に限ること・生の flag で判定すること（§2.1・§2.3）。URL 契約の追加（§2.1）。Flyway 履歴の checksum 照合（§2.3）。driver の接続失敗の秘匿（§2.6）。

R4 の注記（機能の開放 feature-enablement-console の FE-C03、2026-11-23）: 起動時検査は機能の開閉（生の `privacy.retention.enabled` を含む）を読まず、prod でも起動を止めない（WARN だけ）。結果は2つに分ける: (a) **hold の効力の結果**（`PrivacyJournalStartupVerifier.protectionResult`）は `nf07_journal_reader`・`nf07_journal_appender` のどちらの principal でも適合とし、起動時に検査して、再検査（`reverify`）は成功した時だけ更新する。`PrivacyHoldGate` と破壊的経路はこれを読むため、保持・保全の開閉・principal の種類・再検査の一時の失敗で既存 hold の効力確認が UNAVAILABLE にならない。(b) **開放の条件の結果**（`enablementResult`）は appender の権限を要し、保持・保全の開放の条件と hold の開始（append）がこれを読む（再検査の間隔の下限は `app.feature-enablement.journal-reverify-interval`、既定10分）。以下の本文の「retention flag ON（hold 開始を使う）」は (b)、「flag OFF（既存 hold の効力確認だけ）」は (a) と読み替え、「起動失敗」は「保持・保全を開けない（開いていれば自動閉鎖）」と読み替える。reader 方式（§2.1 方式A、§3.1 の reader 行）はそのまま使える。

インスタンスの配置・region・複製・RPO/RTO・運用者・鍵管理は G-DBA / G-BKP / D-03 の決定事項であり、本書では値を決めない（UNKNOWN）。実 GRANT は DBA が署名・実行する（G-DBA）。本書は、application がどの設定を受け取り、起動時に何を検査し、各 principal がどの権限だけを持つべきかを定める。合成層（`JournalTestConnectionCapability`、`W2bTestConnectionCapability`、`src/test/resources/privacy-journal/*.sql`）の権限分離を本番の形へ写したもので、合成層自体は変更しない。

---

## 1. 位置付け

| インスタンス | 役割 | 第1期 | 正本 |
|---|---|---|---|
| application DB（`ses_manager_db`） | 業務表、F1 表（policy・hold projection・DSAR） | 既存 | hold については projection（正本ではない） |
| Journal インスタンス | hold / dispatch 認可 / command の追記と CAS | NF07-A1J で接続 | hold の正本（SF-03a / SF-04a） |
| Witness インスタンス | 署名 checkpoint の追記と latest pointer | B2 で接続（第1期は設定しない） | restore 後の巻戻り検知の信頼点 |

3つは別 instance とする（同一 instance の別 schema では、instance 全体の restore で同時に巻き戻るため不可）。

---

## 2. W-6 設定契約

### 2.1 設定項目

| key | 必須になる条件 | 既定 | 検査 |
|---|---|---|---|
| `privacy.journal.datasource.url` | hold 開始を使うとき（retention flag ON） | 無し | `jdbc:mysql://` だけ。`autoReconnect=true` を含めば拒否（B-NF06-5 と同じ）。`sslMode` は `VERIFY_IDENTITY`（または DBA が決めた同等以上）必須。A1J の実装では加えて、複数 host・`address=(...)` 形式・URL 内の認証情報（`user@host`、query の `user=`・`password*=`）・`%` による符号化（driver は decode するが検査は decode しないため）・同じ parameter の重複・タイムゾーン parameter・`socketTimeout`/`connectTimeout` の上書きを拒否する。複数 host へ切り替える形は NF06 の `ImportJdbcReconnectGuard` と同じ範囲を拒否する（`:loadbalance`・`:replication`・`+srv`・`mysqlx`・MariaDB の scheme、host 部の `,`・`[h1,h2]`・`(`。host 部は `;` で終わらないので `h1;x,h2` も拒否。RB-NF07-HOLD で各形を `PrivacyJournalStartupVerifierTest` に固定）。`#`（fragment）も拒否する: driver は `#` 以降を捨てるため、`#` の後ろに置いた `sslMode` を検査だけが読み driver は読まない形を作れる（RB-NF07-HOLD） |
| `privacy.journal.datasource.username` / `password` | 同上 | 無し | secret store から注入。yml に既定値を書かない。log に出さない（driver の接続失敗の例外も固定文言に置き換え、principal・接続先を log と cause に残さない。§2.6） |
| `privacy.journal.datasource.max-pool-size` | — | 4 | 1〜8。application DB の pool とは別枠 |
| `privacy.journal.datasource.connection-timeout` | — | `PT5S` | 下限 `PT0.25S`、上限 `PT30S`。pool の接続待ちの上限（Connector/J の `connectTimeout` も同じ値） |
| （設定 key なし）socket timeout | — | 20秒（固定） | Connector/J の `socketTimeout`。append の transaction 上限（10秒）より長くして正常な statement を切らず、statement timeout の対象外の `COMMIT` と、statement timeout（別接続からの `KILL QUERY`）が届かない網分断での読み待ちを打ち切る |
| （設定 key なし）append の transaction 上限 | — | 10秒（固定。`ProductionPrivacyJournalAdapter.APPEND_TRANSACTION_TIMEOUT`） | append・照会の transaction timeout。各 statement の timeout はその残り時間から決まる（§2.4）。接続を得た後から数えるため、接続待ちは含まない |
| `privacy.journal.expected-schema` | 同上 | `privacy_journal` | 接続先 `DATABASE()` と一致 |
| `privacy.journal.journal-id` | 同上 | 無し | 形式 `[A-Za-z0-9._-]{4,64}`。`t_privacy_projection_state.journal_id` と一致 |
| `privacy.witness.datasource.*`（同じ形） | B2 の recovery を使うとき | 無し | 同上 |
| `privacy.witness.expected-schema` | 同上 | `privacy_witness` | 同上 |
| `privacy.clock.max-skew` | hold 開始を使うとき | 無し（P-ACT で決定） | 未設定なら hold 開始を `DECISION_UNKNOWN` で拒否 |
| `privacy.journal.pending-timeout` | hold 開始を使うとき | 無し（運用で決定） | 「append の timeout」より長いこと（下の定義）。reconciler が「Journal に command が無い」pending を消してよいまでの時間（production-bridge-phased-plan.md §3.4.4）。未設定なら reconciler は pending を消さない。満たさなければ起動時検査の契約違反（`pendingTimeout.notLongerThanAppendInFlight`） |

**「append の timeout」の定義**（A1J Review P2-1）: 1回の append が Journal で確定し得る時間の上限（在途時間の上限）であり、`connection-timeout`（pool の接続待ち。Spring の transaction timeout は接続を得た後から数えるため別に足す）+ append の transaction 上限 `APPEND_TRANSACTION_TIMEOUT` = 10秒 + socket timeout 20秒（statement timeout の対象外の `COMMIT`・網分断の余裕）とする。既定の `connection-timeout`（5秒）なら35秒で、`pending-timeout` はこれより長くする。holder の `appendInFlightUpperBound()` が同じ値を返す。上限を過ぎても server 側の確定を完全には否定できないため、reconciler は pending を消す直前に command ID で Journal を照会し、command が無いことを確かめる（§3.4.4。A1H で実装）。

Journal への接続は、journal 設定がある場合だけ作り、設定が無い間は接続を一度も試みない。journal 設定がある場合は flag とは独立に接続（pool）を作る（flag を OFF に戻しても、破壊的経路が既存 hold の効力を Journal で確認できるようにするため。production-bridge-phased-plan.md §3.4.4）。prod で retention flag ON（hold 開始を使う）なのに設定が欠けていれば起動失敗とする（OFF へ倒さない）。prod 以外では本番 adapter を生成できず Journal を使う経路が無いため、起動は止めずに警告だけを出す（test profile は既存 test の意味を保つため retention flag ON）。

**principal は1つ（総括決定 2026-11-16、方式A）**: application が Journal に使う principal は `privacy.journal.datasource.*` の1組だけとし、reader 用の別接続は作らない。retention flag ON（hold 開始を使う）では `nf07_journal_appender`（読み取りも同じ接続で行う）、flag OFF（既存 hold の効力確認だけ）では `nf07_journal_reader` または `nf07_journal_appender` を使う。どちらかは起動時検査が実際の権限で判定し（§2.3 の 4）、どちらの許可集合とも完全一致しなければ不適合とする。同じ process に2組の secret を持たせても分離の利益が小さいため。gate・projection の読み取りを reader に分ける（方式B）場合は、`privacy.journal.reader.*` の契約・2つ目の pool・2組目の権限検査を追加する設計変更として扱う。

接続は専用の holder 型（`PrivacyJournalConnections`）に閉じ込め、Spring には `DataSource` 型の Bean として公開しない。`DataSource` 型で登録すると、Spring Boot の `DataSourceAutoConfiguration`（`@ConditionalOnMissingBean(DataSource.class)`）が application 用の DataSource を作らなくなり、単一候補を前提にする `JdbcTemplate`・MyBatis-Plus 等の自動構成も退くか誤って結び付く。holder は内部の connection pool を自分で生成・破棄し、Journal adapter にだけ渡す。journal 設定ありの完全な context で application の mapper が application DB に接続したままであることを test で固定する（`PrivacyJournalConnectionsContextTest`）。Witness（B2）も同じ形にする。

### 2.2 インスタンスに求める性質

- 3 instance の `@@server_uuid` が互いに異なる（application DB と Journal、Journal と Witness、application DB と Witness）。
- `innodb_flush_log_at_trx_commit = 1`、`sync_binlog = 1`、`log_bin = ON`、`read_only = OFF`、`super_read_only = OFF`、`default_storage_engine = InnoDB`、全 journal 表が InnoDB（[`sf-w2-protocol.md`](sf-w2-protocol.md) §11.3 と同じ条件）。
- identity 列は binary NO PAD collation（合成 schema と同じ）。
- 接続は primary に限る。read replica、透過的な再接続 proxy、cache は使わない（sf-w2-protocol §3）。
- session time zone は `+00:00` に固定し、`TIMESTAMP(6)` とマイクロ秒正規化の hash 再計算を一致させる。
- Journal と Witness は application DB の restore 対象に含めない。Witness は Journal とも別に backup する（G-BKP）。

### 2.3 起動時検査（`PrivacyJournalStartupVerifier`）

journal 設定があるときだけ実行する。1つでも不適合・照会不能なら、prod の retention flag ON（hold 開始を使う）では起動失敗、flag OFF（既存 hold の効力確認だけ）と prod 以外では起動時に警告して `PrivacyHoldGate` が `UNAVAILABLE` を返す（app 全体は止めず、破壊的経路だけを止める。gate と hold 開始は profile に関係なく `startupResult()` が `AVAILABLE` でなければ `UNAVAILABLE` として扱う）。flag は決定ID束縛の有効期間を見ない生の `privacy.retention.enabled` で判定する（署名日前・失効で NF07 が OFF として扱われる間も、実行中に有効期間へ入れば hold 開始が使われ得るため、保守側に倒す）。各照会には query timeout（10秒）を置き、Journal が TCP を受けて応答しない場合も socket timeout で打ち切る。ただし `t_privacy_projection_state` に行が無い tenant（hold 機構を一度も使っていない tenant）は、この検査の結果に関係なく gate が「hold なし」を返す（production-bridge-phased-plan.md §3.4.4。未使用の tenant の削除を Journal の不調で止めない）。いずれも理由は固定文言で出す（接続文字列・user 名・password は出さない）。

1. 2.2 の `server_uuid` 相違。application DB の値は既存 datasource から読む。
2. 2.2 の耐久・engine 設定。
3. `DATABASE()` が `expected-schema` と一致する。
4. 自身の権限: `SHOW GRANTS` の文字列は解析しない（role・partial revoke・表記の揺れで壊れやすいため）。代わりに `information_schema.USER_PRIVILEGES` / `SCHEMA_PRIVILEGES` / `TABLE_PRIVILEGES` を `CURRENT_USER()` の grantee で読み、§3.1 の許可集合と表×操作の単位で突き合わせる。許可集合を超える権限（`DELETE`、`DROP`、`ALTER`、`CREATE`、`INDEX`、`SUPER`、global 権限、他 schema への書込）があれば失敗、必要な権限が欠けても失敗。これらの principal には role を使わない前提とし、`CURRENT_ROLE()` が `NONE` でなければ失敗する（role 経由の権限は上の表に現れないため）。判定できない場合も失敗として扱う。許可集合は principal の方式A（§2.1）に従い、retention flag ON では appender の集合、flag OFF では reader または appender の集合のどちらかと完全一致することを求める。routine・proxy の権限は information_schema に現れないため対象外（残余。DBA の GRANT 手順で持たせない）。
5. Journal 用 Flyway 履歴表（`flyway_schema_history_privacy_journal`）が artifact の migration と版・script 名・checksum（Flyway と同じ計算）で一致する（未適用・失敗行・artifact に無い版・適用後に書き換えられた script は失敗）。application は migration を実行しない（P-3）。
6. `t_privacy_projection_state` の `journal_id` が設定と一致する（別 journal への誤接続の検出）。

### 2.4 実行時の規則

- 状態変更は Journal 上の単一短 transaction で行い、外部 I/O を lock 保持中に行わない（合成 adapter と同じ）。
- ACK が不明な接続断は成功・失敗のどちらとも推定せず、同じ command ID で照会・再送する。
- statement / transaction timeout は残り時間から設定する（slice-d §7 の方針）。A1J の実装では append・照会の transaction 上限を `APPEND_TRANSACTION_TIMEOUT` = 10秒とし、各 statement の timeout はその残り時間から決まる。statement timeout の対象外の `COMMIT` と網分断は socket timeout（20秒）で打ち切る（§2.1）。
- Journal が使えない間、hold 開始は 503（`JOURNAL_UNAVAILABLE`）、`PrivacyHoldGate` は hold を使った tenant について `UNAVAILABLE`（gate 対象の batch はその回を skip、手動の文書処分は 409。production-bridge-phased-plan.md §3.4.1）。

### 2.5 DDL の置き場所と適用

| インスタンス | location | 履歴表 | 適用者 |
|---|---|---|---|
| application DB | `db/migration`（既存） | `flyway_schema_history` | 既存の専用 Flyway job（migrator account） |
| Journal | `db/privacy-journal`（新規） | `flyway_schema_history_privacy_journal` | 専用 Flyway job（`nf07_journal_migrator`） |
| Witness | `db/privacy-witness`（B2 で新規） | `flyway_schema_history_privacy_witness` | 専用 Flyway job（`nf07_witness_migrator`） |

版番号は location ごとに独立だが、各 location に `MigrationScriptIntegrityTest` と同型の重複・空 script 検査を置く（`PrivacyJournalMigrationIntegrityTest`）。DDL lane（wave3 §2）の対象は `db/migration/**` であり、`db/privacy-journal/**` は NF07-A1J の batch が単独で持つ。deploy 手順は `.kiro/specs/production-launch/deploy-runbook.md` に別節として追加する（NF07-A1J の範囲）。

Journal 用 Flyway job は application の job と混ぜない。application の job は `ProductionFlywayJobConfigurationTest` が locations をちょうど2つ（`classpath:db/migration` と prod 用 location）に固定し、`prod-migrate` profile だけが `flyway-prod.conf` を読むことを確かめているので、Journal には別の Maven profile（`privacy-journal-migrate`）と別の設定 file（`ops/flyway/flyway-privacy-journal.conf`）を用意する。設定は locations を `classpath:db/privacy-journal` だけ、履歴表を `flyway_schema_history_privacy_journal`、application の callback を持たない、接続情報・secret を file に書かない、とする。これを `PrivacyJournalFlywayJobConfigurationTest`（`ProductionFlywayJobConfigurationTest` と同型）で固定する。Witness（B2）も同じ形で別 profile・別 file にする。

### 2.6 secret と監視

- password は secret store から環境変数で注入し、POM・Maven property・コマンド引数に書かない（deploy-runbook §1.3 と同じ）。
- Connector/J の接続失敗の例外は、認証失敗なら user 名と接続元 host、通信失敗なら接続先を含み、Hikari はそれを log（接続 setup の失敗は ERROR）と `SQLTransientConnectionException` の cause に載せる。Journal の pool は driver 直結の `SanitizingJournalDriverDataSource` を通し、接続失敗を固定文言（SQLState と error code だけを残す。元の例外は cause にも付けない）に置き換える。principal と password は driver の接続 property にだけ持ち、Hikari の設定（username・URL）に入れない。`com.zaxxer.hikari` の log を DEBUG にしない（pool 設定の一覧が出る）。application DB の pool は従来どおりで、本節の対象外。
- 監視（G-INC / SRE）: Journal 接続失敗、ACK 不明の件数、pending が残っている時間、reconciler の判定結果、`PrivacyHoldGate` が `UNAVAILABLE` を返した回数と継続時間、projection の追随遅れ、破壊的経路ごとの「hold で skip した件数」に警告を出す。`UNAVAILABLE` の継続は期限超過の保持に直結するので、通報先と escalation 手順も決める。閾値と通報先は SRE が決める（UNKNOWN）。

---

## 3. W-12 / S-13 最小 SQL 権限の GRANT 設計案

### 3.1 principal 一覧

| インスタンス | principal（仮名） | 用途 | 許可（表単位） | 明示的に持たせないもの |
|---|---|---|---|---|
| application DB | 既存 app account | 業務、F1 表の読書き | schema 単位 `SELECT, INSERT, UPDATE, DELETE`（既存、deploy-runbook §1.2） | DDL。※強化案: `m_retention_policy`・`t_legal_hold`・`t_privacy_projection_state` から `DELETE` を外す（schema 単位の GRANT を表単位へ分解する必要があり、DBA が判断） |
| application DB | 既存 migrator account | application DB の Flyway | 既存（deploy-runbook §1.2） | application からの利用 |
| application DB | `nf07_recovery`（B2） | restore 後の projection 再構築 | `t_legal_hold`・`t_privacy_projection_state` に `SELECT, INSERT, UPDATE`、F1 の他表に `SELECT` | `DELETE`、DDL、他表への書込、fixture 操作 |
| Journal | `nf07_journal_appender` | hold 等の追記と CAS。retention flag ON の application は読み取りもこの principal で行う（§2.1 方式A） | `pj_head` に `SELECT, INSERT, UPDATE`、`pj_event`・`pj_command`・`pj_action`・`pj_lease` に `SELECT, INSERT`、履歴表に `SELECT` | `DELETE`、`UPDATE`（head 以外）、DDL、他 schema |
| Journal | `nf07_journal_reader` | retention flag OFF の application が既存 hold の効力確認に使ってよい principal（appender でもよい）。recovery（B2）の照会 | 全 journal 表に `SELECT`（schema 単位） | 書込全般 |
| Journal | `nf07_journal_migrator` | Journal の Flyway | `privacy_journal` の DDL と履歴表 | application からの利用（job 専用） |
| Witness（B2） | `nf07_witness_attestor` | 署名 checkpoint の追記と latest の CAS | `pw_checkpoint_log` に `SELECT, INSERT`、`pw_latest_checkpoint` に `SELECT, INSERT, UPDATE` | log の `UPDATE`・`DELETE`、DDL |
| Witness（B2） | `nf07_witness_reader` | recovery の照会 | 両表に `SELECT` | 書込全般 |
| Witness（B2） | `nf07_witness_migrator` | Witness の Flyway | `privacy_witness` の DDL と履歴表 | application からの利用 |
| 全インスタンス | fixture-admin | 試験の fixture 作成・restore | — | **本番には作らない**（合成試験専用） |

表名 `pj_*` / `pw_*` は合成 schema の `sf_journal_*` / `sf_w2b_checkpoint_log` / `sf_w2b_latest_checkpoint` の本番名（仮）。合成試験の権限（`JournalTestConnectionCapability:101-104`、`W2bTestConnectionCapability`）と同じ分け方である。

### 3.2 GRANT script 草案（実行しない。`<...>` は DBA が決める値）

```sql
-- Journal インスタンス（DBA が実行。application は実行しない）
CREATE USER 'nf07_journal_appender'@'<app-host>' IDENTIFIED BY '<secret>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE ON privacy_journal.pj_head    TO 'nf07_journal_appender'@'<app-host>';
GRANT SELECT, INSERT         ON privacy_journal.pj_event   TO 'nf07_journal_appender'@'<app-host>';
GRANT SELECT, INSERT         ON privacy_journal.pj_command TO 'nf07_journal_appender'@'<app-host>';
GRANT SELECT, INSERT         ON privacy_journal.pj_action  TO 'nf07_journal_appender'@'<app-host>';
GRANT SELECT, INSERT         ON privacy_journal.pj_lease   TO 'nf07_journal_appender'@'<app-host>';
GRANT SELECT ON privacy_journal.flyway_schema_history_privacy_journal TO 'nf07_journal_appender'@'<app-host>';

CREATE USER 'nf07_journal_reader'@'<app-host>' IDENTIFIED BY '<secret>' REQUIRE SSL;
GRANT SELECT ON privacy_journal.* TO 'nf07_journal_reader'@'<app-host>';

CREATE USER 'nf07_journal_migrator'@'<job-host>' IDENTIFIED BY '<secret>' REQUIRE SSL;
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
  ON privacy_journal.* TO 'nf07_journal_migrator'@'<job-host>';

-- Witness インスタンス（B2）
CREATE USER 'nf07_witness_attestor'@'<attestor-host>' IDENTIFIED BY '<secret>' REQUIRE SSL;
GRANT SELECT, INSERT         ON privacy_witness.pw_checkpoint_log    TO 'nf07_witness_attestor'@'<attestor-host>';
GRANT SELECT, INSERT, UPDATE ON privacy_witness.pw_latest_checkpoint TO 'nf07_witness_attestor'@'<attestor-host>';

CREATE USER 'nf07_witness_reader'@'<app-host>' IDENTIFIED BY '<secret>' REQUIRE SSL;
GRANT SELECT ON privacy_witness.pw_checkpoint_log    TO 'nf07_witness_reader'@'<app-host>';
GRANT SELECT ON privacy_witness.pw_latest_checkpoint TO 'nf07_witness_reader'@'<app-host>';

-- application DB（B2 の recovery）
CREATE USER 'nf07_recovery'@'<app-host>' IDENTIFIED BY '<secret>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE ON ses_manager_db.t_legal_hold               TO 'nf07_recovery'@'<app-host>';
GRANT SELECT, INSERT, UPDATE ON ses_manager_db.t_privacy_projection_state TO 'nf07_recovery'@'<app-host>';
GRANT SELECT ON ses_manager_db.m_retention_policy TO 'nf07_recovery'@'<app-host>';
```

### 3.3 実行時の自己検査

§2.3 の 4. で、application が使う各 principal の権限を information_schema の権限表から読み、§3.1 の許可集合と照合する。照合は「許可集合を超える権限が無いこと」と「必要な権限があること」の両方。DBA がインスタンス側で権限を変えた場合も次回起動で検出する。権限は role を経由させず user に直接 GRANT する（§3.2 も role を使わない）。role を使う運用にする場合は、自己検査の方式を DBA と決め直す。

### 3.4 合成 ACL 試験計画（Testcontainers）

| test class（案） | 内容 |
|---|---|
| `PrivacyJournalGrantMatrixMySqlTest`（`@Tag("mysql")`） | §3.2 の GRANT を Testcontainers の MySQL 8 に適用し、principal ごとに表×操作（SELECT/INSERT/UPDATE/DELETE/DDL/他 schema）の許可・拒否を全件確認する。appender の `pj_event` UPDATE・DELETE、reader の INSERT、全 principal の DDL が拒否されること |
| `PrivacyJournalStartupVerifierMySqlTest` | 過剰権限（例: appender に DELETE を足す）・権限不足・role 経由の権限・同一 `server_uuid`・耐久設定不適合をそれぞれ与え、起動検査が失敗すること |
| `PrivacyWitnessGrantMatrixMySqlTest`（B2） | attestor による log の UPDATE・DELETE 拒否、Journal/app 側 principal から Witness への書込拒否。slice-d §7.3 の recovery principal 全 matrix を含む |

各 class は `scripts/test-suites/mysql-shard-*.txt` にちょうど1回登録する（`MySqlTestShardInventoryTest`）。container と user は test が生成したものだけを使い、既存 DB・既存 user を再利用しない。

### 3.5 署名と実行の境界

- インスタンスの作成、user の作成、GRANT の実行、secret の発行は DBA / SRE / Security が行う（G-DBA、deploy-runbook §1.2〜§1.3 と同じ扱い）。
- 本書の principal 名・表名は仮であり、DBA の命名規則に合わせて変えてよい。変えた場合は §3.1 の許可集合と起動時検査の照合表を同時に更新する。
- 本書の承認は Plan Review の判定であり、DBA の署名を代行しない。
