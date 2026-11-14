# NF07 production bridge 段階実施計画（W3-04 NF07-P）

状態: **PLAN R2（Plan Review 再提出用）。この文書ではコード・migration・Spring Bean・設定を変更しない。**
作成日: 2026-11-14（Asia/Tokyo）。基準: `1be03bea`（S-NF 統合候補、branch `codex/snf-w3-nf07p-20261114`）。`com.ses.privacy` 配下のコードは `73aa7000` から無変更（main 68 file、test 24 file、Spring アノテーションなし、package 外からの参照なし）。

改訂記録:

- R1（commit `3d59ffae`）: 初版。独立 Plan Review は REQUEST CHANGES（外部記録の転記）（P1×2、P2×8、P3×12。報告は control repo `ses-claude-control-20261112-r1/reviews/nf07-plan-3d59ffae-review.md`、本repo外）。W-5 の方向と N7-2 は成立と判定された。
- R2（本版）: 指摘を反映した。対応箇所は下表のとおり。

| 指摘 | 内容 | 本版の対応箇所 |
|---|---|---|
| P1-1 | hold が実際の削除点（`DocumentServiceImpl.executeDisposal`）に接続されていない。`legal_hold_flag` との関係が不明。孤児清掃2 scheduler の扱いが不一致 | §3.4.1（破壊的経路の一覧）、§3.4.2（`legal_hold_flag` との関係） |
| P1-2 | 「subject 不明なら削除しない」＋第1期は解除不可で、最初の SUBJECT hold により多数の purge が恒久停止する | §3.4.3（採用案 (b)+(c)、補助 (a)、比較）、§7 D-05 行、§9 R-15/R-16 |
| P2-1 | Feature 登録が既存 test の不変条件と衝突。未登録 path が OFF で 404 にならない | §3.1.2、§5.1 |
| P2-2 | `*` group の privacy 除外が deny 行だけに依存 | §3.1.3、§6.2、§6.6 |
| P2-3 | `ActionPermissionMatrixTest` の完了条件が自己矛盾 | §3.1 完了条件 3、§6.5 |
| P2-4 | DSAR の SoD CHECK が DB で成立しない。承認が package に束縛されていない | §3.1 DDL 草案（`t_privacy_request`・`t_privacy_export_package`） |
| P2-5 | DSAR package が汎用 file 経路から取得できる | §3.5.2、§6.8 |
| P2-6 | pending 記録の reconciler と並行制御が無い | §3.4.4 |
| P2-7 | Journal の DataSource を `DataSource` 型で登録すると自動構成と衝突する | §3.3、[`journal-witness-grant-design.md`](journal-witness-grant-design.md) §2.1 |
| P2-8 | NF08 依存の書き方が不統一 | §0、§2、§4、§8、§9 R-14、§10 |
| P3-1〜P3-12 | resolver の全 method、tenant 一致、CLAUDE.md、生成列、複合 FK、警告と競合、A1J の配置、列 allow-list、監査・通知、共有 file、用語、privacy-admin の割当 | 各節（§3.1.4 の実装上の注意に集約） |

入力となった依頼者の決定（記録の所在は control repo `ses-claude-control-20261112-r1/reports/launch-decisions-20261113.md`、本repo外）:

| 決定 | 内容 |
|---|---|
| B-NF07-1（2026-11-13） | production bridge（W-1〜W-12）を全て接続してから本番公開する |
| B-NF07-2（2026-11-13） | privacy 管理権限は permission group の action 権限として付与する。baseline + deny に従い、既定は deny、管理者専用 group だけに開く。付与までは fail-closed |
| Q-1（2026-11-14） | 段階化する。第1期は F1（表・読み取り専用 policy source Bean・flag 既定OFF）、W-5 権限、A1 保全hold、A2 DSAR（半手動）。B1 自動処分と B2 recovery は署名後 |
| Q-8（2026-11-14） | DG-07 / D-01〜D-09 の署名前でも、値UNKNOWN・flag OFF・未知は一律拒否の形でコードをマージしてよい（tasks.md「F1〜M（DG-07完了まで実装禁止）」の停止条件を上書き。変更記録は tasks.md 同節） |
| Q-7（2026-11-14） | NF07 F1 の DDL を承認する（新規 migration だけで実装） |
| hold 正本（2026-11-14） | NF07 hold の正本は独立 Journal。hold は DBA が Journal インスタンスを用意（W-6）してから開放する |
| privacy-admin（2026-11-14） | privacy 管理者には `privacy-admin` だけを割り当てる（`role-admin` の代わり。`*` を持つので管理権限は減らない）。承認経路で `role-admin` を承認者 group にしている場合に外れる副作用は受け入れる |

本計画は D-01〜D-09、DG-07、G-*、P-* の署名・承認を代行しない。全項目は署名まで「未承認（記入禁止）」のまま（[`prod-wiring-decision-contract.md`](prod-wiring-decision-contract.md) §5）。batch ID は W3-14 だけが総括の採番済みで、他は仮名（総括が採番する）。

関連文書: [`policy-source-catalog-wiring-plan.md`](policy-source-catalog-wiring-plan.md)（N7-1 で取込）、[`status-alignment.md`](status-alignment.md)（N7-2）、[`journal-witness-grant-design.md`](journal-witness-grant-design.md)（W-6 設定契約・W-12 GRANT 設計案）。

---

## 0. 要約

- **第1期（署名前に実装・マージしてよい。全て flag OFF・値UNKNOWN・未知は拒否）**
  1. W3-14「F1 + PERM」: F1 表（`V?` 2本、暫定 V192/V193）、`privacy.retention.enabled` / `privacy.dsar.enabled`、決定ID束縛、読み取り専用 policy source Bean、説明API、W-5 権限、prod 起動時の privacy deny 不変条件の検査。総括の判断で W3-14a（F1）→ W3-14b（PERM）に分けてもよい（§3.1 規模）。
  2. NF07-F2「read-only provider」と NF07-A1J「Journal 本番 adapter・設定契約」（並行可）。
  3. NF07-A1H「保全hold（開始のみ）と破壊的経路への hold 接続」。
  4. NF07-A2a「DSAR 受付〜SCOPED」→ NF07-A2b「DSAR 検索〜交付記録」（半手動）。A1H と A2a/A2b は共有 file が重なるので直列にする。
- **署名前に実装してよいが開放は署名後の batch（Q-8）**: NF07-REG（W-2 policy 登録）、NF07-TRUST（W-3 KMS/CRL/OCSP）、NF07-REL（hold 解除 proof。実装は A1H の直後に前倒し）、NF07-NF08-LINK。**NF07-NF08-LINK は NF08 外部送信を開放する前に必ずマージする（必須の前提）。**
- **署名後に着手する batch（Q-1）**: B1（W-10 処分）、B2（W-11 recovery）、M（W-13 release）。
- **NF08 外部送信**: NF07-REG・NF07-TRUST・NF07-NF08-LINK がマージ済みで、`privacy.retention.enabled=true`、対象 policy が `APPROVED_ACTIVE` になるまで、NF08 の実外部送信は必ず拒否される（§8）。
- **外部で実施**: W-6 の Journal/Witness インスタンス（DBA/SRE）、W-12 の実 GRANT（DBA）。本計画は設定契約と GRANT 設計案だけを書く。
- **権限（W-5）**: `privacy.*` を `privacy-admin`（管理者専用 group）以外の全 group で deny、`privacy-admin` だけが許可。管理者 role の短絡許可を `privacy.*` に適用しない。group 未割当の legacy fallback は `privacy.*` を常に拒否する。多重防御として、privacy の判定では `*` を許可の候補に数えない。

---

## 1. 前提と不変条件

| # | 不変条件 |
|---|---|
| I-1 | 法的な値（保持期間、起算trigger、処分方式、本人確認基準、DSAR期限、hold種類と権限者、unquarantine基準）は署名まで UNKNOWN。コード・migration・設定の既定値に書かない。技術上の現在値（例: `app.resume.retention-days`=30、g10 allow-list の 730日）を法的値へ昇格しない |
| I-2 | `privacy.retention.enabled` / `privacy.dsar.enabled` は既定OFF。真偽値として解釈できない値は起動失敗（既存 `FeatureSwitchProperties` と同じ扱い）。OFF の入口は 404（登録済み path は `FeatureSwitchFilter`、それ以外は `PrivacyAccessGuard`） |
| I-3 | 未知は capability 単位で拒否する。決定ID欠落、policy UNKNOWN、provider 未登録、journal 不達、trust anchor 不明、失効状態不明は全て拒否（§5.2 の理由コード）。保全方向（hold 開始）は決定と journal が揃えば許可し、解除・処分・交付・外部送信は署名と専用 proof が揃うまで拒否する |
| I-4 | 第1期は業務データに対する削除・匿名化・restrict の writer、外部 provider の呼出、DSAR 成果物の自動交付を一切持たない（NF07 自身が作った一時 package を交付記録・期限で消すことは対象外） |
| I-5 | NF03 の日常 CRUD 等の既存業務を `POLICY_UNKNOWN` を理由に止めない（prod-wiring §4.1） |
| I-6 | 合成層（`SyntheticEnvironmentGuard`、test-only capability、`requireTestProfile()`、合成 adapter のコード）は変更しない。本番部品は別 class・別入口とする |
| I-7 | migration は新規だけ。番号は merged 状態で総括が採番する。V185 は NF10 が使用済み、最新は V186（[`status-alignment.md`](status-alignment.md) C-21） |
| I-8 | 各 batch は wave3 の共通規則に従う: worker は mvn を実行せず 1 commit を渡す → 総括が diff・BOM・LF・method 名の空白を確認 → Runner が test-compile → SHA に束縛した独立 Review → Runner が対象限定テスト（共通 guard を含む）→ `--no-ff` マージ |
| I-9 | hold を守る方向と、期限超過の保持を避ける方向の両方を守る。hold に帰属できない行は SUBJECT hold で止めない（§3.4.3） |

---

## 2. W-1〜W-13 と batch の対応

| W | 内容 | batch | 期 | 第1期で作るもの | 本番で開くのに必要な署名・外部作業 |
|---|---|---|---|---|---|
| W-1 | F1 catalog/policy/hold/request の本番 DDL | W3-14 | 第1期 | §3.1 の表。disposition job 表は B1 へ移す | D-01、D-02（値）、P-SRC |
| W-2 | 正式 PolicyApprovalEnvelope の登録、`POLICY_UNKNOWN` の解除 | NF07-REG | 実装は前倒し可、開放は署名後 | なし | D-01、D-02、D-03（処分を含む policy は D-04 も） |
| W-3 | 本番 Trust Anchor / KMS / CRL・OCSP | W3-14（設定駆動 registry、既定は空。失効 provider は常に UNKNOWN）＋ NF07-TRUST | 第1期は空 registry だけ。実 provider は前倒し可、開放は署名後 | 空 registry、常時 UNKNOWN の失効 provider | D-03 |
| W-4 | `ProductionPolicyReadonlyQueryService` の Spring 登録・API・NF03/NF08 への接続 | W3-14（Bean・説明API）＋ NF07-NF08-LINK | Bean/API は第1期。NF08 への接続（LINK）は前倒し可で、**NF08 外部送信の開放前に必須**。NF03 は日常業務を止めない契約（prod-wiring §4.1）なので、接続先は B1 の処分経路だけ | prod の Bean、`GET /api/privacy/policies/evaluate` | P-SRC、P-LE、P-ACT、P-API、G-ID |
| W-5 | privacy 権限の付与 | W3-14 | 第1期 | §6 | G-ID（割当規則）、D-01 |
| W-6 | 本番独立 Journal/Witness インスタンスと設定 | NF07-A1J（Journal adapter と設定契約）。Witness は B2 | code は第1期。インスタンスは外部 | adapter、起動時検査、DDL location、Flyway job | G-DBA、G-BKP |
| W-7 | 実 DB/file/AI provider、第三者 masking 規則 | NF07-F2 | 第1期（read-only、規則 UNKNOWN） | read-only provider、registry、redaction 枠 | D-06、D-08、AI G10、G-AUD、G-REC、G-EXP |
| W-8 | DSAR 受付・本人確認・export・交付（A2） | NF07-A2a、NF07-A2b | 第1期（半手動） | 受付〜交付記録 | D-01、D-06、D-09、G-AUD |
| W-9 | hold 開始/解除の authority 名簿、不可解除型（A1） | NF07-A1H（開始）＋ NF07-REL（解除） | 開始は第1期。解除は実装を A1H の直後に前倒し、開放は署名後 | hold 開始、破壊的経路への接続 | D-05、D-04、D-03（解除 proof） |
| W-10 | 処分 batch（B1）と flag の本番開放 | B1 | 署名後 | なし | D-02、D-04、D-09、全 gate |
| W-11 | unquarantine 手続、代表 restore/PITR 演習（B2） | B2 | 署名後 | なし | D-07、G-BKP（HFP-03-PROD-001〜008）、G-INC |
| W-12 | 本番最小 SQL 権限の実 GRANT | 外部（DBA） | 設計案は本 batch | [`journal-witness-grant-design.md`](journal-witness-grant-design.md) §3 | G-DBA |
| W-13 | PR・merge・deploy・Go/No-Go | M | 署名後 | なし | 全署名 + 独立 Review + 固定 SHA の全 gate |

依存の順序:

```text
W3-04 NF07-P（本計画）PASS
  └─ W3-14「F1 + PERM」（DDL lane: V?×2。分割する場合は W3-14a F1 → W3-14b PERM）
       ├─ NF07-F2（read-only provider。catalog seed があれば DDL lane）
       └─ NF07-A1J（Journal adapter・設定契約・Journal 用 Flyway job）
            └─ NF07-A1H（hold 開始・破壊的経路）
                 └─ NF07-A2a（DSAR 受付〜SCOPED。F2 も前提）
                      └─ NF07-A2b（DSAR 検索〜交付記録）
署名前に実装可・開放は署名後: NF07-REL（A1H の直後）/ NF07-REG / NF07-TRUST / NF07-NF08-LINK（NF08 外部送信の開放前に必須）
署名後に着手: B1 → B2 → M
```

A1H・A2a・A2b を直列にする理由: `ActionPermissionMatrixTest`、`FeatureSwitchRouteCoverageTest`、`Feature.java`、`templates/layout/sidebar.html`、messages 4本を共有するため。`ActionPermissionMatrixTest` の所有は W3-14（wave3 §2 の NF07-PERM）から A1H、A2a、A2b の順に引き継ぐ。MySQL shard の新規登録は各 batch で1 class ずつとし、同じ round で同じ shard file を2つの batch が触らない。

---

## 3. 第1期の batch

### 3.1 W3-14「F1 + PERM」

**目的**: F1 表、flag、決定ID束縛、読み取り専用 policy source Bean、説明API、privacy 権限を、値UNKNOWN・flag OFF のままマージする。NF03/NF08 の呼出元、NF08 の class、`application.yml` の `ai.*` は変更しない。

#### 3.1.1 ファイル集合（見込み）

| 区分 | ファイル |
|---|---|
| migration（DDL lane） | `src/main/resources/db/migration/V?__nf07_privacy_f1_core.sql`（暫定 V192）、`V?__nf07_privacy_permission_seed.sql`（暫定 V193） |
| H2 と DDL lane の付随 | `src/test/resources/sql/schema-privacy-f1-h2.sql`（新規）、`src/test/resources/application-test.yml`（schema-locations に追記）、`src/test/resources/sql/permission-group-seed-h2.sql`（`privacy-admin` group、deny 行、test user の所属行）、F1 表を使う test が `@Sql("/sql/engineer-schema-h2.sql")` を使う場合はその schema も同期、`src/test/java/com/ses/migration/LatestMigrationVersion.java`、`src/test/java/com/ses/config/ProductionSchemaVersionGuardTest.java`、`.kiro/specs/production-launch/deploy-runbook.md` の版数・件数行、`scripts/test-suites/mysql-shard-*.txt`（新 MySQL 試験を1回だけ登録） |
| 設定・flag | `src/main/resources/application.yml`（`privacy.*` 節だけ）、`com.ses.privacy.production.PrivacyFeatureProperties`（`@ConfigurationProperties("privacy")`）、`PrivacyDecisionBinding`（D-01〜D-09 の決定ID）、`PrivacyProductionConfigurationValidator` |
| 機能スイッチ（§3.1.2） | `com.ses.config.feature.Feature`、`FeatureSwitchProperties`、`src/test/java/com/ses/config/feature/FeatureSwitchRouteCoverageTest.java`、`FeatureSwitchDefaultsTest.java`、`.kiro/specs/production-launch/feature-switches.md` |
| entity / mapper | `com.ses.entity.privacy.PiiDataElement`、`RetentionPolicy`（`currentKey` は `@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)`。生成列へ書き込むと MySQL が拒否するため）、`com.ses.mapper.privacy.PiiDataElementMapper`、`RetentionPolicyMapper`（`@MapperScan("com.ses.mapper")` は subpackage を含む）。他の F1 表の entity は利用する batch で追加する |
| foundation（package-private の追加） | `ProductionPolicyCatalogPort`、`CurrentPolicySource`、`ProductionPolicyTrustRegistry`、`ProductionPolicyReadonlyQueryService`、record `PolicyLookupKey` / `ProductionCatalogBinding` / `CurrentPolicySnapshot`（[`policy-source-catalog-wiring-plan.md`](policy-source-catalog-wiring-plan.md) §2 の契約）、公開 factory `ProductionPrivacyComponents`（生成前に `ProductionEnvironmentGuard` を通す）。`SecurityContextCallerContextProvider`（§6.4 の判定変更）。`PolicyFailureReason` に `SOURCE_DISABLED`（`POLICY_UNKNOWN` 群）を追加する案 |
| Spring 配線（foundation 外） | `com.ses.privacy.production.PrivacyProductionConfiguration`（prod は production service、非 prod は常に `POLICY_UNKNOWN` を返す `UnavailablePolicyReadonlyQueryPort`）、`com.ses.privacy.production.retention.policy.DbProductionPolicyCatalog`、`DbCurrentPolicySource`、`ConfiguredPolicyTrustRegistry`（既定は空）、`UnknownRevocationStatusProvider`（常に UNKNOWN） |
| API | `com.ses.controller.api.PrivacyPolicyApiController`（`GET /api/privacy/policies/evaluate` だけ）、`com.ses.privacy.production.PrivacyAccessGuard`（service 層で flag と action を両方再判定。`AuthorizationService` が取れなければ拒否） |
| 権限 | `AuthorizationServiceImpl`、`ActionPermissionResolver`、`PermissionGroupManagementServiceImpl`（割当 guard と監査、§6.7）、`BreakGlassServiceImpl`（`privacy.` 拒否）、`SecurityConfig`（`/privacy/**`・`/api/privacy/**` を `hasRole("管理者")`、`/privacy/**` の許可 method を6つに限定。§6.1）、`ProductionSecurityConfigurationValidator`（§3.1.3） |
| messages | `messages{,_en,_ko,_zh_CN}.properties`（例: `error.privacy.disabled`、`error.privacy.decisionUnknown`、`error.permission.adminOnlyGroup`、`error.permission.privacyGroupConflict`。各言語で翻訳し、日本語を複写しない） |

foundation package 内には Spring アノテーションを置かない（`PrivacyFoundationIsolationTest` を拡張して固定する）。Spring 登録は `com.ses.privacy.production` 配下に置き、foundation 側は `ProductionPrivacyComponents` だけを public にする。production 側の service package は `com.ses.privacy.production.retention.*`（policy・catalog・hold）と `com.ses.privacy.production.dsar` に分ける（§3.1.2 の route coverage が package で機能を判定するため）。

#### 3.1.2 機能スイッチの登録（P2-1）

既存 test の不変条件（`FeatureSwitchRouteCoverageTest` の prefix と controller の対応・機能間の prefix 重複禁止・完全一致 path は静的 file 専用・sidebar の menu key 判定、`FeatureSwitchDefaultsTest` の環境変数による ON）を満たす形にする。

1. `Feature.NF07_RETENTION`（key `nf07-retention`、menu key `privacy-retention`）と `Feature.NF07_DSAR`（key `nf07-dsar`、menu key `privacy-dsar`）を親子にせず追加する。`/privacy` の根画面は作らず、`/privacy` を前方一致にも完全一致にも登録しない。
2. prefix は controller を追加する batch で登録する。W3-14 は `NF07_RETENTION` の apiPrefixes に `/api/privacy/policies` だけを入れ、`NF07_DSAR` は全 list を空で始める（`NF03_CERTIFICATION_PII` と同じ）。以後、F2 が `/api/privacy/catalog`、A1H が `/api/privacy/holds` と `/privacy/holds`、A2a が `/api/privacy/dsar` と `/privacy/dsar` を足す。これらは segment 単位で重ならない。
3. `Feature.propertyName()` / `environmentVariable()` を NF07 の2つだけ上書きし、`privacy.retention.enabled` / `PRIVACY_RETENTION_ENABLED`、`privacy.dsar.enabled` / `PRIVACY_DSAR_ENABLED` を返す。`FeatureSwitchProperties.toggle()` は NF07 の2つについて `PrivacyFeatureProperties` の Toggle を返す。`app.features.*` 側に別名を作らない。
4. `FeatureSwitchDefaultsTest` の bind 補助を `privacy.*` も bind するよう変える。既存の parameterized test は `feature.environmentVariable()` を使うので、上書き後の名前でそのまま通る。
5. `FeatureSwitchRouteCoverageTest` の `NF_SERVICE_PACKAGES` に `com.ses.privacy.production.retention` → `NF07_RETENTION`、`com.ses.privacy.production.dsar` → `NF07_DSAR` を足す（`Map.of` の上限10件に達するので `Map.ofEntries` に変える）。`NF_ACTION_RESOURCES` には `privacy` を登録しない（1つの resource が2機能にまたがるため。漏れの検出は package 側で行う）。sidebar の `linkedFeatures` の期待集合は、sidebar に導線を足す batch（A1H、A2a）で更新する。
6. `PrivacyAccessGuard` は action と flag を両方確認し、flag OFF なら `FeatureSwitchFilter` と同じ本文の 404 を返す。これで、遮断表に登録していない `/api/privacy/<x>` も OFF で拒否される。

#### 3.1.3 prod 起動時の privacy deny 不変条件（P2-2）

`ProductionSecurityConfigurationValidator` に検査を足す（既存の `validateDefaultPermissionGroups` の隣）。prod では flag の状態に関係なく常に行う。

- 設定 tenant の有効な group のうち、`action_key = '*'` かつ `deny_flag = 0` の行を持ち、group key が `privacy-admin` でなく、`privacy.*` の deny 行を持たないものが1件でもあれば起動失敗とする（固定文言、group key だけを出す）。
- `privacy-admin` group が無効・欠落していても起動は止めない（flag OFF なら使わないため）。flag ON のときの必要条件は §5.1。
- 多重防御として、§6.2 で privacy の判定時に `*` を許可の候補から外す。これにより、将来 `*` を複製した group が deny を欠いても privacy は開かない。起動時検査はその状態を運用上の誤りとして早期に知らせる役である。

#### 3.1.4 実装上の注意（Plan Review の注意事項を反映）

1. resolver: privacy 分岐は query 除去の直後、`/api/` 判定より前に置き、`/api/privacy/**` と `/privacy/**` を一度に扱う。画面を含め GET / HEAD / POST / PUT / PATCH / DELETE で null でない `privacy.*` を返し、それ以外の method の扱いは §6.1 のとおり。`privacy` を `RESOURCE_NAMES` に入れる commit で、legacy fallback 先頭の拒否と break-glass の拒否も同時に入れる（片方だけだと `isKnownAction` が true になり legacy と break-glass が privacy を通す）。
2. `AuthorizationServiceImpl`: §6.2 の順序で `startsWith("privacy.")` を判定する。`BUSINESS_ACTIONS` は変えない。
3. `ActionPermissionMatrixTest`: 既存 assertion のうち変更するのは seed 件数の3行だけ（§6.5）。9008（`privacy-admin` と `role-admin` の両方に所属する管理者 → false）を足す。
4. Feature 登録は §3.1.2。
5. 起動時検査は §3.1.3。
6. DDL: §3.1.5 の SoD と package 束縛、`RetentionPolicy.currentKey` の NEVER、`m_menu.sort_order` は既存の 97 と重複するので未使用値（暫定 260 / 261）を使う。
7. §6.4: tenant 不一致なら拒否、`AuthorizationService` が取れなければ拒否、既存 `SecurityContextCallerContextProviderTest` の正例を新しい判定に書き換える。
8. policy source Bean: 構築時に DB へ触れない（lazy に読む）。flag OFF で読取0回を test で固定する。
9. CLAUDE.md の記述の食い違い（§6.6）は W3-14 では直さず、X3-3 / DOC-FINAL に任せる。
10. NF08 の class と `application.yml` の `ai.*` は触らない。messages は4言語でそれぞれ翻訳する。

#### 3.1.5 DDL 草案（番号なし。`V?__nf07_privacy_f1_core.sql`）

```sql
-- NF07 F1: catalog / policy / hold projection / DSAR request。法的な値は一切seedしない。
-- 処分 job（t_disposition_job / t_disposition_item）は署名後の B1 で別 migration とする。

CREATE TABLE m_pii_data_element (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        VARCHAR(100) NOT NULL DEFAULT 'default',
  data_element_id  VARCHAR(128) NOT NULL COMMENT '固定ID（例: AI_PROMPT_LOG）。pii-inventory.md の行と対応',
  storage_kind     VARCHAR(20)  NOT NULL COMMENT 'DB_COLUMN/FILE_OBJECT/AI_PAYLOAD/LOG_OR_BACKUP',
  storage_ref      VARCHAR(255) NOT NULL COMMENT 'table.column / provider.object-kind / payload.field',
  data_class       VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
  owner_state      VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
  owner_ref        VARCHAR(100) NULL,
  provider_id      VARCHAR(100) NULL COMMENT 'F2 の provider。NULL=未登録（検索・処分の対象外として報告する）',
  subject_attribution VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ATTRIBUTABLE/NOT_ATTRIBUTABLE/NO_PERSONAL_DATA/UNKNOWN（§3.4.3）',
  third_party_flag TINYINT      NOT NULL DEFAULT 1 COMMENT '第三者情報を含み得る（既定は含む扱い）',
  inventory_status VARCHAR(30)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'CONFIRMED_TECHNICAL/PROVISIONAL/UNKNOWN。法的承認ではない',
  inventory_ref    VARCHAR(255) NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag     TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_pii_data_element UNIQUE (tenant_id, data_element_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF07 PII data element catalog（構造のみ）';

-- policy の正本候補。値は署名済み envelope の canonical bytes にだけ入る。
-- current pointer は生成列 + UNIQUE で (tenant, module, 法人, data element, action, provider) ごとに高々1件にする。
CREATE TABLE m_retention_policy (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id               VARCHAR(100) NOT NULL,
  policy_id               VARCHAR(128) NOT NULL,
  policy_revision         VARCHAR(64)  NOT NULL,
  module_code             VARCHAR(50)  NOT NULL,
  legal_entity_id         BIGINT       NULL COMMENT 'NULL=tenant-wide。P-LE 未決のため法人単位の主張はしない',
  data_element_id         VARCHAR(128) NOT NULL,
  target_action           VARCHAR(50)  NOT NULL,
  target_provider         VARCHAR(100) NOT NULL,
  status                  VARCHAR(20)  NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/CURRENT/SUPERSEDED/REVOKED',
  current_key             VARCHAR(512) GENERATED ALWAYS AS (
                            CASE WHEN status = 'CURRENT'
                                 THEN CONCAT_WS('|', tenant_id, module_code, IFNULL(legal_entity_id, 0),
                                                data_element_id, target_action, target_provider)
                                 ELSE NULL END) STORED,
  envelope_schema_version INT          NOT NULL,
  envelope_canonical      LONGBLOB     NOT NULL COMMENT '署名対象の canonical bytes',
  envelope_signature      VARBINARY(1024) NOT NULL COMMENT '現行 kernel は Ed25519（64 byte）。D-03 で方式が決まったら長さを見直す',
  envelope_digest         CHAR(64)     NOT NULL,
  authority_id            VARCHAR(100) NOT NULL,
  key_id                  VARCHAR(100) NOT NULL,
  registered_by           BIGINT       NOT NULL,
  registered_at           DATETIME(6)  NOT NULL,
  activated_by            BIGINT       NULL,
  activated_at            DATETIME(6)  NULL,
  row_version             BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_retention_policy_revision UNIQUE (tenant_id, policy_id, policy_revision),
  CONSTRAINT uk_retention_policy_current UNIQUE (current_key),
  CONSTRAINT chk_retention_policy_activation CHECK (status <> 'CURRENT' OR (activated_by IS NOT NULL AND activated_at IS NOT NULL AND activated_by <> registered_by))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 retention policy（署名envelope + current pointer）';

-- hold は独立 Journal が正本で、本表は projection。理由本文は保存せず、コード・hash・ticket 参照だけを持つ。
CREATE TABLE t_legal_hold (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           VARCHAR(100) NOT NULL,
  hold_id             VARCHAR(64)  NOT NULL COMMENT 'Journal 上の hold 識別子',
  target_type         VARCHAR(20)  NOT NULL COMMENT 'SUBJECT/DATA_ELEMENT',
  subject_ref         VARCHAR(128) NULL COMMENT '種別付き opaque 参照（例: ENGINEER:123）',
  data_element_id     VARCHAR(128) NULL,
  hold_kind           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'D-05 で確定するまで UNKNOWN',
  status              VARCHAR(20)  NOT NULL COMMENT 'ACTIVE/RELEASED',
  reason_code         VARCHAR(50)  NOT NULL,
  reason_hash         CHAR(64)     NOT NULL,
  ticket_ref          VARCHAR(100) NOT NULL,
  requested_by        BIGINT       NOT NULL,
  requested_at        DATETIME(6)  NOT NULL,
  journal_revision    BIGINT       NOT NULL,
  journal_event_hash  CHAR(64)     NOT NULL,
  released_at         DATETIME(6)  NULL,
  CONSTRAINT uk_legal_hold UNIQUE (tenant_id, hold_id),
  CONSTRAINT chk_legal_hold_target CHECK ((target_type = 'SUBJECT' AND subject_ref IS NOT NULL AND data_element_id IS NULL)
                                       OR (target_type = 'DATA_ELEMENT' AND data_element_id IS NOT NULL AND subject_ref IS NULL)),
  INDEX idx_legal_hold_subject (tenant_id, subject_ref, status),
  INDEX idx_legal_hold_element (tenant_id, data_element_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 hold projection（正本は独立Journal）';

-- 行が1件でもあれば「hold 機構が使われた」とみなし、破壊的経路は Journal との追随確認を必須にする（§3.4.4）。
-- 最初の Journal append の前に行を作り、append 中の command を pending_command_id に CAS で記録する。
CREATE TABLE t_privacy_projection_state (
  tenant_id            VARCHAR(100) NOT NULL,
  journal_id           VARCHAR(64)  NOT NULL,
  engaged_at           DATETIME(6)  NOT NULL,
  last_applied_seq     BIGINT       NOT NULL DEFAULT 0,
  last_applied_hash    CHAR(64)     NULL,
  pending_command_id   VARCHAR(64)  NULL COMMENT 'Journal append 前に CAS で記録し、projection 完了か reconciler の判定で NULL に戻す',
  pending_since        DATETIME(6)  NULL,
  verified_at          DATETIME(6)  NULL,
  row_version          BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, journal_id),
  CONSTRAINT chk_projection_pending CHECK ((pending_command_id IS NULL) = (pending_since IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='hold projection の追随位置（A1H の gate が参照）';

-- DSAR。依頼者の連絡先・本人確認書類は保存せず、外部 ticket / 証跡の参照IDだけを持つ（第1期）。
CREATE TABLE t_privacy_request (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                VARCHAR(100) NOT NULL,
  request_no               VARCHAR(40)  NOT NULL,
  request_type             VARCHAR(20)  NOT NULL COMMENT 'ACCESS/CORRECTION/DELETION/RESTRICTION/OBJECTION',
  status                   VARCHAR(30)  NOT NULL COMMENT 'PrivacyCaseState の部分集合',
  channel                  VARCHAR(20)  NOT NULL,
  received_at              DATETIME(6)  NOT NULL,
  due_date                 DATE         NULL COMMENT 'D-06 の期限規則で計算。規則未設定なら NULL',
  intake_ticket_ref        VARCHAR(128) NOT NULL,
  identity_state           VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/AMBIGUOUS/VERIFIED',
  identity_evidence_ref    VARCHAR(128) NULL,
  identity_rule_ref        VARCHAR(128) NULL COMMENT '適用した D-06 本人確認基準の決定ID',
  identity_verified_by     BIGINT       NULL,
  identity_verified_at     DATETIME(6)  NULL,
  case_owner_id            BIGINT       NULL,
  scope_snapshot_hash      CHAR(64)     NULL,
  prepared_by              BIGINT       NULL,
  approved_by              BIGINT       NULL,
  approved_at              DATETIME(6)  NULL,
  approved_package_id      BIGINT       NULL COMMENT '承認した package。ダウンロード・交付記録時に照合する',
  approved_content_sha256  CHAR(64)     NULL COMMENT '承認した package の内容 hash',
  delivered_by             BIGINT       NULL,
  delivered_at             DATETIME(6)  NULL,
  delivery_method          VARCHAR(20)  NULL,
  delivery_evidence_ref    VARCHAR(128) NULL,
  reopen_of_request_id     BIGINT       NULL,
  row_version              BIGINT       NOT NULL DEFAULT 0,
  created_by               BIGINT       NOT NULL,
  created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag             TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_privacy_request_no UNIQUE (tenant_id, request_no),
  CONSTRAINT uk_privacy_request_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_privacy_request_sod CHECK (approved_by IS NULL OR (prepared_by IS NOT NULL AND approved_by <> prepared_by)),
  CONSTRAINT chk_privacy_request_approval_binding CHECK (approved_by IS NULL OR (approved_at IS NOT NULL AND approved_package_id IS NOT NULL AND approved_content_sha256 IS NOT NULL)),
  CONSTRAINT fk_privacy_request_reopen FOREIGN KEY (tenant_id, reopen_of_request_id) REFERENCES t_privacy_request(tenant_id, id),
  INDEX idx_privacy_request_status (tenant_id, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF07 DSAR case';

-- 子表の FK は tenant を含む複合 FK とし、DB 層でも tenant をまたぐ関連を作れないようにする。
CREATE TABLE t_privacy_request_subject_link (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  request_id        BIGINT       NOT NULL,
  subject_ref       VARCHAR(128) NOT NULL,
  link_state        VARCHAR(20)  NOT NULL COMMENT 'CANDIDATE/SELECTED/REJECTED',
  resolved_by       BIGINT       NULL,
  resolved_at       DATETIME(6)  NULL,
  resolution_code   VARCHAR(50)  NULL,
  CONSTRAINT uk_privacy_subject_link UNIQUE (tenant_id, request_id, subject_ref),
  CONSTRAINT fk_privacy_subject_link_request FOREIGN KEY (tenant_id, request_id) REFERENCES t_privacy_request(tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR と本人の明示 link（自動結合しない）';

CREATE TABLE t_privacy_action (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  request_id        BIGINT       NOT NULL,
  action_type       VARCHAR(20)  NOT NULL COMMENT 'SEARCH/EXPORT_DRAFT/MANUAL_ACTION',
  provider_id       VARCHAR(100) NULL,
  data_element_id   VARCHAR(128) NULL,
  status            VARCHAR(20)  NOT NULL COMMENT 'PENDING/CLAIMED/SUCCEEDED/BLOCKED/UNKNOWN/FAILED',
  idempotency_key   VARCHAR(128) NOT NULL,
  claim_token       VARCHAR(64)  NULL,
  lease_until       DATETIME(6)  NULL,
  result_count      INT          NULL,
  result_hash       CHAR(64)     NULL,
  blocked_reason    VARCHAR(50)  NULL,
  evidence_ref      VARCHAR(128) NULL,
  row_version       BIGINT       NOT NULL DEFAULT 0,
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_privacy_action_idem UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_privacy_action_request FOREIGN KEY (tenant_id, request_id) REFERENCES t_privacy_request(tenant_id, id),
  INDEX idx_privacy_action_request (tenant_id, request_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR の検索・草案・手動対応の記録';

CREATE TABLE t_privacy_export_package (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       VARCHAR(100) NOT NULL,
  request_id      BIGINT       NOT NULL,
  stored_name     VARCHAR(255) NOT NULL COMMENT 'FileStorageService の保存名（quarantine→published）。汎用 file 経路からは取得させない（§3.5.2）',
  content_sha256  CHAR(64)     NOT NULL,
  size_bytes      BIGINT       NOT NULL,
  status          VARCHAR(20)  NOT NULL COMMENT 'DRAFT/APPROVED/DELIVERED/PURGED',
  purge_after     DATETIME(6)  NOT NULL,
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  approved_by     BIGINT       NULL,
  approved_at     DATETIME(6)  NULL,
  purged_at       DATETIME(6)  NULL,
  CONSTRAINT uk_privacy_export_package UNIQUE (tenant_id, stored_name),
  CONSTRAINT uk_privacy_export_package_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_privacy_export_package_sod CHECK (approved_by IS NULL OR approved_by <> created_by),
  CONSTRAINT chk_privacy_export_package_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
  CONSTRAINT fk_privacy_export_request FOREIGN KEY (tenant_id, request_id) REFERENCES t_privacy_request(tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR 開示 package（一時保存）';

-- request と package は相互に参照するため、承認 package の FK は両表の作成後に足す。
ALTER TABLE t_privacy_request
  ADD CONSTRAINT fk_privacy_request_approved_package
  FOREIGN KEY (tenant_id, approved_package_id) REFERENCES t_privacy_export_package(tenant_id, id);
```

承認の束縛: 承認時に service は同一 tx で、`t_privacy_export_package` の対象行を `status='APPROVED'`・`approved_by`・`approved_at` に CAS 更新し、`t_privacy_request` の `approved_package_id`・`approved_content_sha256` に同じ package の id と hash を書く。ダウンロード（§3.5.2）と交付記録では、package の `content_sha256` と request の `approved_content_sha256` が一致し、package id が `approved_package_id` と一致する場合だけ許可する。承認後に別の package を作っても、承認されていない package は取得も交付記録もできない。

H2 では生成列・CHECK・複合 FK の構文や挙動が異なるため、`schema-privacy-f1-h2.sql` を別に持つ（CLAUDE.md の「MySQL 専用 migration には H2 変種を置く」に従う）。H2 変種との差は MySQL 試験 `FlywayNf07PrivacyF1MigrationTest` が実 MySQL で補う。

**DDL 草案（番号なし。`V?__nf07_privacy_permission_seed.sql`）** は §6.3 に置く。

**依存**: W3-04（本計画）の PASS、DDL lane の割当（総括）、`ActionPermissionMatrixTest` の所有（wave3 §2 で NF07-PERM に割当済み）、`application.yml` は `privacy.*` 節だけを変更する。

#### 3.1.6 対象限定テスト

| 種別 | test class | 確認すること |
|---|---|---|
| Fast | `ActionPermissionMatrixTest` | §6.5 の変更。既存 assertion のうち変更するのは seed 件数の3行だけ。`BUSINESS_ACTIONS` は編集しない |
| Fast | `PermissionGroupManagementServiceImplTest` | `privacy-admin` を管理者 role 以外へ割り当てると 400、`privacy.*` deny を持つ group との同時割当は 409、`privacy-admin` の割当・解除は group key 付きの required 監査と既存 privacy-admin への通知を伴う、既存の session 失効は不変 |
| Fast | `BreakGlassServiceImplTest` | `allowed_actions` に `privacy.` 接頭辞を含む申請は 400 |
| Fast | `MenuPermissionFilterTest` | `/api/privacy/**` は `privacy-admin` 未所属の管理者でも 403 JSON |
| Fast | `FeatureSwitchFilterTest`、`FeatureSwitchRouteCoverageTest` | NF07 の入口が OFF で 404、NF 外の入口を誤遮断しない、prefix の重複が無い、package の対応表が NF07 を検出する |
| Fast | `FeatureSwitchDefaultsTest` | prod の既定で NF07 の2つも OFF、`PRIVACY_RETENTION_ENABLED` / `PRIVACY_DSAR_ENABLED` だけで該当機能が ON |
| Fast | `PrivacyFeaturePropertiesTest`（新規） | 既定 OFF、`ture` 等で bind 失敗 |
| Fast | `PrivacyDecisionBindingTest`（新規） | placeholder 断片・重複ID・未来の署名日を拒否。規則は NF08 の `AiExternalSendApproval` と一致（乖離を検出する比較 test を置く） |
| Fast | `PrivacyProductionConfigurationValidatorTest`（新規） | prod で flag ON かつ D-01 / D-09 の決定ID欠落なら起動失敗（固定文言、値は出さない）。flag OFF では決定ID不要 |
| Fast | `ProductionSecurityConfigurationValidatorTest` | `*` 許可を持ち `privacy.*` deny を持たない group（`privacy-admin` 以外）があれば prod 起動失敗。現行 seed 相当では成功 |
| Fast | `ProductionPolicyReadonlyQueryServiceTest`（新規） | policy-source-catalog-wiring-plan.md §4 の (a)〜(e)。flag OFF では source の読取 0 回で `POLICY_UNKNOWN`/`SOURCE_DISABLED`。Bean 構築時の DB 接触 0 回 |
| Fast（H2） | `DbCurrentPolicySourceTest`（新規） | 0件は `NOT_FOUND`、CANDIDATE/SUPERSEDED/REVOKED は current として返さない、読取は1文の一貫 snapshot、`currentKey` を書かない |
| Fast | `SecurityContextCallerContextProviderTest` | §6.4 の変更後、`privacy-admin` 所属の管理者だけが通る。GrantedAuthority 文字列だけでは通らない。tenant 不一致と `AuthorizationService` 不在は拒否 |
| Fast | `PrivacyFoundationIsolationTest` | foundation 内に Spring アノテーションなし。package-private 部品は `ProductionPrivacyComponents` 経由でしか生成できない |
| Fast | `PrivacyPolicyApiControllerTest`（新規、`@WebMvcTest`） | service 層の再認可で 403、flag OFF なら 404、`ApiResult` 形式 |
| 共通 guard | `MigrationScriptIntegrityTest`、`MessageBundleConsistencyTest`、`ProductionSchemaVersionGuardTest`、`MySqlTestShardInventoryTest` | 版番号重複なし、4言語の key 一致、最新版数、shard 登録が1回 |
| MySQL（`@Tag("mysql")`） | `FlywayNf07PrivacyF1MigrationTest`（新規） | 空DBから最新まで適用、F1 表と collation、`current_key` の2件目 CURRENT が Duplicate、`chk_retention_policy_activation`、`chk_privacy_request_sod`（`prepared_by` NULL での承認を拒否）、承認束縛の CHECK、複合 FK が他 tenant の request を参照させない、`privacy-admin` 以外の全 group に `privacy.*` deny、`privacy-admin` に `*` と `privacy.*`（deny 0）、menu `privacy-retention` / `privacy-dsar` は管理者だけ |

#### 3.1.7 完了条件

1. 上記テストが固定 SHA で 0 failure / 0 error / 0 skip（Runner 実行）。
2. 既定構成（flag OFF）で `/api/privacy/**` が 404 になり（登録済み prefix は `FeatureSwitchFilter`、未登録は `PrivacyAccessGuard`）、policy source の DB 読取が 0 回。
3. 業務 action の判定を確かめる既存 assertion は1つも変更しない。既存 assertion で変更するのは `ActionPermissionMatrixTest` の seed 件数3行（`permissionGroupSeedの内容そのものを検査する` の営業 deny 8→9、HR deny 7→8、マネージャー deny 8→9）だけで、Runner と Review は diff でこの3行以外の既存 assertion に変更が無いことを機械的に確認する。
4. 保持期間・処分方式などの法的な値が migration・設定既定値に1件も無い（Review で確認）。
5. `com.ses.privacy.foundation` の合成層の test（`PrivacyJournalLinearizationMySqlTest` 等）に回帰がない（Runner が既存 shard で確認）。
6. prod の `ProductionPolicyReadonlyQueryService` の挙動は単体テスト（fake port）でだけ確認する。dev の Demo は `UnavailablePolicyReadonlyQueryPort` を使うので、権限と flag の確認に限られる。

#### 3.1.8 Demo

1. 既定（flag OFF）で起動し、管理者で `GET /api/privacy/policies/evaluate?moduleCode=NF08_AI_SUMMARY&dataElementId=AI_PROMPT_LOG&targetAction=EXTERNAL_DISPATCH&targetProvider=gemini-adapter` を呼ぶ → 404。`GET /api/privacy/unknown-sub` も 404。
2. dev で `PRIVACY_RETENTION_ENABLED=true` として再起動し、`role-admin` だけの管理者で同じ API を呼ぶ → 403（`privacy.*` deny）。
3. 別の管理者が `PUT /api/permission-groups/users/{id}` で対象管理者を `privacy-admin` だけに割り当てる → 対象者の session が失効し、監査に group key が残り、既存の privacy-admin に通知が届く → 再ログインして同じ API を呼ぶ → 200、`decisionStatus=POLICY_UNKNOWN`。
4. 営業ユーザーに `privacy-admin` を割り当てようとする → 400。`role-admin` と `privacy-admin` の同時割当 → 409。
5. 起動失敗の確認は `PrivacyProductionConfigurationValidatorTest` と `ProductionSecurityConfigurationValidatorTest` の結果で示す。

**規模**: L〜XL（main 約24 file、test 約16 class、migration 2本 + H2 1本、messages 4本）。総括の判断で、W3-14a「F1」（F1 DDL・flag・Feature・決定ID・policy source Bean・説明API）→ W3-14b「PERM」（V193・権限・割当 guard・起動時検査・break-glass）に分けてよい。分ける場合、PERM の Demo は F1 の API を使うので順序は F1 → PERM で、DDL lane は2回に分かれる。

### 3.2 NF07-F2「read-only provider」

**目的**: DSAR 検索・dry-run・hold の帰属判定の母集団を作る本番の read-only provider と registry、第三者 redaction 規則の枠を用意する。規則・範囲の値は UNKNOWN のまま。外部 provider（Gemini、CloudSign、freee、mailbox、backup/replica）は登録しない＝scope 外として常に呼ばない。

**ファイル集合（見込み）**

- `com.ses.privacy.production.ProductionPrivacyProviderRegistry`（未登録 provider は `PROVIDER_UNREGISTERED`。合成 `PrivacyProviderRegistry` は変更しない）
- `com.ses.privacy.production.provider.*`: 初期候補 `EngineerProfileProvider`（`t_engineer` 系）、`CandidateProvider`（`t_candidate` 系）、`SysUserProvider`、`DocumentFileProvider`（`t_document` / `t_document_version` / `t_document_link` / `t_file_security_metadata`、`FileReferenceProvider` と `FileScopeValidationService` を経由）、`IngestionTextProvider`（`t_resume_ingestion` / `t_bp_availability_ingestion` / `t_project_ingestion` の `extracted_text`）、`AiLogProvider`（`t_ai_recommendation_run`、legacy `t_ai_log`）。初期集合は Plan Review で確定する
- 各 provider は読み取る列を明示した allow-list を持ち、`SELECT *` を使わない。`sys_user` の password hash、MFA 秘密、token 類は候補にも草案にも入れない（P3-8）
- 各 provider は「subject の導出規則」と「論理削除済み行も含めた stored name の解決」を提供する（A1H の hold 帰属判定と `PrivacyHoldFileReferenceProvider` が使う）
- `com.ses.privacy.production.redaction.ThirdPartyRedactor`（規則は D-06 の決定ID付き設定から読む。未設定なら全 field を「要手動確認」に回し、自動では出さない）
- `com.ses.privacy.production.PrivacyCoverageReport`（登録済み／未登録の data element と provider の一覧。`m_pii_data_element.provider_id IS NULL` を必ず報告する）
- `com.ses.privacy.production.retention.catalog.PrivacyCatalogService`、`PrivacyCatalogApiController`（`GET /api/privacy/catalog`、`privacy.catalog.view`）、`Feature.NF07_RETENTION` の apiPrefixes に `/api/privacy/catalog` を追加
- migration（DDL lane）: `V?__nf07_privacy_data_element_catalog_seed.sql`（初期 provider が扱う data element の構造行と `subject_attribution` だけを seed。owner・保持などは UNKNOWN）

**DDL 草案（番号なし）**

```sql
-- data element の構造行だけ。inventory_status は UNKNOWN、owner も UNKNOWN。保持期間は持たない。
-- tenant は sys_user に存在する tenant から取る（権限 group とは結び付けない）。
INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id, subject_attribution, inventory_ref)
SELECT t.tenant_id, d.data_element_id, d.storage_kind, d.storage_ref, d.provider_id, d.subject_attribution, d.inventory_ref
FROM (SELECT DISTINCT tenant_id FROM sys_user) t
JOIN (
  SELECT 'ENGINEER_PROFILE' AS data_element_id, 'DB_COLUMN' AS storage_kind, 't_engineer.(allow-list)' AS storage_ref,
         'engineer-profile' AS provider_id, 'ATTRIBUTABLE' AS subject_attribution, 'pii-inventory.md#t_engineer' AS inventory_ref
  -- UNION ALL ...（初期 provider の対象と、§3.4.1 の破壊的経路の data element。Plan Review で確定）
) d
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e WHERE e.tenant_id = t.tenant_id AND e.data_element_id = d.data_element_id);
```

**依存**: W3-14。DDL lane（seed がある場合）。`FileReferenceProvider` / `FileScopeValidationService` の既存規約。

**対象限定テスト**: `ProductionPrivacyProviderRegistryTest`（未登録・重複・外部 provider 登録の拒否）、provider ごとの H2 test（scope 外の行を返さない、他 tenant の行を返さない、`DataScopeService` と同じ母集団、write 0、allow-list 外の列を読まない、`sys_user` の秘密列が結果に出ない）、`ThirdPartyRedactorTest`（規則未設定で自動出力 0、規則ありでも第三者 field を出さない）、`PrivacyCoverageReportTest`、`PrivacyCatalogApiControllerTest`、`FeatureSwitchRouteCoverageTest`、共通 guard（`MigrationScriptIntegrityTest`、`MessageBundleConsistencyTest`）。MySQL では seed の重複投入なしを `FlywayNf07PrivacyF1MigrationTest` に追加する。

**完了条件**: provider の呼出が全て read-only（mapper の select だけ、write 0 を構造 test で確認）。外部 provider への接続コードが無い。未登録の data element が coverage report に必ず出る。各 provider の列 allow-list が test で固定されている。

**Demo**: privacy-admin で `GET /api/privacy/catalog` → 登録済み provider と未登録 data element（例: audit log、backup）が分かれて表示され、各 element の `subject_attribution` が見える。未登録側の理由が `PROVIDER_UNREGISTERED`。

**規模**: L（provider 6 前後、test 11 class 前後）。

### 3.3 NF07-A1J「Journal 本番 adapter と設定契約」

**目的**: hold の正本となる独立 Journal への本番接続を作る（W-6 の code 側）。合成 `JdbcPrivacyJournalAdapter` とその `requireTestProfile()` は変更しない。Witness / attestor は B2 で扱う。

**ファイル集合（見込み）**

- `com.ses.privacy.foundation.ProductionPrivacyJournalAdapter`（package-private。foundation の既存 package-private な canonical・hash 計算部品をそのまま使い、合成 adapter の class には手を入れない。JDBC 処理は本番用に別に書き、同一入力で合成 adapter と hash chain が一致することを同値性 test で固定する。生成は `ProductionPrivacyComponents` 経由だけ）。I-6 を守るため、合成 adapter からの共通部抽出は行わない（抽出すると合成層のコードが変わるため。重複は同値性 test で乖離を検出する）
- `com.ses.privacy.production.retention.hold.PrivacyJournalConnections`（専用の holder 型。内部に connection pool を持つが、Spring には `DataSource` 型の Bean として公開しない。application の DataSource 自動構成（`DataSourceAutoConfiguration` の `@ConditionalOnMissingBean(DataSource.class)`）と、単一候補を前提にする `JdbcTemplate`・MyBatis-Plus の自動構成に影響させないため。journal 設定がある場合だけ作り、flag とは独立にする）
- `PrivacyJournalStartupVerifier`（[`journal-witness-grant-design.md`](journal-witness-grant-design.md) §2.3 の起動時検査）
- Journal DDL: `src/main/resources/db/privacy-journal/V?__privacy_journal_core.sql`（application DB の `db/migration` とは別 location・別履歴表。test 用 `src/test/resources/privacy-journal/schema-test.sql` を基にする）
- Journal 用 Flyway job: Maven profile `privacy-journal-migrate`、設定 `ops/flyway/flyway-privacy-journal.conf`（locations は `classpath:db/privacy-journal` だけ、履歴表 `flyway_schema_history_privacy_journal`、application の callback は持たない）。`pom.xml` の変更を含む
- test: `PrivacyJournalMigrationIntegrityTest`（`MigrationScriptIntegrityTest` と同型）、`PrivacyJournalFlywayJobConfigurationTest`（`ProductionFlywayJobConfigurationTest` と同型。profile・conf・locations・callback なし・secret を含まないこと）
- deploy 手順: `.kiro/specs/production-launch/deploy-runbook.md` に Journal 用 Flyway job の節を追加

**DDL 草案（番号なし。Journal インスタンス側）**: 表の形は合成の `sf_journal_head` / `sf_journal_event` / `sf_journal_command` / `sf_journal_action` / `sf_journal_lease` を本番名（`pj_head` / `pj_event` / `pj_command` / `pj_action` / `pj_lease`）へ移したものとし、列・制約・collation（identity 列は binary NO PAD）は合成 schema と同一にする。差分は名前だけで、意味の変更は Plan Review を要する。

**依存**: W3-14（flag、決定ID束縛）。G-DBA のインスタンスは開放時に必要で、実装には不要（Testcontainers で検証）。

**対象限定テスト**: `ProductionPrivacyJournalAdapterMySqlTest`（`@Tag("mysql")`、3 container: app DB / journal / 予備。append・CAS・replay・ACK 不明・transaction 中断で head 不変、`HOLD_RELEASED` は本番 adapter でも拒否、合成 adapter との hash chain 同値性）、`PrivacyJournalStartupVerifierMySqlTest`（`innodb_flush_log_at_trx_commit`≠1、`sync_binlog`≠1、binlog 無効、read_only、app DB と同じ `server_uuid`、過剰権限・権限不足・role 付与の principal をそれぞれ与えて起動拒否）、`PrivacyJournalConnectionsContextTest`（journal 設定ありの完全な context で、application の mapper と `JdbcTemplate` が application DB に接続したまま（`SELECT DATABASE()` が application schema）であること、`DataSource` 型の Bean が1つだけであること。journal 設定が無ければ pool 生成 0・接続試行 0）、`PrivacyJournalMigrationIntegrityTest`、`PrivacyJournalFlywayJobConfigurationTest`、既存 `PrivacyJournalLinearizationMySqlTest` の回帰、`MySqlTestShardInventoryTest`。

**完了条件**: journal 設定が無ければ接続試行 0。Journal 設定が app DB を指すと起動失敗（retention flag ON の場合）または警告と gate の `UNAVAILABLE`（flag OFF の場合）。`DataSource` 型の Bean は application の1つだけ。合成 adapter のコードに diff が無い。

**Demo**: Testcontainers の journal に対し、本番 adapter で HOLD_STARTED を append → 同じ command ID の再送が元の ACK を返す → `innodb_flush_log_at_trx_commit=2` の container では起動検査が失敗する、を test 結果で示す。

**規模**: L（main 7 前後、MySQL test 3 class、Fast test 3 class、pom 1）。

### 3.4 NF07-A1H「保全 hold（開始のみ）と破壊的経路への接続」

**目的**: privacy-admin が subject / data element に hold を掛けられるようにし、実際にデータを消す全ての経路が hold を尊重するようにする。同時に、hold に帰属できない行を止めて期限超過の保持を生まないようにする。解除は本 batch では作らない（NF07-REL を A1H の直後に前倒しで実装する。開放は D-05・D-03 の署名後）。

#### 3.4.1 破壊的経路の一覧と接続方法（P1-1）

`DocumentRetentionJob` は処分申請（PENDING）を作るだけで、実際の物理削除は管理者の手動実行 `DocumentServiceImpl.executeDisposal` で起きる。このため接続点は「申請を作る所」ではなく「消す所」を主とする。

| # | 経路 | 起動 | 処理 | subject の導出 | data element | NF07 hold の扱い |
|---|---|---|---|---|---|---|
| D1 | `DocumentServiceImpl.executeDisposal`（`POST /api/documents/disposal/{requestId}/execute`） | 管理者の手動（承認済み申請） | 文書を DISPOSED にし、全 version の実体を物理削除 | `t_document_link` の target のうち人を指すもの（ENGINEER / CANDIDATE / SYS_USER 等） | `DOCUMENT:<document_type>` | 同一 tx 内、更新の直前に照会する。SUBJECT または DATA_ELEMENT の hold に当たれば 409（`error.privacy.holdActive`）。gate が `UNAVAILABLE` でも 409 |
| D2 | `DocumentServiceImpl.requestDisposal` | 管理者の手動 | PENDING 申請の作成（非破壊） | D1 と同じ | D1 と同じ | hold に当たれば 409（申請を作らない） |
| D3 | `DocumentRetentionJob` | 自動（日次） | 期限到来文書の PENDING 申請作成（非破壊） | D1 と同じ | D1 と同じ | 当たる文書は申請を作らず skip し、件数を警告する |
| D4 | `ResumeRetentionCleanupServiceImpl`（履歴取込） | 自動（日次） | 原本の実体削除と `extracted_text` 消去 | `converted_engineer_id` → ENGINEER、無ければ `candidate_id` → CANDIDATE、どちらも無ければ帰属不能 | `RESUME_INGESTION_ORIGINAL`、`RESUME_INGESTION_TEXT` | 帰属できる行は SUBJECT hold に当たる行だけ skip。帰属不能な行は DATA_ELEMENT hold があるときだけ止まる |
| D5 | 同（BP 空き取込） | 自動（日次） | `extracted_text` 消去 | `converted_availability_id` → BP_AVAILABILITY、無ければ帰属不能 | `BP_AVAILABILITY_INGESTION_TEXT` | D4 と同じ |
| D6 | `AiRecommendationRetentionScheduler`（`ai.retention.scheduler-enabled=true` のときだけ Bean がある） | 自動 | redacted summary の purge | F2 の `AiLogProvider` と同じ規則。導出できなければ帰属不能 | `AI_REDACTED_SUMMARY` | D4 と同じ。接続は Bean の有無に依存しない service 側（`AiRecommendationRetentionService`）に入れ、Bean が無い構成でも test が成立するようにする |
| D7 | `IntegrationHubRetentionPurgeScheduler` の record purge（IDEMPOTENCY / DELIVERY / INBOUND / INBOUND_REPLAY / AUDIT） | 自動（60秒） | 保持期限切れ record の削除 | 帰属不能（record kind × retention class 単位） | `INTEGRATION_HUB_<KIND>` | DATA_ELEMENT hold があるときだけ、その kind を止める。SUBJECT hold では止めない |
| D8 | 同 nonce purge（`ApiNonceReplayService.purgeExpired`） | 自動（60秒） | replay 防止用 nonce の削除 | 個人情報を含まない | — | gate に接続しない |
| D9 | `ExternalApiReadSnapshotPurgeScheduler` | 自動（60秒） | read snapshot の削除 | 帰属不能 | `EXTERNAL_API_READ_SNAPSHOT` | D7 と同じ。F2 の inventory で個人情報を含まないと確認できれば D8 と同じく接続しない |
| D10 | `FileCleanupService.cleanupOrphanFiles()`（`FileCleanupScheduler` と `DocumentArchiveFileCleanupScheduler` の両方が呼ぶ） | 自動 | どの `FileReferenceProvider` も参照しない実体の削除 | 参照元の行が無いので、この経路自体では導出できない | — | 両 scheduler を同じ扱いにするため、scheduler ではなく `FileReferenceProvider` の側で守る。新規 `PrivacyHoldFileReferenceProvider` が、有効な hold の subject / data element に帰属する stored name（F2 provider が論理削除済みの行も含めて解決）を「参照あり」として返す。gate が `UNAVAILABLE` のときは provider が例外を返し、cleanup をその回は中止させる（既存の cleanup が provider 例外で中止することを A1H で確認し、しない場合は合わせて直す） |
| D11 | 業務 record の削除で file 参照が外れる経路 | 手動 | 参照が外れた実体は D10 で消える | 各 `FileReferenceProvider` | — | `FileReferenceProvider` ごとに、論理削除行を参照に数えるかを A1H で一覧化する。held subject の実体は D10 の provider で守る。参照元の行が物理削除された実体は帰属できず守れない（残余、§9 R-15） |
| D12 | upload 失敗時の補償削除（`DocumentServiceImpl` の import・rollback 補償、`DocumentMetadataCommitService`、`ResumeIngestionServiceImpl.createJob`） | 同一要求内 | 業務 record に結び付く前の新規実体の削除 | — | — | 対象外（hold の対象になり得るデータになる前の実体） |
| D13 | 他 NF の破壊的経路 | — | — | — | — | A1H 着手時に本表へ追記して同じ規則で分類する。対象外とするものは理由を書いて D-05 owner の受理を得る |

#### 3.4.2 NF07 hold と `legal_hold_flag` の関係（P1-1）

- `t_document.legal_hold_flag`（V67）は文書台帳の文書単位の保全で、文書の担当者が `placeLegalHold` で操作する。NF07 hold は subject / data element 単位の保全で、privacy-admin が操作し、正本は Journal。
- 両者は互いに書き換えない。NF07 hold の開始時に `legal_hold_flag` を立てない（正本が2つになるため）。
- 判定は独立に両方行い、どちらかに当たれば処分しない（D1〜D3）。`legal_hold_flag` に当たったときの既存の応答（`error.document.legalHoldActive`）は変えず、NF07 hold に当たったときは `error.privacy.holdActive` を返す。
- 文書画面の legal hold 表示は `legal_hold_flag` のまま。NF07 hold は「privacy hold 中」として別に表示する（A1H では任意。表示する場合も subject 識別子は出さない）。

#### 3.4.3 帰属できない行の扱い（P1-2）

採用案は **(b)+(c)** で、補助として **(a)** を行う。

| 案 | 内容 | 長所 | 短所 | 判断 |
|---|---|---|---|---|
| (a) | NF07-REL（解除）を第1期に前倒し | 誤った hold を戻せる | 解除 proof は D-03（署名鍵）と D-05 を要するので、開放が hold より遅れれば第1期の恒久停止は残る。帰属不能行の全停止そのものは解除しない限り続き、根本解決にならない | 補助として採る（実装を A1H の直後に前倒し、開放は署名後） |
| (b) | 停止範囲を、帰属を確認できる類別に限る | 過剰停止を構造的に無くせる | 帰属不能な類別にある held subject の行は、SUBJECT hold だけでは守れない | 採る（中核） |
| (c) | purge ごとに「関連し得ない」判定を持つ | 個人情報を含まない purge を完全に外せる | 経路ごとの分類と保守が要る | 採る（(b) の具体化） |
| 現行（R1） | subject 不明の行は全て止める | 取りこぼしが無い | 最初の SUBJECT hold で多数の purge が恒久停止し、期限超過の保持になる | 採らない |

規則:

1. §3.4.1 の各経路に data element と subject 導出規則を宣言する。`m_pii_data_element.subject_attribution` に ATTRIBUTABLE / NOT_ATTRIBUTABLE / NO_PERSONAL_DATA を持たせる（F2 の seed）。
2. NO_PERSONAL_DATA の purge（D8 など）は gate に接続しない。
3. 帰属できる行: SUBJECT hold に当たる行だけを skip し、他の行は従来どおり消す。
4. 帰属できない行（NOT_ATTRIBUTABLE の element、または ATTRIBUTABLE の element でも導出に必要な列が NULL の行）: SUBJECT hold では止めない。その data element に DATA_ELEMENT hold があるときだけ、その経路のその element を止める。
5. SUBJECT hold の開始画面には、帰属できない data element と tenant 内の件数（subject ごとではない）を表示する。privacy-admin は必要なら DATA_ELEMENT hold を明示的に追加する。DATA_ELEMENT hold を追加するときは「その element の purge は解除が開放されるまで止まる」ことを確認させ、required 監査に残す。
6. 許可する hold の種別（SUBJECT / DATA_ELEMENT）は D-05 の決定で設定する（`privacy.hold.allowed-target-types`。未設定なら hold 開始自体を `DECISION_UNKNOWN` で拒否）。「帰属できない element の行は SUBJECT hold で守られない」という残余は、D-05 の署名時に owner が受理する（§7）。
7. 警告: 経路ごとの「hold で skip した件数」「`UNAVAILABLE` で skip した回数と継続時間」を監視項目にする。閾値と通報先は SRE が決める（UNKNOWN）。

依頼者の新たな判断は不要（残余の受理は D-05 の署名項目として扱う）。

#### 3.4.4 gate・pending・reconciler（P2-6、P3-6）

- `PrivacyHoldGate`（判定は flag に依存させない。flag を OFF に戻しても、既に掛けた hold は守る）
  - `t_privacy_projection_state` に当該 tenant の行が無い（hold 機構を一度も使っていない）→ 「hold なし」。この判定は Journal の起動時検査の結果より優先する（未使用の tenant では Journal の不達が削除を止めない）。追加の照会はこの1回だけで、既存挙動は変わらない。
  - 行がある → Journal の head と `last_applied_seq` / `last_applied_hash` が一致し、`pending_command_id` が NULL であることを確認してから hold 集合を返す。一致しない・pending が残る・Journal 不達・journal 設定が無い場合は `UNAVAILABLE`。batch 経路はその回の gate 対象の削除を skip し、手動経路（D1・D2）は 409 を返す。hold 機構を使った後で journal 設定が無くなっている場合は、起動時に警告を出す。
  - batch 経路の gate は batch 開始時に1回 snapshot を取る。batch の途中で開始された hold はその回に反映されず、次回から効く（最大で1回分の実行時間の窓。既知の残余として受け入れ、hold 開始画面に表示する）。手動経路（D1・D2）は削除・申請作成と同じ tx の中で照会するので、この窓は無い。
- `com.ses.privacy.production.retention.hold.PrivacyHoldService`（開始の手順）
  1. 決定 D-05 の ID と `allowed-target-types`、journal の可用性、`privacy.hold.manage` を確認する。
  2. 独立 tx（`REQUIRES_NEW`）で `t_privacy_projection_state` の行を作る（無ければ）か `SELECT ... FOR UPDATE` で取り、`UPDATE ... SET pending_command_id = ?, pending_since = ? WHERE tenant_id = ? AND journal_id = ? AND pending_command_id IS NULL` の CAS で pending を記録して commit する。CAS が0件なら 409（`error.privacy.holdStartInProgress`。同じ tenant の hold 開始は1件ずつ直列化される）。
  3. Journal に HOLD_STARTED を append する（command ID は 2. の値）。
  4. ACK 後、別 tx で `t_legal_hold` へ projection し、`last_applied_*` を進め、`WHERE pending_command_id = ?` の CAS で pending を NULL に戻す。ACK 不明なら pending を残したまま 503 を返し、reconciler に任せる。
  5. 監査は `recordRequired`（失敗なら操作失敗）。監査本文に subject 識別子は入れず、hold ID・data element・ticket 参照だけを入れる。
  - 先に書く pending 記録は成功応答ではなく、gate を fail-closed に倒すための印である（SF-04a の「local DB 更新を先に成功応答しない」に反しない）。
- `PrivacyHoldProjectionReconciler`（新規。起動時と定期実行、`@SchedulerLock` 付き）
  - Journal の `last_applied_seq` より後の event を projection へ追い付かせる（ACK 後に projection が失敗した hold を補う）。
  - pending が残る行は command ID で Journal を照会し、ACK 済みなら projection して pending を消す。Journal に command が無く、`pending_since` が `privacy.journal.pending-timeout`（append の timeout より長い値。運用で決める）を過ぎていれば pending を消す。Journal 不達なら pending を残す（gate は `UNAVAILABLE` のまま）。
  - 各判定を required 監査に残し、`UNAVAILABLE` が続く場合は §3.4.3 の7. の監視に載せる。
  - gate と同じく flag に依存させないので、`FeatureSwitchRouteCoverageTest` では理由付きで対象外 scheduler に登録する。

#### 3.4.5 ファイル集合（見込み）

- `com.ses.privacy.production.retention.hold.PrivacyHoldService`、`PrivacyHoldGate`、`PrivacyHoldProjectionReconciler`、`PrivacyHoldFileReferenceProvider`、`HoldSubjectResolver`（§3.4.1 の導出規則。F2 provider を使う）
- `PrivacyHoldApiController`（`GET /api/privacy/holds`、`POST /api/privacy/holds`）、`templates/privacy/holds.html`、`static/js/modules/privacy-holds.js`、`templates/layout/sidebar.html`（`allowedMenus.contains('privacy-retention')` に加えて privacy action の判定結果を見る。§6.8）
- `Feature.NF07_RETENTION` に `/api/privacy/holds` と `/privacy/holds` を追加、`FeatureSwitchRouteCoverageTest` の sidebar 期待集合に `NF07_RETENTION` を追加、reconciler を対象外 scheduler として登録
- 接続する既存コード: `DocumentServiceImpl`（D1・D2）、`DocumentRetentionJob`（D3）、`ResumeRetentionCleanupServiceImpl`（D4・D5）、`AiRecommendationRetentionService`（D6）、`ApiRetentionPurgeService` または `IntegrationHubRetentionPurgeScheduler`（D7）、`ExternalApiReadSnapshotPurgeService`（D9）、`FileCleanupService`（D10 の provider 例外時の中止）
- messages 4本

**DDL**: 無し（W3-14 の `t_legal_hold` / `t_privacy_projection_state` / `m_pii_data_element` を使う）。

**依存**: NF07-A1J、NF07-F2（帰属判定と D10 の stored name 解決）、W3-14。既存 scheduler に触れるため、NF10 の scheduler 方針（test profile では scheduler 無効、対象 test が経路を直接呼ぶ）に従い、`SchedulingDisabledDrainTest`（NF05-C 所有）は変更しない。

**対象限定テスト**:

- `PrivacyHoldServiceTest`（D-05 決定ID・`allowed-target-types` の欠落で `DECISION_UNKNOWN`、journal 不達で 503、ACK 前に projection を書かない、監査失敗で操作失敗、監査本文に subject 識別子が無い）
- `PrivacyHoldServiceMySqlTest`（`@Tag("mysql")`。ACK 不明時の再送で二重 hold を作らない、同じ tenant の並行開始で後者が 409、pending の CAS）
- `PrivacyHoldProjectionReconcilerMySqlTest`（`@Tag("mysql")`。pending 残り＋ACK 済み → projection して復旧、pending 残り＋未 append → timeout 後に解除、Journal 不達 → pending を残す、projection 欠落の追い付き）
- `PrivacyHoldGateTest`（未使用 tenant は照会1回で「hold なし」かつ Journal 不達でも「hold なし」、追随遅れ・pending 残り・不達・journal 設定なしで `UNAVAILABLE`、hold を掛けた後に flag を OFF にしても hold を守る）
- 経路ごとの test（新規を含む）: `DocumentServiceImplDisposalHoldTest`（D1・D2: NF07 hold と `legal_hold_flag` をそれぞれ単独・両方で検証、`UNAVAILABLE` で 409）、`DocumentRetentionJobTest`（新規、D3）、`ResumeRetentionCleanupServiceImplTest`（新規、D4・D5: 帰属できる held 行だけ残る、帰属できない行は SUBJECT hold では消える、DATA_ELEMENT hold で止まる）、`AiRecommendationRetentionSchedulerTest`（既存に追加、D6。Bean が無い構成も）、`IntegrationHubRetentionPurgeSchedulerTest`（D7・D8: nonce purge は hold に関係なく動く）、`ExternalApiReadSnapshotPurgeSchedulerTest`（新規、D9）、`PrivacyHoldFileReferenceProviderTest` と `DocumentArchiveFileCleanupSchedulerTest`（新規）・`FileCleanupScheduler` の test（D10: 両 scheduler で held subject の実体が残る、provider 例外でその回を中止）
- `PrivacyHoldApiControllerTest`、`ActionPermissionMatrixTest`（`privacy.hold.*`）、`FeatureSwitchRouteCoverageTest`、`StaticAssetLocalityTest`、`MessageBundleConsistencyTest`

**完了条件**: hold 機構を一度も使っていない間は、接続した経路の挙動が変わらない（増える SQL は gate の照会1回だけ）。hold 開始は Journal の ACK を先に取る。flag の ON/OFF で既存 hold の効力が変わらない。SUBJECT hold があっても、帰属できない行と個人情報を含まない purge は止まらない。D1 で NF07 hold に当たる文書は処分できない。解除の endpoint が存在しない（`POST /api/privacy/holds/{id}/release` は 404）。

**Demo**:

1. dev で retention flag ON、D-05 の試験用決定IDと `allowed-target-types=SUBJECT,DATA_ELEMENT` を設定し、application DB とは別の MySQL instance を dev 用 journal として設定 → privacy-admin が `ENGINEER:<id>` に hold を開始 → 開始画面に帰属できない element と件数が出る → 一覧に ACTIVE で出る。
2. 対象者に `t_document_link` で結び付いた文書の承認済み処分申請を管理者が実行 → 409（`error.privacy.holdActive`）。`legal_hold_flag` は 0 のまま。
3. 対象者と他者の取込履歴が保持日数を過ぎた状態で `ResumeRetentionCleanupServiceImpl` を手動実行 → 対象者の分は残り、他者の分と、どの subject にも帰属しない未変換の取込は消える。integration hub の nonce purge は動き続ける。
4. journal を停止して再実行 → 帰属できる経路はその回を全て skip し警告が出る。D1 は 409。hold を使っていない別 tenant では従来どおり消える。
5. journal を戻し、retention flag だけを OFF（journal 設定は残す）にして再起動・再実行 → 対象者の分は引き続き残る。
6. pending を残した状態（append 前に process を止める）で再起動 → reconciler が pending を解消し、gate が復帰する。

**規模**: XL（main 12 前後、既存コードの接続 8 か所、test 15 class 前後、画面1）。必要なら「hold 開始・gate・reconciler」と「経路の接続」に分ける（Plan Review で決める）。

### 3.5 NF07-A2「DSAR（半手動）」

**目的**: DSAR の受付から交付記録までを system で追跡する。本人確認・名寄せ・交付は人が行い、system は記録・期限管理・read-only 検索・redaction 済み草案の作成・二者承認を受け持つ。第1期で自動処理するのは開示請求（ACCESS）の草案作成までで、訂正・削除・利用停止・異議は受付・期限管理・手動対応の記録だけを行う（system は処分を実行しない）。規模が XL なので A2a と A2b に分ける。

**状態遷移**（`PrivacyCaseState` の部分集合を DB 値に使う）

| 状態 | 入る条件 | 担当 | batch |
|---|---|---|---|
| REQUESTED | 受付（`intake_ticket_ref` 必須。D-06 の期限規則から `due_date` を計算） | privacy-admin | A2a |
| IDENTITY_VERIFIED | 本人確認の記録（`identity_evidence_ref`、適用した D-06 基準の決定ID）。基準未設定なら `DECISION_UNKNOWN` で進めない | privacy-admin | A2a |
| SCOPED | 名寄せの人手裁定（候補は F2 provider が提示し、氏名一致だけでは自動結合しない。SELECTED が1件でない場合は BLOCKED）と scope snapshot（provider 一覧・data element・as-of の hash） | privacy-admin | A2a |
| PREVIEWED | F2 provider の read-only 検索 → redaction 済み草案 package（未登録 provider・規則未設定の項目は「要手動確認」一覧として添付） | privacy-admin（作成者 = `prepared_by`） | A2b |
| APPROVAL_PENDING → APPROVED | 作成者以外の privacy-admin が特定の package を承認（DB の CHECK と service の両方で作成者≠承認者。承認した package の id と hash を request に書く。§3.1.5） | 別の privacy-admin | A2b |
| CONFIRMED | system 外での交付後に交付記録（方法・証跡参照、承認 package の hash 照合）。package は記録と同時に PURGED | privacy-admin | A2b |
| BLOCKED / UNKNOWN / CANCELLED | 本人不明・複数候補・redaction 不能・provider 障害・取下げ | — | A2a/A2b |

再申立・再開は `reopen_of_request_id` で新 case を作る（元 case は書き換えない）。

#### 3.5.1 NF07-A2a「受付〜SCOPED」

- ファイル集合: `com.ses.privacy.production.dsar.PrivacyRequestService`（受付・本人確認記録・名寄せ裁定・scope snapshot）、`PrivacyDsarDeadlineScheduler`（期限接近を privacy-admin へ通知。feature OFF・test profile では動かない。`FeatureSwitchRouteCoverageTest` に gated scheduler として登録）、`PrivacyDsarApiController`（受付〜SCOPED の endpoint）、`templates/privacy/dsar/*.html`、`static/js/modules/privacy-dsar.js`、`templates/layout/sidebar.html`（`allowedMenus.contains('privacy-dsar')` と privacy action の判定）、`Feature.NF07_DSAR` に `/api/privacy/dsar` と `/privacy/dsar` を追加、sidebar 期待集合に `NF07_DSAR` を追加、`com.ses.entity.privacy.*` / `com.ses.mapper.privacy.*`（request / subject link / action）、messages 4本
- 通知と監査（P3-9）: 通知の宛先は role ではなく `privacy-admin` group の所属者として解決し、本文に subject 識別子・氏名を入れない（request 番号と期限だけ）。監査本文も request 番号と状態遷移だけにする（`/api/audit-logs` は `privacy-admin` 以外の管理者も見られるため）。
- 依存: NF07-F2（候補提示）、W3-14、NF07-A1H（共有 file の直列化。機能上は hold の表示だけ）。
- 対象限定テスト: `PrivacyRequestServiceTest`（各遷移の前提、D-06 決定ID欠落で進めない、同姓同名で自動結合しない、CAS 競合で 409、監査失敗で操作失敗、監査と通知の本文に subject 識別子が無い）、`PrivacyRequestServiceMySqlTest`（`@Tag("mysql")`、並行裁定の競合、複合 FK による tenant 越えの拒否）、`PrivacyDsarDeadlineSchedulerTest`（宛先が group 所属者で、role 管理者全員ではない）、`PrivacyDsarApiControllerTest`、`ActionPermissionMatrixTest`（`privacy.dsar.view` / `privacy.dsar.manage`）、`FeatureSwitchRouteCoverageTest`、`StaticAssetLocalityTest`、`MessageBundleConsistencyTest`
- 完了条件: 依頼者の連絡先・本人確認書類の本体を保存しない。SELECTED が1件でなければ SCOPED に進まない。
- Demo: dsar flag ON、D-06 の試験用決定IDと期限規則を dev に設定 → privacy-admin A が ACCESS 請求を受付 → 本人確認を記録 → 候補から本人を選ぶ（同姓の別人は自動で結合されない）→ SCOPED。期限接近の通知が privacy-admin だけに届く。
- 規模: L（main 8 前後、画面2、test 8 class 前後）。

#### 3.5.2 NF07-A2b「検索〜交付記録」（P2-4、P2-5）

- ファイル集合: `com.ses.privacy.production.dsar.PrivacySearchService`（F2 を呼ぶ）、`PrivacyExportPackageService`（`FileStorageService` で quarantine→published。package の最長保存期間 `privacy.dsar.package-max-age` は D-06 で決める値で、未設定なら草案を作らない。期限超過・交付記録で PURGED）、承認と package の束縛（§3.1.5）、専用ダウンロード `GET /api/privacy/dsar/{id}/package/download`（`PrivacyDsarApiController`）、`PrivacyExportPackageFileReferenceProvider`（`FileReferenceProvider` 登録。登録しないと `FileCleanupScheduler` に消される。ダウンロードの許可には使わない）、`FileScopeValidationService` の変更、`com.ses.entity.privacy.PrivacyExportPackage` / mapper、messages 4本
- 汎用 file 経路の遮断（P2-5）: `FileScopeValidationService.assertDownloadAllowed` の先頭（既存の `assertImportJobNotAccessibleThroughGenericFilePath` と同じ位置、管理者の通過分岐より前）に `assertPrivacyPackageNotAccessibleThroughGenericFilePath(storedName)` を置き、`t_privacy_export_package` の `stored_name` は role・group に関係なく一律拒否する。`POST /api/files/{storedName}/rescan` も同じく拒否する。package を取得できるのは専用ダウンロードだけで、そこで flag、`privacy.dsar.export`、tenant、request の状態（APPROVED）、package id と `approved_package_id` の一致、`content_sha256` と `approved_content_sha256` の一致、file の `PUBLISHED`+`CLEAN` を確認する。
- 依存: NF07-A2a、NF07-F2。
- 対象限定テスト: `PrivacySearchServiceTest`（他 tenant 0 件、未登録 provider を報告）、`PrivacyExportPackageServiceTest`（第三者情報 0、規則未設定で自動出力 0、交付記録で PURGED、`purge_after` 超過で削除、承認されていない package は交付記録できない）、`PrivacyRequestApprovalMySqlTest`（`@Tag("mysql")`、二重承認・二重交付記録の競合、`prepared_by` NULL での承認が DB で拒否される、承認後に作った別 package の hash 不一致でダウンロード拒否）、`PrivacyExportPackageFileReferenceProviderTest`、`FileScopeValidationServiceTest`（`GET /api/files/{storedName}` と rescan が営業・要員・管理者・privacy-admin の誰でも拒否、専用経路だけ許可）、`PrivacyDsarApiControllerTest`（専用ダウンロードの各拒否条件）、`ActionPermissionMatrixTest`（`privacy.dsar.approve` / `privacy.dsar.export`）、`MessageBundleConsistencyTest`
- 完了条件: system から外部への送信経路が無い。第三者情報を含む項目は自動で package に入らない。承認者≠作成者が DB と service の両方で強制される。package は汎用 file 経路から取得できない。
- Demo: (1) A2a の case を検索 → 草案作成（「要手動確認」一覧に未登録 provider が並ぶ）。(2) A 自身の承認は拒否される → privacy-admin B が承認 → `GET /api/files/{storedName}` は B でも拒否 → A が専用ダウンロードで取得 → 交付を記録 → package が PURGED になる。
- 規模: L（main 7 前後、test 9 class 前後）。

---

## 4. 署名前に実装してよいが開放は署名後の batch と、署名後に着手する batch

| batch | 内容 | ファイル集合（見込み） | DDL 草案 | 依存 | 対象限定テスト | 完了条件 / Demo | 規模 |
|---|---|---|---|---|---|---|---|
| NF07-REL（W-9。実装は A1H の直後に前倒し、開放は D-05・D-03 の署名後） | hold 解除 proof（`VerifiedHoldRelease`、[`sf-w2-protocol.md`](sf-w2-protocol.md) §11.1）と解除 API | `VerifiedHoldRelease`、verifier、本番 adapter の `HOLD_RELEASED`、`POST /api/privacy/holds/{id}/release`（`privacy.hold.release`） | 無し | NF07-A1H、別 Plan Review、試験は test-only 鍵 | §11.1 の拒否条件全件、MySQL で head/hold revision CAS | 要求者・発行者・承認者の分離。Demo: 二者の署名付き proof で解除 → 同 command の再送は元 ACK | M |
| NF07-REG（W-2。前倒し可、開放は署名後） | 署名済み envelope の登録（verify-then-store）と、別の privacy-admin による current 切替 | `PrivacyPolicyRegistrationService`、`PrivacyPolicyApiController` に `POST /api/privacy/policies`・`POST /api/privacy/policies/{id}/activate`（`privacy.policy.manage`） | 無し（F1 を使う） | W3-14、試験は test-only 鍵 | `PrivacyPolicyRegistrationServiceTest`（改竄・未知 anchor・失効 UNKNOWN・SoD・二重 current の CAS）、MySQL で current 切替の並行 | 登録者≠有効化者、旧 current は同一 tx で SUPERSEDED。Demo: test 鍵で署名した envelope を登録 → 別人が有効化 → 説明 API が `APPROVED_ACTIVE` | M |
| NF07-TRUST（W-3。前倒しは D-03 の方式決定後） | KMS/HSM 連携の anchor registry、CRL/OCSP の失効 provider | D-03 で決まる方式に依存（`KmsPolicyTrustRegistry`、`OcspRevocationStatusProvider` 等） | 無し | D-03 の方式 | 鍵 rotation の重複期間、失効伝播、provider 障害で UNKNOWN | 失効が即時に `POLICY_REVOKED` へ反映 | M〜L |
| NF07-NF08-LINK（前倒し可。**NF08 外部送信の開放前に必須**） | NF08 の送信 gate が NF07 policy を参照する（§8） | NF08 側 `AiProductionApprovalGate` / `GeminiTextServiceImpl`（NF08-1 の所有者と調整）、NF07 側 `PolicyEnforcementPort`（説明 DTO を返さない内部判定） | 無し | W3-14、NF08-1 の共有 file 調整 | NF07 の flag OFF・UNKNOWN・REVOKED・EXPIRED・DRIFT・policyId 不一致で送信拒否 | §8 の手順 | S〜M |
| B1（W-10。署名後に着手） | 処分 batch: item ごとの CAS・冪等・再送・部分失敗・誤対象取消、approved policy の allow-list、緊急停止、子スイッチ `privacy.retention.disposition.enabled`（親は retention） | `DispositionJobService`、worker、outbox（SF-W3）、DB 監査 | 下記 | D-02、D-04、D-09、全 gate、NF07-REG | SF-W3 の Demo 全件、hold との線形化 | flag 既定 OFF、人手承認の無い batch は拒否 | XL |
| B2（W-11。署名後に着手） | Witness/attestor の本番化、recovery の本番配線、unquarantine 手続、DB+binary 同時点 restore 演習 | `ProductionLatestWitnessAdapter`、attestor（鍵は D-03）、`PrivacyRecoveryService` の本番入口、unquarantine API（D-07 の承認者） | Witness インスタンス側 DDL（別 location） | D-07、G-BKP、G-INC、W-6 の witness インスタンス | slice-d §7.3 の未完境界の全件 | restore 後は quarantine を自動解除しない | XL |
| M（W-13） | release 判定 | release 固定 SHA での coverage scan（未分類 0・欠落 0 を確認する）、全 gate、Go/No-Go | 無し | 全署名 | CI 全 job、PITR | Go/No-Go 記録 | M |

B1 の DDL 草案（番号なし）:

```sql
CREATE TABLE t_disposition_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, job_key VARCHAR(64) NOT NULL,
  policy_id VARCHAR(128) NOT NULL, policy_revision VARCHAR(64) NOT NULL, method VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL, requested_by BIGINT NOT NULL, approved_by BIGINT NULL, approved_at DATETIME(6) NULL,
  emergency_stopped TINYINT NOT NULL DEFAULT 0, row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_disposition_job UNIQUE (tenant_id, job_key),
  CONSTRAINT uk_disposition_job_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_disposition_job_sod CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE TABLE t_disposition_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, job_id BIGINT NOT NULL,
  data_element_id VARCHAR(128) NOT NULL, target_ref VARCHAR(128) NOT NULL, target_version_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL, idempotency_key VARCHAR(128) NOT NULL, claim_token VARCHAR(64) NULL,
  lease_until DATETIME(6) NULL, attempt INT NOT NULL DEFAULT 0, result_evidence_ref VARCHAR(128) NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_disposition_item UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_disposition_item_job FOREIGN KEY (tenant_id, job_id) REFERENCES t_disposition_job(tenant_id, id)
);
```

---

## 5. flag と UNKNOWN の扱い

### 5.1 flag

| key（環境変数） | 既定 | OFF のとき | ON にしたとき追加で要るもの（prod の起動時検査） |
|---|---|---|---|
| `privacy.retention.enabled`（`PRIVACY_RETENTION_ENABLED`） | false | `NF07_RETENTION` に登録した `/api/privacy/policies`・`/api/privacy/catalog`・`/api/privacy/holds`・`/privacy/holds` が 404（`FeatureSwitchFilter`）。未登録の `/api/privacy/<x>` も `PrivacyAccessGuard` が 404。policy source は読まずに `POLICY_UNKNOWN`/`SOURCE_DISABLED`。`PrivacyHoldGate` の判定は flag に依存しない（掛けた hold は OFF でも守る。§3.4.4） | D-01・D-09 の決定ID、`privacy-admin` group が存在し有効な管理者が2名以上所属、hold を使うなら journal 設定 |
| `privacy.dsar.enabled`（`PRIVACY_DSAR_ENABLED`） | false | `/api/privacy/dsar`・`/privacy/dsar` が 404、期限 scheduler 停止 | D-01・D-06・D-09 の決定ID、`privacy.dsar.due-days` と `privacy.dsar.package-max-age`、privacy-admin 2名以上、F2 provider が1件以上登録済み |
| `privacy.retention.disposition.enabled`（B1 で追加） | false | 処分 batch の入口と worker 停止 | 親が ON、D-02・D-04 の決定ID、緊急停止の設定 |

`Feature` への登録の仕方は §3.1.2。flag は公開可否であって権限ではないので、ON でも §6 の権限が別に要る。prod では flag の状態に関係なく §3.1.3 の privacy deny 不変条件を検査する。

### 5.2 UNKNOWN の理由コード（capability 単位の拒否）

| capability | 必要な決定・設定 | 欠けたときの結果 |
|---|---|---|
| policy の説明照会 | 無し（flag ON） | 値が無ければ `POLICY_UNKNOWN`（`POLICY_NOT_FOUND`、`REVOCATION_STATUS_UNKNOWN` など既存理由） |
| policy の登録・有効化（REG） | D-01、D-02、D-03 の anchor | 登録拒否（`UNKNOWN_TRUST_ANCHOR` 等） |
| hold 開始（A1H） | D-01、D-05（`allowed-target-types` を含む）、journal | `DECISION_UNKNOWN`（409）／`JOURNAL_UNAVAILABLE`（503）／`holdStartInProgress`（409） |
| hold 解除（REL） | D-05、D-03、解除 proof | 第1期（A1H）は endpoint 自体が無い。REL マージ後も署名まで拒否 |
| DSAR の本人確認記録以降（A2a） | D-01、D-06 | `DECISION_UNKNOWN`（409） |
| DSAR 草案 package（A2b） | D-06 の redaction 規則と `package-max-age` | 草案を作らない／自動出力 0（要手動確認へ回す） |
| 処分（B1） | D-02、D-04、D-09、子スイッチ | 入口 404 ／ `POLICY_UNKNOWN` |
| unquarantine（B2） | D-07 | 隔離を維持 |
| NF08 外部送信 | NF07-REG・NF07-TRUST・NF07-NF08-LINK のマージ、`privacy.retention.enabled=true`、NF07 policy が `APPROVED_ACTIVE`、NF08 の承認設定 | 送信拒否（§8） |

決定IDは `privacy.approval.d01.decision-id` / `privacy.approval.d01.signed-on` 〜 `d09` の形で設定する（環境変数 `PRIVACY_APPROVAL_D01_DECISION_ID` 等）。検査規則は NF08 の `AiExternalSendApproval`（ID の形式、`UNKNOWN`・`TBD`・`PENDING` 等の placeholder 断片の拒否、項目間の重複拒否、署名日が未来なら拒否）と同じにする。決定IDは「人が署名した」ことを system に伝える入力であって承認の証明ではない。値は署名後に人が設定し、AI・実装者は設定しない。

---

## 6. W-5 権限設計

### 6.1 action key

`ActionPermissionResolver` に `privacy` root を追加し、`/api/privacy/**` と `/privacy/**` を専用の分岐で解決する。この分岐は query を取り除いた直後、`!uri.startsWith("/api/")` の画面判定より前に置き、一度に両方を扱う。画面判定より後に置くと `/privacy/**` が `resolvePageAction` で null になり、`isExportPath` / `isDownloadPath` より後に置くと `/api/privacy/dsar/1/export` が `export.execute`、`.../download`・`.csv`・`.pdf`・`.xlsx` が `file.download` に解決され、営業・要員が持つ action で action 層を通過してしまう。

| action key | 経路 | 第1期 |
|---|---|---|
| `privacy.view` / `privacy.create` / `privacy.update` / `privacy.delete` | `/privacy/**`（画面。method に応じて）、`/api/privacy` 直下の未知 subpath（method に応じて） | ○ |
| `privacy.policy.view` | `GET /api/privacy/policies/**` | ○（W3-14） |
| `privacy.policy.manage` | `POST`/`PUT /api/privacy/policies/**` | key だけ予約（REG） |
| `privacy.catalog.view` | `GET /api/privacy/catalog/**` | ○（F2） |
| `privacy.hold.view` / `privacy.hold.manage` | `GET` / `POST /api/privacy/holds` | ○（A1H） |
| `privacy.hold.release` | `POST /api/privacy/holds/{id}/release` | key だけ予約（REL） |
| `privacy.dsar.view` / `privacy.dsar.manage` | `GET` / `POST`・`PUT /api/privacy/dsar/**` | ○（A2a） |
| `privacy.dsar.approve` | `POST /api/privacy/dsar/{id}/approve` | ○（A2b） |
| `privacy.dsar.export` | `/api/privacy/dsar/{id}/package/**`（`/package/download` を含む）と `/api/privacy/dsar/{id}/export`（どちらの形も、method に関係なく） | ○（A2b） |
| `privacy.disposition.*` / `privacy.recovery.*` | B1 / B2 | 署名後 |

GET / HEAD / POST / PUT / PATCH / DELETE は、`/privacy/**` と `/api/privacy/**` のどの subpath でも null を返さない（画面の null は action 判定を素通しにし、`/api/**` の null は全員 403 になるため。privacy 配下に留めれば `privacy.*` の規則がそのまま効く）。それ以外の method（OPTIONS 等）は、`/api/privacy/**` では既存どおり null を返して MenuPermissionFilter に拒否させ、`/privacy/**` では `SecurityConfig` で許可する method を上の6つに限って拒否する（画面の null が素通しになるのを避けるため）。

### 6.2 評価規則（`AuthorizationServiceImpl`）

```java
// isAllowed の既存分岐へ、管理者の短絡許可より前に1つ足す（既存の one-on-one.confidential 分岐と同型）。
if ("one-on-one.confidential".equals(actionKey)) {
    // 既存のまま
} else if (actionKey.startsWith("privacy.")) {
    if (!ROLE_ADMIN.equals(role)) {
        return false;                 // 管理者 role 以外は group に関係なく拒否（誤割当の防御）
    }
    // 管理者でも短絡許可しない。以降の group 評価（deny 優先）へ進む。
} else if (ROLE_ADMIN.equals(role)) {
    return true;                      // 既存のまま
}
// group 評価で privacy key の「許可」候補を作るとき、全体 baseline の '*' を含めない（多重防御、§3.1.3）。
//   allowCandidates = { actionKey, "privacy.*" }   // privacy 以外は既存どおり { actionKey, "<resource>.*", "*" }
//   denyCandidates  = { actionKey, "privacy.*", "*" }（拒否側は既存と同じ集合のまま。拒否を弱めない）
// legacyRoleAllows の先頭:
if (actionKey.startsWith("privacy.")) {
    return false;                     // group 未割当のユーザーは privacy 管理者になれない
}
```

group 評価は既存どおり「有効な所属 group のどれかに deny があれば拒否、無ければ allow を探す」。privacy key の場合だけ、allow の候補から `*` を外す。deny の照合は既存と同じ候補集合（`*` を含む）で行い、拒否を弱めない。`privacy-admin` は `privacy.*` を明示で持つので影響しない。

### 6.3 seed（`V?__nf07_privacy_permission_seed.sql`、暫定 V193）

```sql
-- 1. 管理者専用 group。role-admin を持つ全 tenant に作る（新規 install では 'default' のみ）。
INSERT INTO m_permission_group (tenant_id, group_key, group_name, description, enabled, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, 'privacy-admin', 'プライバシー管理（管理者専用）',
       'NF07 privacy.*。管理者roleのユーザーにだけ、role-adminの代わりに割り当てる', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key = 'role-admin'
  AND NOT EXISTS (SELECT 1 FROM m_permission_group e WHERE e.tenant_id = g.tenant_id AND e.group_key = 'privacy-admin');

-- 2. privacy-admin は role-admin と同じ baseline（'*'）に privacy.* を明示で加える
--    （privacy の判定では '*' を候補に数えないので、privacy.* の行が許可の根拠になる）。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, a.action_key, 0, NOW(), NOW(), 0
FROM m_permission_group g
JOIN (SELECT '*' AS action_key UNION ALL SELECT 'privacy.*') a
WHERE g.group_key = 'privacy-admin'
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id AND x.action_key = a.action_key);

-- 3. privacy-admin 以外の全 group で privacy.* を deny（V110 と同型。tenant・enabled を問わない）。
UPDATE t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id
SET a.deny_flag = 1, a.deleted_flag = 0, a.updated_at = NOW()
WHERE a.action_key = 'privacy.*' AND g.group_key <> 'privacy-admin';

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, 'privacy.*', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key <> 'privacy-admin'
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id AND x.action_key = 'privacy.*');

-- 4. menu を機能ごとに2つ（Feature の menu key と一致させる）。到達可否は action 層と SecurityConfig が決める。
--    sort_order は既存の 97 と重複するので未使用値（暫定 260 / 261）。
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('privacy-retention', 'プライバシー保全', '/privacy/holds', '/api/privacy/holds', 260),
  ('privacy-dsar', '本人請求（DSAR）', '/privacy/dsar', '/api/privacy/dsar', 261);
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id FROM m_menu m WHERE m.menu_key IN ('privacy-retention', 'privacy-dsar');
```

group 作成・action 編集の API は無い（`PermissionGroupApiController` は一覧と割当だけ）ので、group は migration でしか増えない。今後 group を追加する migration・運用作業は `privacy.*` の deny 行も入れる必要がある。漏れは CI では `FlywayNf07PrivacyF1MigrationTest`、本番では §3.1.3 の起動時検査が検出し、§6.2 の多重防御で privacy は開かない。

### 6.4 caller provider の権限判定

`SecurityContextCallerContextProvider.hasExplicitPrivacyAdminAuthority`（`ROLE_ADMIN` と `PRIVACY_ADMIN_READ` の GrantedAuthority を要求）は、この app では誰にも発行されない authority を前提にしている（role は `ROLE_<role>` だけ）。B-NF07-2 に従い、判定の正本を permission group action に移す。R1 の Plan Review はこの変更を次の条件付きで認めた（外部記録の転記。正式な判定は外部記録で確認する）。

- 永続 `SysUser` を tenant 付きで再読し（既存）、role が `管理者` であることを確認した上で、`AuthorizationService.isAllowed(authentication, "privacy.policy.view")` が true の場合だけ `TrustedActorRole.ROLE_ADMIN` を返す。
- `AuthorizationService` が取れない、または例外を投げたら `Optional.empty()`。
- tenant の一致: `AuthorizationServiceImpl` は設定 tenant（`oidcProperties.getTenantId()`、未設定なら `default`）で group を評価し、provider は principal の tenant と再読した `SysUser` を使う。両者が一致しなければ拒否する（別 tenant の group で評価させない）。
- GrantedAuthority `PRIVACY_ADMIN_READ` は発行しない。login 時に authority を焼き込む方式は、group 変更から再 login までの古い権限が残るため採らない（group 変更時は既存の `revokeAllForUser` で session も失効する）。
- 既存 `SecurityContextCallerContextProviderTest` の正例（`PRIVACY_ADMIN_READ` で組み立てたもの）は新しい判定に合わせて書き換える。

### 6.5 `ActionPermissionMatrixTest` の変更点

- 既存 assertion のうち変更するのは、`permissionGroupSeedの内容そのものを検査する` の seed 件数3行（営業 deny 8→9、HR deny 7→8、マネージャー deny 8→9）だけ。業務 action の判定を確かめる既存 assertion と `BUSINESS_ACTIONS` は変更しない（privacy key を足さない）。
- test user を追加: `9005`（管理者、`privacy-admin` だけ所属）、`9006`（営業、`privacy-admin` だけ所属＝誤割当の再現。`replaceAssignments` の guard を通さず直接所属させ、code の role 検査を確かめる）、`9007`（管理者、`role-admin` だけ所属）、`9008`（管理者、`privacy-admin` と `role-admin` の両方に所属）。既存の `9000`（管理者）は現行 seed で group 未割当なので legacy fallback の管理者として使う。
- 追加 test:
  - `privacy系actionはprivacy-admin所属の管理者だけに許可される`: `privacy.view`、`privacy.policy.view`、`privacy.hold.manage`、`privacy.dsar.approve`、`privacy.unknown-sub.view` について、9005 は true、9007・9008・9000・9001〜9004・9006 は false。
  - `privacy-adminとrole-adminの両方に所属すると拒否優先でprivacyは使えない`: 9008 で上記が false、業務 action（`dashboard.view` 等）は true。
  - `privacy-admin所属の管理者は管理者の既存actionを失わない`: 9005 で `dashboard.view`、`user.view`、`one-on-one.confidential` が true。
  - `legacy fallbackはprivacy系actionを誰にも許可しない`: 9000・9999 で false。
  - `全体baselineはprivacyの許可根拠にならない`: test 内で `*` だけを持ち `privacy.*` deny を持たない group を一時的に作り、管理者を所属させても privacy が false。
  - `privacyのURIはexportとdownloadと画面判定より先にprivacy actionへ解決される`: `GET /api/privacy/dsar/1/package/download` → `privacy.dsar.export`、`POST /api/privacy/dsar/1/export` → `privacy.dsar.export`、`GET /api/privacy/dsar/1/package/x.pdf` → `privacy.dsar.export`、`POST /api/privacy/holds` → `privacy.hold.manage`、`GET /api/privacy/unknown-sub` → `privacy.view`、`GET /privacy/holds` → `privacy.view`、`POST /privacy/holds` → `privacy.create`、`OPTIONS /api/privacy/holds` → null（MenuPermissionFilter が拒否することを `MenuPermissionFilterTest` で確認）。
- 機密 action の集合に追加: `既知resource許可を持つroleでも機密actionは拒否指定が優先される` と `マネージャーは全権限wildcardを持たず機密actionを実行できない` に `privacy.view`・`privacy.dsar.manage` を加える（新規 assertion の追加であり、既存 assertion の変更ではない）。
- seed 内容の検査に追加: group 1（`role-admin`）と 5（`role-member`）に `privacy.*` deny が1件、group 6（`privacy-admin`）に `*` 許可1件・`privacy.*` 許可1件・deny 0件、`privacy-admin` 以外で `privacy.*` deny を持たない group が0件。「非管理者 group（2〜5）に `*` 許可が無い」は不変。
- `permission-group-seed-h2.sql` に group 6 と上記の行、test user 9005〜9008 の `t_user_permission_group` 所属行を足す（DDL lane の所有範囲）。

### 6.6 「allow-list 禁止」との整合

CLAUDE.md の規則は「role ごとに許可 key を列挙すると、URI root から機械生成される未列挙 key が黙って 403 になる」ことを禁じている。本設計は次の理由でこれに当たらない（R1 の Review も同じ判断。外部記録の転記）。

1. 許可は resource wildcard `privacy.*` の1行だけで、`privacy-admin` に与える。privacy 配下に endpoint が増えても列挙の追加は要らない（未知 subpath も `privacy.*` に入る）。
2. 他の group が privacy を使えないのは deny 行（差し引き）、code の role 検査、`*` を privacy の候補に数えない規則による。列挙から漏れたから拒否されるのではない。
3. `BUSINESS_ACTIONS` を編集しない。業務 action の判定は変わらない。
4. legacy fallback は `privacy.*` を無条件に拒否する（admin-only action と同じ扱い）。

なお、CLAUDE.md の「API root を足しても AuthorizationService / legacy fallback の変更は要らない」は V66_1 以降の実装（非管理者 group は既知 resource の wildcard を持ち、未知 root は拒否。新 root には `RESOURCE_NAMES` の登録と seed が要る）と食い違っている。本計画はその記述に依存しない。CLAUDE.md の修正は X3-3 / DOC-FINAL で行い、W3-14 にも本 batch にも含めない。

### 6.7 割当の排他と guard（依頼者決定 2026-11-14）

deny は所属 group 全体で優先されるため、`role-admin`（`privacy.*` deny）と `privacy-admin`（許可）の両方に所属すると privacy は拒否される。依頼者の決定どおり、privacy 管理者には **`role-admin` の代わりに `privacy-admin` だけ**を割り当てる。`privacy-admin` は `*` を持つので、管理者としての既存 action（短絡許可の対象外である `one-on-one.confidential` を含む）は失わない。承認経路で `PERMISSION_GROUP=role-admin` を承認者に指定している場合に privacy 管理者が外れる副作用は受け入れ済み（seed にその経路は無く、運用者が設定した場合だけ起きる）。

`PermissionGroupManagementServiceImpl.replaceAssignments` に次を足す。

- 要求に `privacy-admin` を含み、対象ユーザーの role が `管理者` でなければ 400（`error.permission.adminOnlyGroup`）。`privacy-admin` は `*` を持つので、誤割当を data の deny だけに任せない。
- 要求に `privacy-admin` と、`privacy.*` deny を持つ他の group が同時に含まれれば 409（`error.permission.privacyGroupConflict`）。判定は group key の固定値ではなく deny 行の有無で行う。
- G-ID の署名までの暫定規則（P3-12）: `privacy-admin` の割当・解除は、変更前後の group key を含む required 監査（既存の監査は group key を持たない）を同一 tx で書き、既存の `privacy-admin` 所属者へ通知する。監査に失敗したら割当も失敗させる。
- role 変更時の既定 group への再割当（`SysUserServiceImpl`）は現行どおり全置換なので、管理者から外れたユーザーの `privacy-admin` は自動で外れる（この解除も上の監査・通知の対象にする）。

### 6.8 その他の境界

- `SecurityConfig`: `/privacy/**`、`/api/privacy/**` を `hasRole("管理者")` で静的に制限する（`/user/**` と同じ二重化）。
- break-glass: `BreakGlassServiceImpl.validateAndSerializeActions` で `privacy.` 接頭辞を拒否する（`privacy` が既知 resource になると `isKnownAction` が true を返し、緊急 access の対象に入れてしまうため）。
- service 層の再認可: `MenuPermissionFilter` は `AuthorizationService` が取れない slice では素通しになり、`FileScopeValidationService` や `GlobalControllerAdvice` には管理者を一括で通す分岐がある。privacy の各 service は `PrivacyAccessGuard` で flag と action を再判定し、取れなければ拒否する。
- 汎用 file 経路: DSAR package は `GET /api/files/{storedName}` と rescan から一律拒否し、専用ダウンロードだけで扱う（§3.5.2）。
- sidebar: `allowedMenus.contains('privacy-retention')` / `contains('privacy-dsar')`（Feature の menu key。OFF の機能は `FeatureSwitches.visibleMenuKeys` で消える）に加えて、privacy action の判定結果（`GlobalControllerAdvice` が model に入れる真偽値）を条件にする。`allowedMenus` は管理者に全 menu を返すので、それだけでは `privacy-admin` 以外の管理者にも出てしまう。
- 全体検索・通知: 将来 privacy の実体を全体検索に入れる場合は privacy action で判定する。通知の宛先は `privacy-admin` の所属者として解決し、本文に subject 識別子を入れない（§3.5.1）。
- 監査: privacy 操作の監査本文に subject 識別子・氏名を入れない（`/api/audit-logs` は全管理者が見られるため）。

---

## 7. 署名項目ごとの開放手順

署名が届いた後に人（運用者）が行う。AI・実装者は値を設定しない。各手順の後に再起動し、起動 log の機能スイッチ状態と `PrivacyProductionConfigurationValidator` の結果を記録する。

| 署名項目 | 署名後に変える値 | 開く flag・capability | 前提 batch |
|---|---|---|---|
| D-01 正式 Privacy Owner | `PRIVACY_APPROVAL_D01_DECISION_ID` / `_SIGNED_ON`。requirements.md の `<OWNER>` を人が置換 | 単独では開かない（全 flag の前提） | W3-14 |
| D-02 / DG-07 核心（保持期間・trigger・法的根拠） | data element ごとの policy を issuer が署名した envelope にして登録・有効化（W-2）。`PRIVACY_APPROVAL_D02_*` | 対象 element が `POLICY_UNKNOWN` → `APPROVED_ACTIVE`。NF03 の `FEATURE_NF03_CERTIFICATION_PII_ENABLED`（B-NF03-1）は NF03 の owner がこの結果を確認して判断。NF08 は §8 | W3-14、NF07-REG、NF07-TRUST |
| D-03 Trust Anchor・KMS・失効 | `privacy.trust.anchors[*]`（authority ID・key ID・公開鍵・有効区間）、失効 provider の接続設定 | policy の検証が通るようになる。hold 解除 proof の署名も可能になる（単独では flag を開かない） | NF07-TRUST |
| D-04 処分方式 | envelope の許可処分方式、`PRIVACY_APPROVAL_D04_*` | B1 の子スイッチの前提 | B1 |
| D-05 hold 権限者・SoD・hold 種類 | `PRIVACY_APPROVAL_D05_*`、hold 種類の値（`hold_kind` の許可値）、`privacy.hold.allowed-target-types`、解除の承認者規則。あわせて owner が §3.4.3 の残余（帰属できない element の行は SUBJECT hold で守られない、DATA_ELEMENT hold は解除開放まで止まり続ける）と §3.4.1 で対象外とした経路を受理する | `privacy.retention.enabled` 下の hold 開始。解除は NF07-REL のマージと D-03 の後 | NF07-A1J、NF07-A1H（解除は NF07-REL） |
| D-06 DSAR SOP | `PRIVACY_APPROVAL_D06_*`、`privacy.dsar.due-days`、`privacy.dsar.package-max-age`、本人確認基準ID、redaction 規則（版付き） | `privacy.dsar.enabled` の前提 | NF07-F2、NF07-A2a、NF07-A2b |
| D-07 unquarantine | `PRIVACY_APPROVAL_D07_*`、unquarantine の承認者 group | B2 の unquarantine | B2 |
| D-08 外部 AI・電子契約 DPA | NF08 の `AI_EXTERNAL_SEND_DPA_REFERENCE` 等（NF08 側）。NF07 側は provider を外部登録しない方針を維持 | NF08 の送信 gate（§8） | NF07-NF08-LINK |
| D-09 本番 flag 開放 | `PRIVACY_APPROVAL_D09_*` を設定し、前提の署名が揃った flag から1つずつ `PRIVACY_RETENTION_ENABLED=true` / `PRIVACY_DSAR_ENABLED=true` にする（順序は問わない） | 両 flag | 各 flag の前提 batch |
| DG-07 全体 | 上記 D-01〜D-09 のうち DG7-a〜i に当たるもの＋ G-EXP・G-REC・G-AUD。tasks.md 0.4 coverage closure を release SHA の scan で閉じる | B1・M の前提 | M |
| P-SRC / P-LE / P-ACT / P-API | P-SRC: policy 正本ストアの承認（現行の `m_retention_policy` を正本と認めるか、別ストアへ移すか）。P-LE: 法人単位 policy の要否（要なら envelope schema v2）。P-ACT: Clock/NTP と許容 skew。P-API: 下流呼出の認可責任 | 説明照会の「本番 currentness 証明済み」表示、NF08 への接続 | W3-14、NF07-NF08-LINK |
| G-DBA / G-BKP | Journal（と B2 で Witness）インスタンスの接続設定 `privacy.journal.*`、GRANT 実行、Journal 用 Flyway job の実行、別 backup | hold 開始（journal 必須） | NF07-A1J |
| G-ID | `privacy-admin` の割当規則（§6.7 の暫定規則を置き換えるか確認） | privacy 管理者の正式な割当 | W3-14 |

---

## 8. NF08 との関係

現状（基準 `1be03bea`）:

- NF08 の外部送信承認設定 `ai.external-send-approval.nf07-policy-id`（環境変数 `AI_EXTERNAL_SEND_NF07_POLICY_ID`、`application.yml:387`）は、`AiExternalSendApproval.evaluate`（`AiExternalSendApproval.java:79-84`）で形式・placeholder・他の決定IDとの重複だけを検査している。NF07 の policy source とは照合していない。
- 設定値が placeholder（`UNKNOWN`・`TBD` 等の断片を含む）なら complete にならない。ただし形式が正しい任意の ID を設定すれば、NF07 に該当する policy が無くても complete になる（照合が無いため）。
- 呼出元は起動時の `AiProductionApprovalGate` と送信ごとの `GeminiTextServiceImpl`（`:252`、`:276`）。

依存関係（統一した書き方）:

- **NF07-NF08-LINK は NF08 外部送信を開放する前に必ずマージする。** 代替手段（運用確認だけで開く）は採らない。LINK がマージされるまで NF08 の外部送信は開放しない。
- NF08 の外部送信が実際に許可されるには、次が全て揃う必要がある: (1) NF07-REG・NF07-TRUST・NF07-NF08-LINK がマージ済み、(2) `privacy.retention.enabled=true`（OFF では policy source が `SOURCE_DISABLED` を返す）、(3) D-02・D-03・D-08 が署名され、対象 policy が登録・有効化されて `APPROVED_ACTIVE`、(4) NF08 自身の承認設定（DG-08 の決定ID・DPA 参照・model・用途・宛先・費用上限、B-NF08-2）。LINK のマージ後は、1つでも欠ければ NF08 の実外部送信は必ず拒否される。
- このため、B-NF08-1（実 Gemini への接続）の本番公開の経路は NF07 の上記の連鎖に依存する。NF08 の gate 文書（`.kiro/specs/ai-management-copilot/external-send-approval-gate.md`）と wave3 計画の依存関係への登録、NF08-1 所有者への通知は総括が行う（本 spec の範囲外）。

NF07 の policy 値が確定した後の手順:

1. D-02（AI payload の保持・外部送信 policy）と D-08（Gemini の DPA）が署名される。
2. issuer が D-03 の鍵で envelope に署名する（key は合成 catalog と同じ形: module `NF08_AI_SUMMARY`、data element `AI_PROMPT_LOG`、action `EXTERNAL_DISPATCH`、provider `gemini-adapter`。本番の正式な値は F2/REG で確定）。
3. privacy-admin A が登録し、別の privacy-admin B が有効化する（NF07-REG）。
4. privacy-admin が `GET /api/privacy/policies/evaluate` で `APPROVED_ACTIVE` と policyId・revision・digest を確認する（確認手順であり、LINK の代わりにはならない）。
5. 運用者が NF08 の `AI_EXTERNAL_SEND_NF07_POLICY_ID` にその policyId を設定する（DG-08 の決定ID・DPA 参照など NF08 の他の項目と一緒に）。再起動して `AiProductionApprovalGate` が complete を返すことを確認する。
6. 送信ごとに LINK の内部判定 port が policyId の一致と `APPROVED_ACTIVE` を確認し、flag OFF・UNKNOWN・EXPIRED・REVOKED・DRIFT・不一致なら送信を拒否する。policy が失効・期限切れになれば、NF08 の設定を変えなくても送信が止まる。再署名後は 3〜5 をやり直す。

注意点:

- `APPROVED_ACTIVE` だけでは送信を許可しない（prod-wiring §4.2）。NF08 自身の承認設定（B-NF08-2）は従来どおり必要。prod-wiring §4.2.3 の `CaseApprovalEnvelope` / `VerifiedDispatchFence` は NF07 自身の外部 dispatch に適用する（[`status-alignment.md`](status-alignment.md) §3 X-1）。
- 説明照会（W-4）は caller を privacy 管理者に限るので、一般利用者の Copilot 送信から直接は呼べない。NF07-NF08-LINK では tenant だけを束縛し、説明 DTO を返さない内部判定 port（`PolicyEnforcementPort`）を別に設計する（LINK の Plan Review 対象）。
- `application.yml` の `ai.*` と NF08 の class は NF08-1 の所有（wave3 §2）。NF07-NF08-LINK はその所有者と調整して入れる。

---

## 9. 署名前実装のリスクと緩和

| # | リスク | 緩和 |
|---|---|---|
| R-1 | 署名前に flag が誤って ON にされる | prod では flag ON に D-01・D-09 等の決定IDが必須（起動失敗）。flag は真偽値として厳格に bind。OFF の入口は 404（登録済みは filter、未登録は guard）。起動 log に状態を出す |
| R-2 | 署名の内容が想定と違い、作り直しになる（例: hold 開始も二者、法人単位の policy、DSAR 期限の数え方） | 値は envelope・設定で外から与え、code に書かない。未定の列は UNKNOWN/NULL を許す。envelope は schema v1 だけを解釈し、法人 ID を署名対象に入れる v2 は P-LE 後に別 Plan（S-14）。batch ごとに Plan Review |
| R-3 | UNKNOWN が既定値として扱われる（技術上の現在値が法的値に見える、placeholder が通る） | placeholder 断片の拒否、技術値を `APPROVED_*` として返さない test、seed に期間を入れない Review 項目 |
| R-4 | 権限の誤割当（非管理者に `privacy-admin`、`role-admin` との併用で黙って拒否） | code の role 検査、割当 guard（400/409）、group key 付き監査と通知、matrix test（9006・9008）、割当変更時の session 失効（既存） |
| R-5 | 既存の削除経路の挙動が変わる。逆に、flag を OFF に戻したときに既存 hold が効かなくなる | hold 機構を一度も使っていない間は gate の照会1回だけで挙動不変。使った後は flag に関係なく hold を守る。経路ごとに test |
| R-6 | Journal インスタンスが遅れる／app DB を指してしまう | hold 開始は journal 必須（`JOURNAL_UNAVAILABLE`）。`server_uuid` の相違・耐久設定・権限を起動時に検査する |
| R-7 | 状態文書がまた食い違う | 状態文には日付・SHA・範囲・証拠を付ける規則（[`status-alignment.md`](status-alignment.md) §1）。verdict は外部記録で確認する |
| R-8 | 署名前に作った DDL を後で変えたくなる | 新しい migration だけで直す（既存 script は変更しない）。第1期の表は本番で flag ON まで空のまま。disposition 表は B1 まで作らない。SoD・承認束縛・複合 FK は W3-14 の前に草案へ入れた |
| R-9 | 新しい表・監査・通知に PII が溜まる | 依頼者連絡先・本人確認書類の本体は持たず参照IDだけ。hold 理由は code・hash・ticket。監査と通知に subject 識別子を入れない。package は quarantine→published、交付記録で PURGED、汎用 file 経路から取得不可 |
| R-10 | NF08 が `APPROVED_ACTIVE` を送信許可と読み違える | NF08 の承認設定は不変。NF07-NF08-LINK は両方を要求する |
| R-11 | 管理者の短絡許可を外したことで、privacy 以外の判定が変わる | 変更は `privacy.` 接頭辞だけ。業務 action の既存 assertion は無変更で通ること |
| R-12 | 合成層を本番から呼べてしまう／合成層が変わる | 合成部品の guard とコードは変更しない。本番部品は `ProductionPrivacyComponents` 経由だけ。`PrivacyFoundationIsolationTest` と同値性 test で固定 |
| R-13 | 署名前に作り込んだ code が開放されないまま古くなる | 開放手順（§7）を batch ごとに test で再現できる形にし、M の前に固定 SHA で再実行する |
| R-14 | NF08 の `nf07PolicyId` は形式だけの検査なので、NF07 に policy が無くても形式の正しい ID で送信承認が complete になる | NF07-NF08-LINK を NF08 外部送信の開放前に必ずマージする（§8）。代替手段は採らない |
| R-15 | 帰属できない行（未変換の取込、integration hub の payload、snapshot、参照元が物理削除された実体）にある held subject のデータは SUBJECT hold で守られない | hold 開始画面で該当 element と件数を示し、必要なら DATA_ELEMENT hold を足す。残余は D-05 owner が受理する（§7）。F2 の inventory で帰属を増やす |
| R-16 | Journal の不達が続くと、hold を使った tenant の帰属可能な削除が止まり続け、期限超過の保持になる | 経路ごとの skip 件数と `UNAVAILABLE` の継続時間を監視し、閾値（SRE が決める）を超えたら通報する。reconciler が pending と projection 欠落を自動で解消する |
| R-17 | batch の途中で開始された hold がその回に反映されない | 窓は最大1回分の実行時間（既知の残余として受け入れ、hold 開始画面に表示）。手動の文書処分は同一 tx で照会するので窓が無い |

---

## 10. Plan Review（再 Review）で重点的に見てほしい箇所

1. §3.4.1 の破壊的経路の一覧に漏れが無いか（特に D1 の接続位置、D10 を provider で守る方式、D11 の分類方法、D13 の他 NF）。
2. §3.4.3 の採用案 (b)+(c)（帰属できない行は SUBJECT hold で止めない）と残余 R-15 の扱い。
3. §3.4.4 の pending の CAS・直列化・reconciler、未使用 tenant の判定優先、batch 途中の hold の窓。
4. §3.1.2 の Feature 登録が `FeatureSwitchRouteCoverageTest` と `FeatureSwitchDefaultsTest` の不変条件を満たすか。
5. §3.1.3 / §6.2 の起動時検査と、privacy の判定で `*` を候補に数えない多重防御。
6. §3.1.5 の SoD CHECK・承認束縛・複合 FK・`ALTER TABLE` で足す相互 FK。
7. §3.5.2 の汎用 file 経路の遮断位置（管理者の通過分岐より前）。
8. §3.3 の Journal 接続の holder 型と、合成 adapter に手を入れずに同値性 test で乖離を検出する方式。
9. §8 の NF08 依存の書き方（LINK は NF08 外部送信の開放前に必須）。
10. §6.7 の privacy-admin 割当の監査・通知（G-ID 署名までの暫定規則）。

---

## 11. 依頼者の判断が要る未決事項

- R1 で挙げた2件（第1期 hold の正本、privacy-admin の割当の排他）は依頼者が 2026-11-14 に決定済み（本書冒頭の決定表）。
- R2 の修正（P1-2 の採用案を含む）で、依頼者の新たな判断が要る事項は無い。帰属できない行の残余の受理は D-05 の署名項目として扱う（§7）。
