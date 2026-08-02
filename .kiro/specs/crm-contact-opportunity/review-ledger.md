# Review Ledger — CRM複数担当者・商機管理 (S08)

## 進捗サマリ

| Task | 状態 | 備考 |
|---|---|---|
| T048 / F1 | **完了** | DDL/移行/entity/定向testに加え、T050顧客detailで移行contact表示Demo成立 |
| T049 / F2 | **実装完了** | 状態CAS/楽観ロック、終端更新拒否、受注→案件/見積の冪等変換、forecast排他を実装。定向7件＋H2統合2件PASS。Docker MySQL smokeはM/release gate |
| T050 / A1 | **完了** | contact CRUD/期間CAS、timeline、請求宛先、退職者除外、PII mask/export、390pxを実装・定向回帰済み |
| T051 / A2 | **完了** | lead/opportunity UI、冪等転換、D&D rollback、定向回帰PASS |
| T052 / B1 | **完了** | CRM KPI、scope/funnel/forecast排他、定向回帰PASS |
| T053 / M | **完了** | Round3 baseline 2件を修正し、L4全量green、MySQL smoke、desktop/390px browser Demoを確認 |

---

## Round 1 独立Review = FAIL への対応（2026-08-01）

Round 1（Base `5bdfb34` → Head `d9a1d02`, branch `feature/crm-contact-opportunity`）は
**P0=2 / P1=3 / P2=4 / NOTE=1** でFAIL。本ledgerの旧記述（「H2 schema replay ＝ 構造検証済み」等）は
実態と乖離していたため、以下のとおり**訂正**したうえで全指摘を修正した。

修正はReview対象branchを引き継がず、**現行 `origin/main` (`e8b7da6`) を新Baseに取り直した**
（Round 1 NOTE-01: `d9a1d02` はV73確定docs `900224e` を含まないstale main起点だったため）。

### 指摘別の対応

| ID | 指摘 | 対応 | 状態 |
|---|---|---|---|
| **P0-01** | V73がV1/V6のDDLを再定義しfresh DBで `ERROR 1050/1060` | **V73を唯一の正**とし、V1・V6への追記を全撤回（`git checkout` で完全復元）。V6は適用済みなので編集自体が禁止（checksum破壊）。fresh(V1→V73)もlegacy(V71→V73)も**同じV73のDDLだけ**を通るため、両経路が構造的に同一スキーマへ収束する | CLOSED |
| **P0-02** | V73 §7 が実在しない列(`menu_path`/`parent_id`/`icon`/`menu_type`/`deleted_flag`, `role_id`)と実在しないテーブル `m_role` を参照 | §7を既存作法（V69/V70と同形）へ全面書換。`INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order, created_at, updated_at)` ＋ `INSERT IGNORE INTO t_role_menu (role, menu_id) SELECT r.role … CROSS JOIN m_menu`。`m_menu` に階層概念は無いため親メニュー/`LAST_INSERT_ID()` を削除。付与ロールは design §6.2 のとおり 管理者/マネージャー/営業 のみ | CLOSED |
| **P1-01** | `CustomerContactSchemaTest` が 10件中2件 FK違反ERROR。R1.3と期間重複が未検証 | 各testが親 `m_customer` 行を自分で作るよう修正。あわせて期間境界fixtureを platform-invariants §1.2 の水準（部分重複/完全内包/同日/隣接/未来開始/無期限）へ拡充。**15/15 PASS** | CLOSED |
| **P1-02** | fresh/legacyでFK 9件が乖離 | P0-01の「V73だけが正」により**構造的に発生しえない**。加えて `FlywayLegacyV71MigrationSmokeTest` を新設し、legacy経路でFK 12件・UNIQUE 3件の存在をassertする | CLOSED（Docker実行はCI待ち） |
| **P1-03** | V73を実行するtestが0系統。ledgerが未実施の検証を実施済みと記載 | (a) 本ledgerのtest欄を実測値へ訂正。(b) `CustomerContactSchemaTest` が **V73ファイル本体から移行INSERTを抽出して実行**（H2用に書き直したコピーではない）。(c) Docker無しで効く静的検査を `MigrationScriptIntegrityTest` へ5件追加（17→22）。(d) fresh smokeへV73 assert群を追加、legacy smokeを新設 | CLOSED |
| P2-01 | 主担当一意がDB・serviceとも無保護で、testが「2件入る」ことをassertしていた | design §6.1 が許す**部分UNIQUE**をDDLで実装。生成列 `active_primary_customer_id`（VIRTUAL）＋ `uk_customer_contact_active_primary` で「有効中(status=有効/valid_to IS NULL/未削除)の primary は1顧客1件」を担保。testは**制約が2件目を拒否する**assertへ反転。期間を閉じた行同士の重なりはDB制約で表現できないためT049/A1のservice CASに残す | CLOSED（閉区間の重なりはT049へ） |
| P2-02 | 移行が `valid_from = CURDATE()` で過去asOfが0件になる | `COALESCE(CAST(c.created_at AS DATE), DATE '1900-01-01')` へ変更。`created_at` 欠損の旧行はsentinelへ倒す。sentinel経路も定向testで固定 | CLOSED |
| P2-03 | R1.2「移行後は互換表示だけ・write禁止」が未強制 | **T050(A1)へ持ち越し**。`m_customer.contact_*` の読み取り専用化は画面/API層の変更であり、T048(DDL)の範囲外 | OPEN → T050 |
| P2-04 | Rollback手順が散文かつ不正確 | 実行可能な手順へ書き直し（下記「Rollback」節）。V1へ列を足していないため `DROP COLUMN` 対象も正しくなった | CLOSED |
| NOTE-01 | branchがstale main起点 | 現行 `origin/main` (`e8b7da6`) を新Baseに取り直し。`tasks.md` ヘッダは既にV73確定版 | CLOSED |

### Round 1が拾えなかった追加欠陥（本修正で自主検出）

| ID | 内容 | 対応 |
|---|---|---|
| SELF-01 | `engineer-schema-h2.sql`（`@Sql` で読む統合H2スキーマ）が未更新で、`Project`/`Quotation`/`SalesActivity` entityの新列に対応する列が無く、`NotificationGenerateServiceTest`・`SalesActivityApiControllerTest` が `Column "SOURCE_OPPORTUNITY_ID" not found` / 500 で落ちていた（AGENTS.md「列を足したら engineer-schema-h2.sql も更新」違反）。Round 1は本suiteを実行していないため未検出 | 3テーブルへ列を追加し、CRM 3テーブルも同ファイルへ追加 |
| SELF-02 | `SpecDispatchConsistencyTest.予約Migration番号が実在スクリプトと衝突しないこと` が、V73が実在した瞬間にS08で失敗する（予約→実在への移行が未反映） | S05/S06と同じ作法で `SPEC_BY_CONVERSATION` からS08をコメントアウト |
| SELF-03 | H2側CRMスキーマにFKを張ると、共有インメモリH2の2つ目のcontext初期化でV1の `DROP TABLE IF EXISTS t_project` が参照制約で失敗し、全 `@SpringBootTest` が起動不能になる | 既存のH2 specスキーマ（bp-company/productivity/document-archive）と同じく**FKを張らない**方針へ統一し、理由をファイル冒頭へ明記。FK検証は実MySQL smokeが担当 |
| SELF-04 | `production-security-users-h2.sql` が `ProductionSecurityConfigurationTest`（`spring.sql.init.data-locations`）と `ProductionSecurityEnrollmentFixtureTest`（`@Sql`）の2経路から同じ共有H2へ素のINSERTを流しており、**testクラスを1つ足しただけでcontext cacheの順序が変わり** `Unique index violation: SYS_USER(USERNAME) 'breakglass2'` で落ちる潜在flake。Base時点では順序の運で通っていた | 投入を `WHERE NOT EXISTS` で冪等化（sys_user 1件・t_user_mfa 2件）。MFAの二重登録も `countEnrolled` が2を返して壊れるため同時に封じた。**本specの範囲外だが、本変更が顕在化させたので同一commitで是正**する（放置すると新規failureを持ち込んだように見える） |

---

## Round 2 独立Review = CONDITIONAL PASS への対応（2026-08-01）

Base `e8b7da6` → Head `3a708a8`（`origin/main` merge済み）。判定は
**CONDITIONAL PASS（P0=0 / P1=1 / P2=2 / NOTE=3）**。Round 1の
P0-01 / P0-02 / P1-01 / P1-02 / P1-03 / P2-01 / P2-02 / P2-04 / NOTE-01 は
Review側の実測で全て `VERIFIED_CLOSED`。P2-03 はT050へ繰越（合意済み）。

対応branchは `claude/crm-contact-opportunity-review-r2`（`origin/main` = `3a708a8` 起点）。
Round 1のbranch `feature/crm-contact-opportunity`（`d9a1d02`）は系譜のみmergeし、
tree は取り込んでいない（同branchはRound 1未修正版のままで、V1/V6の重複DDLとm_role参照のV73を
含むため取り込むと退行する。無害な `.gitignore` の1行だけ採用した）。

### CRM-R2-P1-01 — `/api/crm/*` がaction key解決表に無く全roleで403

Reviewの再現（jshellで `resolve()` が null）を追試し、**指摘どおりであることを確認**した。
`MenuPermissionFilter` は `/api/**` で actionKey==null なら deny()、page も matchedMenu の
api_prefix から再解決して null なら deny() し、この deny は**管理者bypassより前**にある。

修正は**2段構え**で、片方だけでは閉じない。

| # | 修正 | 無いとどうなるか |
|---|---|---|
| 1 | `ActionPermissionResolver.RESOURCE_NAMES` へ `Map.entry("crm", "crm")` を追加 | `resolve()` が null を返し、**管理者を含む全roleが403** |
| 2 | **V74** で `crm.*` を `t_permission_group_action` へseed（role-sales / role-manager） | V66_1が非管理者groupから全局 `*` を削除し「既知resource wildcardの列挙」へ置換したため、**group割当済みの営業/マネージャーが403** |

Reviewの最小修正は1だけだったが、2が無いと営業/マネージャーは依然403のままで、
「正しい期待値を書いたtest」を追加できない（実態に合わせた誤ったtestを書くのは Round 1 で
問題になったパターン）。したがって2も同時に入れて閉じた。

**V73のコメント訂正について**: Reviewは「V73:200-203のコメントを訂正する」としているが、
**V73は`main`にmerge済みで適用済みのためchecksumを壊せない**（本specがRound 1で最も強く指摘された規約）。
訂正はV74の冒頭コメントへ「V73のどの記述がなぜ誤りか」を明記する形で残した。

### 本対応で発見した既存の穴（S05 / S06）

上記2と同じ理由で、**V68/V69の `search` / `task` / `saved-view` / `batch-operation` と
V70の `bp-company` も権限seedが無く**、group割当済みの営業/HR/マネージャーは
**出荷済み機能で403**になる（V67の `document.*` だけが正しくseedしていた）。
発注者確認のうえ、同じ1行機構で **V74で併せて補完**した。付与先は各specのmenu付与に一致させている。

| resource | 付与先group | 根拠 |
|---|---|---|
| `crm.*` | role-sales / role-manager | V73のmenu付与（管理者/マネージャー/営業）。design §6.2でHR・要員は不可視 |
| `search.*` `task.*` `saved-view.*` `batch-operation.*` | role-sales / role-hr / role-manager | V69のmenu付与（全5role）。要員はSecurityConfigの`anyRequest`で`/my/**`以外へ到達できないためgroup付与しない |
| `bp-company.*` | role-sales / role-manager | V70のmenu付与（管理者/営業/マネージャー） |

### 再発防止（Docker不要の静的検査を2件追加）

Reviewのprocess記録「新規に導入するURI prefixについてもconsumer inventoryを適用する」を
人手のgrepではなくtestで固定した。**いずれも修正前の状態で実際に失敗することを確認済み**。

| test | 内容 | 修正前の実測 |
|---|---|---|
| `migrationが登録するメニューのapi_prefixがaction_keyへ解決できること` | m_menuへ入れる `api_prefix` が `resolve()` で解決できること | `crm` 未登録時に `/api/crm/leads` `/api/crm/opportunities` を検出してFAIL |
| `メニューを持つresourceには権限seedがあること` | menuを持つresourceに `<resource>.*` のseedがあること（admin専用の user/permission/audit は除外） | V74を外すとFAIL |

### 採番の繰り上げ

V74をCRMが使用したため、`latest + 1`・「後発を上へ繰り上げ、前の欠番は埋めない」の原則どおり
**S07 approval → V75、#9〜#17 → V76〜V84** へ繰り上げた。詳細と更新対象ファイルは
中央台帳 `spec-execution-ledger.md` §2.4 を正とする。**V59とV72は永久欠番**。

### Round 2 指摘の対応状況

| ID | 対応 | 状態 |
|---|---|---|
| **CRM-R2-P1-01** | `RESOURCE_NAMES` へ `crm` 登録 ＋ V74で `crm.*` seed ＋ 静的検査2件 ＋ filter/matrix回帰 | CLOSED |
| CRM-R2-P2-01（範囲外） | `main` 既存RED 3件。本specでは修正せず、中央台帳§2.4へbacklogとして記録。T053(M)前に解消が必要 | OPEN（backlog） |
| NOTE-R2-01 | 閉区間同士のprimary重なりはservice CASで塞ぐ | OPEN → T049/A1 |
| NOTE-R2-02 | CIのno-skip gateがsmokeを除外している。CI緑を実行証拠にしない | 運用注意として記録 |
| NOTE-R2-03 | `tasks.md` F1の `- [ ]` 維持は適切 | 対応不要 |

### Round 2対応のテスト（実測）

| test | 結果 |
|---|---|
| `MigrationScriptIntegrityTest` | **24/24 PASS**（静的検査を22→24へ拡充） |
| `ActionPermissionMatrixTest` | **13/13 PASS**（CRM／S05・S06のmatrixを追加） |
| `MenuPermissionFilterTest` | **14/14 PASS**（`/crm/leads` page・`/api/crm/leads`・HR拒否の3件を追加） |
| `SpecDispatchConsistencyTest` | **8/8 PASS**（採番繰り上げ後） |
| `CustomerContactSchemaTest` | 15/15 PASS（Round 1から変更なし） |
| 全量 `mvn clean test` | **1201 / F2 / E1 / S7**。失敗3件は `main` 既存の CRM-R2-P2-01 と同一で**新規failure 0件**（Round 1時点は1193/2/1/7、Base `e8b7da6` は1169/2/1/6） |
| `git diff --check` | exit 0 |
| fresh / legacy MySQL smoke | **未実行**（Docker不在によりskip）。V74のseed assert（付与先groupの一致）を追加済み |

---

## T048: F1. contact/lead/opportunity DDLと移行

| 項目 | 内容 |
|------|------|
| **Task** | T048 / F1. contact/lead/opportunity DDLと移行 |
| **Requirements** | R1.1（複数担当者DDL）, R1.2（既存contact移行）, R1.3（退職者の候補除外）, R2.1（商機DDL）, R3.1（リードDDL・activity関連付け）, design §6.3（冪等変換のUNIQUE） |
| **Base commit** | `e8b7da6` (`origin/main`) |
| **Branch** | `claude/crm-contact-opportunity-review-r1-xj8daf` |
| **Migration** | V73 (`V73__crm_contact_lead_opportunity.sql`) — 実適用最新はV71、V72はapproval予約のため使用しない（V72はV59と同じく永久欠番化する方針） |

### 変更ファイル

| ファイル | 変更内容 |
|----------|----------|
| `src/main/resources/db/migration/V73__crm_contact_lead_opportunity.sql` | 新規（唯一の正）: t_customer_contact / t_lead / t_opportunity、主担当一意の生成列＋UNIQUE、t_sales_activity 3列＋FK、t_project/t_quotation の source_opportunity_id＋UNIQUE＋FK、既存contact移行、CRMメニュー＋ロール付与 |
| `src/main/java/com/ses/entity/CustomerContact.java` | 新規entity（`@Version`） |
| `src/main/java/com/ses/entity/Lead.java` | 新規entity（`@Version`） |
| `src/main/java/com/ses/entity/Opportunity.java` | 新規entity（`@Version`） |
| `src/main/java/com/ses/entity/SalesActivity.java` | contactId / opportunityId / assigneeUserId 追加 |
| `src/main/java/com/ses/entity/Project.java` | sourceOpportunityId 追加 |
| `src/main/java/com/ses/entity/Quotation.java` | sourceOpportunityId 追加 |
| `src/main/java/com/ses/mapper/{CustomerContact,Lead,Opportunity}Mapper.java` | 新規mapper |
| `src/test/resources/sql/schema-crm-h2.sql` | 新規: H2方言のDDL（JSON→CLOB、VIRTUAL省略、FKなし） |
| `src/test/resources/sql/engineer-schema-h2.sql` | 統合H2スキーマ同期: t_project/t_quotation の source列＋UNIQUE、t_sales_activity 3列、CRM 3テーブル |
| `src/test/resources/application-test.yml` | schema-locations へ schema-crm-h2.sql を追加 |
| `src/test/java/com/ses/crm/CustomerContactSchemaTest.java` | 定向test 15件（後述） |
| `src/test/java/com/ses/migration/MigrationScriptIntegrityTest.java` | Docker無しの静的検査を5件追加（17→22） |
| `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | fresh経路のV73 assert群を追加 |
| `src/test/java/com/ses/migration/FlywayLegacyV71MigrationSmokeTest.java` | 新規: legacy(V71適用済み→V73)経路のsmoke |
| `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java` | S08を予約対象から除外（V73実装済み。S05/S06と同じ作法） |
| `src/test/resources/sql/production-security-users-h2.sql` | 共有H2への投入を冪等化（SELF-04。本specの範囲外だが本変更が顕在化させたため同一commitで是正） |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md` | 本ファイル |

**V1 / V6 は変更していない**（Round 1 P0-01 の是正。適用済みmigrationは編集しない）。

### テスト（実測）

| レベル | 内容 | 結果 |
|--------|------|------|
| L1 | `CustomerContactSchemaTest` — 3テーブルのentity CRUD（H2 replay） | **15/15 PASS**（下記に内訳） |
| L2 | 主担当一意（有効中は1顧客1件 / 期間を閉じた旧主担当は共存 / 顧客違いは並存）、0件許容 | PASS |
| L2 | 期間境界（部分重複・完全内包・同日・隣接・未来開始・無期限） | PASS |
| L2 | 退職者の宛先候補除外＋履歴保持（R1.3） | PASS |
| L2 | lead/opportunity 初期状態（converted列がNULL、未割当lead） | PASS |
| L2 | `t_project.source_opportunity_id` のUNIQUEが2件目を拒否（design §6.3） | PASS |
| L3 | **V73本体の移行INSERTを抽出して実行**し、件数一致・値一致（name/email/phone）・primary=1・valid_to NULL・valid_from=顧客登録日・再実行で0件・空文字/NULL除外 | PASS |
| L3 | `MigrationScriptIntegrityTest` | **22/22 PASS**（新規5件を含む） |
| L3 | `SpecDispatchConsistencyTest` | **8/8 PASS** |
| L3 | 直接回帰: `SalesActivityApiControllerTest` 7、`NotificationGenerateServiceTest` (web) 1 / (service) 9、`ContractServiceImplTest` 47、`QuotationApiControllerTest` 4、`CustomerApiControllerTest` 3、`MenuPermissionFilterTest` 11、`ActionPermissionMatrixTest` 10、`MessageBundleConsistencyTest` 4 | PASS |
| L3 | 全量 `mvn test`（H2スキーマが全context共通で影響範囲が全specに及ぶため、例外的に実施） | 下記「全量test」参照 |
| L4 | fresh / legacy MySQL smoke（Testcontainers） | **未実行（Docker不在によりskip）** — CI必須 |

#### 全量test（例外実施の理由と結果）

T048はL1〜L3が原則だが、本変更は `application-test.yml` の `schema-locations` と
`engineer-schema-h2.sql` という**全 `@SpringBootTest` が共有するH2スキーマ**を触るため、
影響範囲がspec内に閉じない。実際、定向testだけでは見えない `engineer-schema-h2.sql` 未更新
（SELF-01）を全量で検出したので、Base比較つきで1回実施した。

| 対象 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Base `e8b7da6`（`origin/main`, 本変更なし） | 1169 | 2 | 1 | 6 |
| 本変更適用後 | **1193** | **2** | **1** | **7** |

- **新規failure 0件**。残る3件（`WorkRecordServiceImplTest` 2件、`BpPaymentWritePathTest` 1件）は
  **Base時点で同一に失敗しており本変更とは無関係**。work-record scope / BP支払の担当specの範囲なので、
  本specでは手を入れない（担当spec外の修正禁止）。
- Skipped 6→7 の +1 は新規 `FlywayLegacyV71MigrationSmokeTest`（Docker不在でskip）。
- Tests +24 = `CustomerContactSchemaTest` 15 ＋ `MigrationScriptIntegrityTest` 5 ＋
  `FlywayLegacyV71MigrationSmokeTest` 1 ＋ 既存クラスの追加分。
- `git diff --check` exit 0。

新規静的検査5件（Docker無しで効く。いずれも**Round 1版のV73で実際に失敗することを確認済み**）:

1. `マイグレーションが参照するテーブルが定義済みであること` — `FROM m_role` のような実在しないテーブル参照を検出（P0-02の第3の誤り）
2. `同一テーブルを再CREATEする後発マイグレーションはIF_NOT_EXISTSを持つこと` — P0-01のCREATE衝突を検出
3. `V73のCRM定義はV1統合baselineへ二重に書かないこと` — V1/V6への書き戻しを禁止
4. `V73はテーブル作成の後に参照列追加とbackfillを行うこと` — FK先未作成/列未追加での参照を検出
5. `V73のCRMメニューは営業系3ロールにだけ付与すること` — design §6.2の逸脱（HR/要員への付与）を検出

検証手順: Round 1版のV73/V1/V6へ一時的に戻して `MigrationScriptIntegrityTest` を実行したところ
**22件中7件がFAIL**（新規5件＋既存の `INSERT列存在` `V1重複ADD` の2件）。修正版では22/22 PASS。

### Demo

| Demo項目 | 状態 |
|---|---|
| 既存顧客の担当者がdetailに表示 | **実施済み**。顧客detailのcontactsカードでV73移行contactを表示 |
| 移行前後で担当者名/emailが一致することを提示 | **自動testで代替済み**（`migrationDmlCopiesExistingCustomerContacts` がV73本体のSQLを実行して name/email/phone の一致と件数一致をassert）。実データでの照合はstaging待ち |

このため `tasks.md` の F1 は **`- [ ]` のまま**とする（Round 1指摘「`- [x]` は取り消しが必要」への対応）。
F1の完了定義そのものがT050へ依存する点は Round 1 NOTE-7 のとおりで、発注者判断が要る論点として残す。

### 未検証事項 / 本番前条件

| # | 事項 | 理由 | 解消条件 |
|---|------|------|----------|
| 1 | fresh MySQL 8 smoke (V1→V73) | 本環境にDockerが無く `@Testcontainers(disabledWithoutDocker = true)` でskip | Docker可能なCIで `FlywayMigrationSmokeTest` を 0 skipped で実行 |
| 2 | legacy MySQL smoke (V71適用済み→V73) | 同上 | Docker可能なCIで `FlywayLegacyV71MigrationSmokeTest` を 0 skipped で実行 |
| 3 | MySQL 8 での生成列＋UNIQUE の実挙動 | 同上。VIRTUALであることは静的検査＋fresh smokeのassertで固定済み | 上記1と同時 |
| 4 | 既存データ移行の実データ照合 | 実データ不在。seed 3件での一致は自動assert済み | staging実データでの件数・値照合 |
| 5 | PII mask（画面＝export同一, R1.4） | `CustomerContactServiceImpl`のDTO変換とCSV出力を同じ公開DTOへ統一 | `CustomerContactApiControllerTest` 1/1で画面/CSVのmask一致をassert |
| 6 | `m_customer.contact_*` のwrite禁止（R1.2後半） | `CustomerApiController`でlegacy contact_*を保存entityへコピーしない。既存値はread compatibilityとしてrole別mask表示 | `CustomerApiControllerTest` 3/3、顧客フォームのlegacy入力をreadonly化 |
| 7 | desktop / 390px ブラウザDemo | T050/T051の画面が前提 | 本番リリース前hard gate（S02/S04/S05/S06と同列） |

### Rollback

V73適用済みDBを戻す手順（**実行前に必ずフルダンプを取得する**）。V1/V6は変更していないため、
戻すのはV73が作った物だけでよい。

```sql
-- 1. 参照している側から外す（FKがあるためこの順序）
ALTER TABLE t_project   DROP FOREIGN KEY fk_project_source_opportunity;
ALTER TABLE t_quotation DROP FOREIGN KEY fk_quotation_source_opportunity;
ALTER TABLE t_project   DROP INDEX uk_project_source_opportunity,   DROP COLUMN source_opportunity_id;
ALTER TABLE t_quotation DROP INDEX uk_quotation_source_opportunity, DROP COLUMN source_opportunity_id;

ALTER TABLE t_sales_activity
  DROP FOREIGN KEY fk_activity_contact,
  DROP FOREIGN KEY fk_activity_opportunity,
  DROP FOREIGN KEY fk_activity_assignee;
ALTER TABLE t_sales_activity
  DROP COLUMN contact_id, DROP COLUMN opportunity_id, DROP COLUMN assignee_user_id;

-- 2. メニュー・権限（t_role_menu は m_menu へのFKで CASCADE 削除される）
DELETE FROM m_menu WHERE menu_key IN ('crm-lead', 'crm-opportunity');

-- 3. テーブル本体（t_opportunity が t_customer_contact より先）
DROP TABLE IF EXISTS t_opportunity;
DROP TABLE IF EXISTS t_lead;
DROP TABLE IF EXISTS t_customer_contact;

-- 4. Flywayの履歴から V73 を消す（消さないと再適用されない）
DELETE FROM flyway_schema_history WHERE version = '73';
```

- 移行で作られた `t_customer_contact` の行は上記3で消えるが、**元データ `m_customer.contact_*` は
  V73が一切書き換えていない**ため、rollbackでの情報欠損は無い。
- コード側（entity / mapper / test）は他から参照されていないため、revertのみで影響しない。

## T049: F2. opportunity状態/変換/forecast排他（task別完了記録）

| Task | Requirements / 変更file | Test | Demo | Commit | Risk / rollback |
|---|---|---|---|---|---|
| T049 / F2 | R2.3/R2.4、R4.2、design §6.3。`OpportunityService`/impl、状態・変換API、conversion DTO/request、Opportunity/Project/Quotation mapper、4言語message bundle | L1/L2: `OpportunityServiceImplTest` 7/7。L3: `OpportunityServiceIntegrationTest` 2/2（H2実DB）。`mvn compile` PASS。MySQL fresh/legacyはM/release gate | H2統合実行で交渉→受注→案件/見積を変換し、再変換後も同一ID・案件/見積各1件、converted商機はforecast母集団から除外 | `e781e79` | 既存案件status enumへ`募集中`を使用。新migrationなし。rollbackは本taskのコード/テスト/message差分をrevertし、V73/V74や既存DBデータは変更しない |

### T049の実装契約と境界

- stage変更は`OpportunityMapper.selectByIdForUpdate`で行ロックし、`@Version`のCAS失敗を409へ変換する。汎用`updateById`はstage遷移と終端状態を迂回できない。
- 受注遷移と案件・見積作成は同一transaction。`source_opportunity_id`の既存UNIQUEを使い、再実行時は既存行を返す。新しい外部APIやmigrationは追加していない。
- forecastは`converted_quotation_id IS NULL AND stage NOT IN (受注, 失注)`をSQL母集団として固定し、営業の顧客DataScopeが有効な場合は顧客ID条件をDBへ渡す。

## T050: A1. 顧客contacts/timeline（task別完了記録）

| Task | Requirements / 変更file | Test | Demo | Commit | Risk / rollback |
|---|---|---|---|---|---|
| T050 / A1 | R1.1〜R1.4/R3.1〜R3.2。`CustomerContactService`/impl、contact DTO/request/API、顧客timeline API/detail UI、activityのcontact/opportunity関連付け、invoice recipient選択、旧contact書込み遮断、4言語message bundle、H2 replay隔離test | `CustomerContactServiceIntegrationTest` 3/3、`CustomerContactApiControllerTest` 1/1、`InvoiceServiceImplTest` 41/41、`InvoiceApiControllerTest` 10/10、`SalesActivityApiControllerTest` 7/7、`CustomerApiControllerTest` 3/3、`MobileResponsiveLayoutTest` 23/23、`MessageBundleConsistencyTest` 4/4、`git diff --check` PASS。L4全量/MySQL smokeはT053 | 顧客detailで移行contact・関連商機・活動を表示。有効contactだけを請求リマインド宛先として選べ、退職後は候補から除外。送信履歴は既存`t_mail_delivery.recipient`へ送信時点snapshotを保存。CSVは画面DTOと同一mask | `c46e78b` | `m_customer.contact_*`は保存APIから遮断しread compatibilityのみ。T050は新規migrationなし。rollbackは本taskのコード/テスト/message差分をrevertし、V73/V74および既存MailDelivery履歴は変更しない |

### T050の実装契約と境界

- contactのvalid_from/valid_toは閉区間として扱い、primary=1かつ有効の期間重複を顧客行の同一transaction内でFOR UPDATEして拒否する。更新はversion CASで競合を拒否し、退職・異動行は履歴として保持する。
- recipient候補は`status=有効`、対象日がvalid区間内、email非NULL/非空をSQL条件で絞る。選択IDからの送信時はserviceが同じ条件で生emailを再解決し、退職者・他顧客ID・期間外IDを拒否する。
- 画面/CSVは同じ`CustomerContactDto`変換を利用し、非管理者のemail/phoneをmaskする。請求メールの実送信は既存MailDeliveryへrecipient snapshotを保存するため、担当者変更後も過去送信履歴の宛先は変わらない。
- `schema-crm-h2.sql`は共有H2（DB_CLOSE_DELAY=-1）でengineer-schemaのCRM表が残る順序依存を避けるため、CRM 3表をreplay時に再生成する。MySQL migrationは追加していない。

## T051: A2. lead/opportunity UI（task別完了記録）

| Task | Requirements / 変更file | Test | Demo | Commit | Risk / rollback |
|---|---|---|---|---|---|
| T051 / A2 | R3.1/R3.2/R3.4/R4.1/R4.2。lead service/API/page/UI、冪等転換、重複候補、opportunity screen API/page/UI、4言語message bundle、mobile layout回帰 | `LeadServiceIntegrationTest` 2/2、`CrmUiRegressionTest` 1/1、`MobileResponsiveLayoutTest` 22/22、`MessageBundleConsistencyTest` 4/4、`OpportunityServiceImplTest` 7/7、`OpportunityServiceIntegrationTest` 2/2、Node `--check` 2/2、compile PASS | `/crm/leads`で重複候補を警告のみ表示し自動mergeせず保存。lead→customer/contact/opportunityを2回実行して同一ID。`/crm/opportunities`のD&D API失敗時にカードを元stageへ戻す。390pxは横スクロールboardと230px列幅で確認 | `c46e78b` | lead転換はlead row lock＋version CASでcustomer/contact/opportunityを同一transactionに作成。rollbackは本taskのCRM UI/API/service/message/test差分のみをrevertし、V73/V74と既存データは変更しない。ブラウザ実D&DはMのrelease gateで追加確認 |

### T051の実装契約と境界

- 未割当leadは営業全員へ可視し、営業の担当leadは本人のみへ絞る。重複は会社名/email/phoneの候補を最大20件表示するだけで、自動mergeは実施しない。
- lead転換は`LeadMapper.selectByIdForUpdate`で直列化し、転換済み行はversionが古くても既存のcustomer/opportunity IDを返す冪等経路とした。新規contactは転換transaction内で有効・主担当として作成する。
- opportunityの一覧は既存DataScopeの許可customer集合をそのまま利用する。stage変更はT049の状態機械APIを再利用し、JSは成功するまでカードを仮移動し、HTTP/業務エラー時に元stageへ戻す。

## T052: B1. CRM KPI（task別完了記録）

| Task | Requirements / 変更file | Test | Demo | Commit | Risk / rollback |
|---|---|---|---|---|---|
| T052 / B1 | R4.1/R4.2。`CrmKpiDto`、`CrmKpiService`/impl、opportunity KPI API/page/JS、4言語message bundle、mobile/UI regression | `CrmKpiServiceIntegrationTest` 1/1、`CrmKpiScopeIntegrationTest` 1/1、`CrmUiRegressionTest` 1/1、`MobileResponsiveLayoutTest` 23/23、`MessageBundleConsistencyTest` 4/4、Node `--check` 3/3、compile PASS | `/crm/opportunities/kpi`でstage金額/加重forecast、滞留/活動なし、担当別転換、失注理由、source ROI、提案/商機forecastの別系列を表示。営業scopeで他担当/他顧客を除外し、変換済み商機を商機forecastから除外 | `c46e78b` | rollbackはT052のKPI service/API/page/JS/message/test差分をrevert。新migrationなし。stage滞留は`stage_changed_at`、source ROIはleadの`source_cost`を根拠に算出し、原価不明はnullとする |

### T052の実装契約と境界

- 商機forecastは`converted_quotation_id IS NULL`かつ`stage NOT IN (受注, 失注)`だけを対象に、`expected_amount`優先、NULLなら`unit_price × required_count`へstage確度を掛ける。既存提案forecastはDashboardと同じ4つのopen proposal status・設定済み確率を使い、レスポンス上も別フィールドで返す。
- 集計の母集団はlist/detailと同じDataScopeのcustomer集合を使う。営業DataScope時は本人担当＋未割当を可視とし、T052のfunnel集計では他担当の行を混ぜない。owner別合計は同一母集団からlead/opportunityを構成する。
- stage滞留は履歴テーブルを追加せず、商機の`stage_changed_at`を現stageの開始日時として日数化する。旧行でNULLの場合は`updated_at`へフォールバックする。活動なし日数は商機の活動の最終`activity_date`、活動が無い場合は商機作成時点からの日数とする。

## T053: M. 回帰（task別完了記録）

| Task | Requirements / 変更file | Test | Demo | Commit | Risk / rollback |
|---|---|---|---|---|---|
| T053 / M | Round3 blocker解消を含む一気通貫CRM回帰、既存customer/proposal/quotation回帰、Node/JS、desktop/390px、MySQL smoke | L4 `mvn test`: **1224 / F0 / E0 / S1**。CRM定向回帰、Flyway fresh/legacy V60/V71、V73部分修復fixture、repair、upgrade smoke、Node/JS、`git diff --check`を実測 | 管理者ログイン後、`/crm/leads`、`/crm/opportunities`、`/crm/opportunities/kpi`を表示。KPIは390px幅で主要見出し・Forecast・担当別・失注理由を確認。DB変更はV73/V1を編集せずrepeatable/runbook経路を使用 | `41d87e1` | `WorkRecordServiceImpl`は過去月に現行Engineer/直属組織を履歴代替として使わず、履歴snapshotが無い場合は拒否。rollbackは本レビュー修正commitをrevertし、V73/V1は変更しない |

## Round 3 独立Review FAIL 対応（2026-08-02）

Base `f582f9e`（CRM既存完了記録）から、Round 3のP0/P1/P2指摘を本レビュー修正として実装した。V73/V1は適用済みのため編集せず、既存DBにはrepeatable reconciliation、部分適用DBには明示runbookを用いる。

| Review ID / Task | 対応file・内容 | Test / Demo | 状態・risk / rollback |
|---|---|---|---|
| CRM-R3-P0-01 / T050-T052 | `CrmScopeService`を新設し、営業・マネージャー・管理者のCRM customer/owner母集団を統一。customer contact/timeline/activity/lead/opportunity/KPIへ同一scopeを適用し、HR/要員を拒否 | `ActionPermissionMatrixTest`、CRM定向回帰、L4全量。営業scope・manager組織scopeは既存scope fixtureで確認 | CLOSED。認可母集団はCRM serviceへ集約。rollbackは本修正commitのscope関連fileをrevert |
| CRM-R3-P0-02 / T053 | `WorkRecordServiceImpl`で過去月の現在Engineer/現行直属組織/account-linkを履歴snapshotの代替にしない。履歴なし・未知組織はfail-closed | `WorkRecordServiceImplTest` 43件、L4全量 | CLOSED。過去実績の参照可能性が狭くなるため、履歴snapshot投入が再開条件 |
| CRM-R3-P0-03 / T048-T053 | `R__crm_contact_reconciliation.sql`をV71以下でno-op、V73後の列/backfillをidempotentに実行。`sql/runbook/v73-crm-partial-repair.sql`は列/index/FK/移行contactを段階復旧し、MySQL fixtureで欠落状態を再現 | `FlywayMigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`FlywayRepairRunbookTest` PASS。V73は未編集 | CLOSED。適用済み履歴はrepairせず、実行前dump・validate・許可リスト確認が必要 |
| CRM-R3-P1 / T048-T052 | stage/probability/version/primary/null更新、activity relation/assignee、mail provider adapter、lead duplicate normalization、CSV formula injection、bounded list/batch customer load、4言語UIを補強 | CRM定向回帰、schema/entity regression、Node `--check`、L4全量。顧客detail/contact CRUD・lead/opportunity/KPIのDemo確認 | CLOSED。contact非管理者PII maskは編集画面で再送信しない運用確認を残す |
| CRM-R3-P2 / T051-T053 | opportunityカードのkeyboard操作、一覧上限、stage_changed_at、asOf/履歴・ROI口径を反映 | `CrmUiRegressionTest`、`MobileResponsiveLayoutTest`、L4全量 | CLOSED。実ブラウザでの全role操作、drag/reload/backはrelease前に再確認 |

## Round 4 独立Review FAIL 対応（2026-08-02〜）

Review packet: Base `8e5066a`（P0修正merge後の`origin/main`） / 対応前Head `8e5066a` / 対応commit `eb5adc4`。対象はS08のP1-01〜P1-04、P2-01〜P2-11とdesign/test matrixの改訂。

| ID | 対応 | 変更file | 検証 | 状態 |
|---|---|---|---|---|
| CRM-R4-P1-01 | V75がapprovalへ予約済みのためV73/V74を編集せず、R__のlegacy-repair DDL例外と6点同期表をdesignへ固定 | `design.md`、V73/V74は不変 | migration/H2/entity/smokeの同期を台帳化 | CLOSED（逸脱根拠固定） |
| CRM-R4-P1-02 | Round 4 packet、現行Head、P0修正証拠、Docker未実施skipを本節と中央台帳へ反映 | `review-ledger.md`、中央`spec-execution-ledger.md` | Docker L4/fresh/legacy/partial/repairはrelease gateとしてOPEN | OPEN（Docker証拠待ち） |
| CRM-R4-P1-03 | 役割を5値checkboxへ変更し、serviceでJSON配列へ正規化・allow-list検証。MySQL JSON往復fixtureを追加 | contact service/API/template/JS、4言語messages、`FlywayMigrationSmokeTest` | Contact service 3/3、MySQL fixtureはDocker待ち | CLOSED（実MySQL smokeはOPEN） |
| CRM-R4-P1-04 | PII平文を`customer.pii.view` actionへ移し、画面/CSV共通DTOとlegacy customer出力を同じ認可判定へ統一 | contact/customer service/API、design | 実service経由のmask test 3/3、画面/CSVは同一DTO経路 | CLOSED（browser DemoはOPEN） |
| CRM-R4-P2-01〜11 | timeline scope、KPI口径/SQL scope、商機→提案導線、顧客select/filter、活動fallback、asOf、重複候補、宛先role、status allow-listを修正 | CRM scope/KPI/contact/activity/lead/opportunity、CRM templates/JS | CRM定向10/10、compile PASS、Node check 3/3 | CLOSED（P2-08のbrowser rollback/390pxはowner: release QA、期限: 2026-08-15でDEFERRED） |

Review packet skip list: 本環境はDocker/browser未実施。Testcontainersのfresh/legacy/partial/repair/L4は0 skipped実測が必要。desktop/390px全roleのdrag/reload/back/二重click Demoもrelease前hard gateとしてOPEN。

P0-01 evidence: `4058d9b`（`R__crm_contact_reconciliation.sql`の余分な`END IF`削除＋`MigrationScriptIntegrityTest`均衡検査）。

### Round 4 同期6点と実測記録

| 追加列 | legacy-repair Flyway | H2 schema | entity | MySQL smoke assert |
|---|---|---|---|---|
| `t_opportunity.stage_changed_at` / `probability_override_reason` | `R__crm_contact_reconciliation.sql` 条件付きALTER | `schema-crm-h2.sql` / `engineer-schema-h2.sql` | `Opportunity` | `FlywayMigrationSmokeTest` CRM schema block |
| `t_lead.source_cost` | 同上 | 同上 | `Lead` | 同上 |
| `t_proposal.source_opportunity_id` | 同上 | `engineer-schema-h2.sql` | `Proposal` | `FlywayMigrationSmokeTest` JSON/CRM blockとproposal生成SELECT |
| `t_mail_delivery.contact_id` / `opportunity_id` | 同上 | `engineer-schema-h2.sql` | `MailDelivery` | `FlywayMigrationSmokeTest` mail schema assert |

定向実測（対応後）: compile PASS、`MigrationScriptIntegrityTest` 25/25、CRM KPI/Activity/Contact関連 10/0/0、Contact service 3/0/0、customer controller 3/0/0。`git diff --check` PASS。Docker fresh/legacy/partial/repair smoke、L4、browser Demoは未実施（環境不在）。

Task別変更file: T050 = contact/customer API/service・PII test、T051 = opportunity filter/proposal導線/lead duplicate、T052 = KPI scope/helper、T053 = 本ledger・中央ledger・release gate記録。
