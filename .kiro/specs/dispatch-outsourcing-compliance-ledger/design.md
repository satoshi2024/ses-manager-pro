# Design — 派遣・準委任コンプライアンス台帳

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（S10正式V84）

> S10正式migration V84。V82は欠番として保持し、既存V83の後へ適用する。

- `m_workplace(id, customer_id, name, address, organization_unit, phone)`。
- `t_contract_compliance_profile(contract_id, contract_type_detail, workplace_id, work_description,
  work_time/break/holidays/overtime, command_person_contact_id, client/dispatch responsible person,
  responsibility_level/detail, workplace_limitation_date, organization_limitation_date,
  social_insurance_procedure_incomplete_reason, health/pension/employment status/reason/expected_date,
  dispatch_fee_amount/basis/currency, benefits_detail, dispatch_headcount,
  agreement_target_flag, indefinite_worker_flag, age_over_60_flag, worker_restriction_type,
  source/client complaint contacts, treatment_scheme, training/safety/insurance fields,
  instruction_route, subcontract_allowed, acceptance_method, version)`。
- `t_contract_compliance_snapshot(contract_id, snapshot_version, snapshot_hash, typed fields, snapshot_json)` と
  `t_contract_compliance_worker_snapshot(contract_id, worker_id, snapshot_version, snapshot_hash, typed fields)`。
- `t_compliance_finding(id, contract_id, code, severity, status, detected_at, due_date,
  acknowledged_by/at, resolution_note, evidence_document_id)`。苦情・教育・雇用安定・紹介予定・月次実績は専用history tableで反復保存する。
- `t_document_delivery(document_id, recipient_contact_id, template_version, effective period, snapshot_hash,
  delivery_method, delivered_at, confirmed_at)`。

> **R5補正**: 上記は実装前の短縮表であり、現行V84実装の合格を意味しない。公式mappingとの1対1対応、履歴table、明示NULL、legacy/partial/repair経路は§5.5/§6.2を正本とする。未決の法的意味をTEXT/JSONへ圧縮してDDLを確定してはならない。

## 2. Rule engine

- 既存`LaborComplianceService`を`ComplianceRule`群へ分解するが、4既存code/挙動を維持。
- Rule inputはcontract/profile/BP tier/attendance/acceptance/document delivery。
- findingsは同じ`contract+code+condition fingerprint`でupsertし、解消時にstatus更新。毎回重複insertしない。
- 法的適用判定ではなく`MISSING_*`, `DEADLINE_*`, `RISK_*`を返す。

## 3. 帳票

- `ComplianceDocumentGenerator`と帳票種別別template version。
- 公式様式の項目対応表を`field-mapping.md`としてURL/版/確認日/effective period付きで保存する。
- mapping lifecycleは`DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`。L0と独立Reviewで
  `PROVISIONAL_REVIEWED`となり開発baselineにできる。runtimeの`COMPLIANCE_RESPONSIBLE` assignment、
  対象version/hashへの実actor承認event、外部専門家Reviewが揃うまで`ACTIVE`化と本番交付を禁止する。
- `COMPLIANCE_RESPONSIBLE`は管理者が有効期間付きで指名・交代する。特定の自然人をcode/spec/seedへ固定せず、
  承認eventにactor ID、表示名snapshot、role、日時、mapping version/hash、根拠資料を保存する。
- PDF/Excelどちらを採用するか帳票別に決め、生成物はarchive登録。

## 4. UI

- contract detailにcompliance profile/findings/documents。
- `/compliance`は現行リスク一覧を拡張し、期限/状態/担当/filterを追加。
- sensitive fieldはpermission mask。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 5.1 時間・asOf

法定帳票は「契約時点の条件で再生成できる」ことが要件（R1.4 / R5）。snapshot設計が本specの中核である。

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 就業条件 | `t_contract_compliance_profile`現在値 | `t_contract_compliance_snapshot` append-only | snapshot rowのtyped列＋`snapshot_json` | **帳票の交付日時点のsnapshot** | 未入力＝`MISSING_*` finding対象 |
| 就業先 | `m_workplace`現在値 | — | profile snapshotへ名称/住所を含める | 契約時点のsnapshot | 未設定＝finding |
| 指揮命令者 | `command_person_contact_id` | contact側の期間 | snapshotへ氏名を含める | 契約時点 | **未設定＝派遣ではfinding、準委任では正常** |
| 抵触日 | `workplace_limitation_date`＋`organization_limitation_date` | snapshot rowへ算定結果を保存 | 2種のdateを別々にasOf解決 | 現在値または交付snapshot | **未算定**。「抵触日なし」ではない。算定不能をfindingにする |
| 保険/教育訓練 | profile各field | — | snapshot | 契約時点 | **未確認**（「不要」ではない） |
| finding | `t_compliance_finding.status` | `detected_at`＋解消履歴 | — | 現在値 | — |
| 帳票交付 | `t_document_delivery` | 交付ごとに行追加 | — | 交付日 | `confirmed_at IS NULL`＝**受領未確認**（未交付ではない） |

- `workplace_limitation_date` または `organization_limitation_date` がNULLを「抵触日なし＝安全」と扱わない。
  **算定できていない**状態であり、それぞれ `MISSING_WORKPLACE_LIMITATION_DATE` / `MISSING_ORGANIZATION_LIMITATION_DATE`
  findingを出す。S02の「履歴なしとNULLの混同」と同型の事故になる。旧単一`limitation_date`はF1契約から除外する。
- 抵触日の算定は**後続契約・組織単位変更を考慮**する（R3.3）。契約単体で算定しない。
  同一要員×同一組織単位の契約chainを辿る。

### 5.2 期間代数（抵触日・契約chain）

| case | 期待 |
|---|---|
| 契約の連続更新 | chainを辿って通算。更新のたびに0からリセットしない |
| 空白期間あり（クーリング） | 空白が規定日数以上なら通算をリセット。未満なら継続 |
| 組織単位の変更 | 変更後は別カウント。ただし変更の実体が同一かをfindingで人へ確認させる |
| 同日開始の新契約 | 前契約を前日で閉じてchainを繋ぐ |
| 未来開始の契約 | 現在の抵触日算定に含める（先読み警告） |
| 複数契約が同時並行 | それぞれ独立にカウントし、最も早い抵触日を採る |

クーリング期間の日数はconfig値とし、コードへ直書きしない。G2で確定した値を`m_system_config`へ置く。

### 5.3 主体 × 操作 × 可見母集団

**R4.1の権限が本specの主要リスク。** 個人別台帳と待遇情報はHR/法務/管理者限定であり、
営業は契約に必要な限定項目だけを見る。この差は**field単位**であって画面単位ではない。

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件・全field | 全件・全field | 全finding | deadline batch |
| 法務/HR | 全件・全field（待遇・個人情報含む） | 同左 | 担当findingと期限 | — |
| マネージャー | 組織scope ∩ DataScope。**待遇・個人情報field はmask** | 同左（maskしたまま） | 自組織のfinding | — |
| 営業 | 既存DataScope（担当契約）。**契約に必要な限定fieldのみ** | 同左 | 自担当契約のfinding | — |
| 要員 | 自分の就業条件明示書のみ（S14経由） | 同左 | — | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | 帳票生成のみ | 宛先は担当営業/法務/HR | 90/60/30日前通知 |

- **exportとPDFに同じfield permissionを適用する**（R4.2）。
  画面でmaskした待遇情報がCSV/PDFで素通しになる事故を防ぐ。§2.3のconsumer inventory必須。
- 既存`LaborComplianceService`の`/api/compliance`は管理者/マネージャー限定である。
  本specでfindingを他画面（契約detail等）へ埋め込む場合、
  `MonthlyClosingServiceImpl.canViewCompliance()`と同じ方式で**menu権限を再チェック**する。
  画面自身のmenu権限で足りるとみなさない。

### 5.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| mapping DRAFT | →PROVISIONAL_REVIEWED | 公式source/版/effective period＋L0＋独立Review、mapping hash固定 | mapping編集 | DRAFTの新versionを作成 |
| mapping PROVISIONAL_REVIEWED | →ACTIVE / →SUPERSEDED | runtime role assignment＋対象hash承認event＋外部専門家Review | 承認とmapping改定 | 承認対象hash不一致なら遷移拒否 |
| mapping ACTIVE | →SUPERSEDED | 新versionの有効化CAS | 法令・様式更新 | 旧versionと過去帳票snapshotを保持 |
| profile 未入力 | →入力済 | 状態CAS | — | — |
| 入力済 | →確定（帳票生成可） | `version` CAS | 同時編集 | 入力済へ |
| 確定 | →改定（新version） | current profileは`current_snapshot_id`/`current_snapshot_version`を`version` CAS。snapshotは`UNIQUE(contract_id, snapshot_version)`＋`UNIQUE(contract_id, snapshot_hash)` | 1 transactionでversion予約→snapshot INSERT→pointer切替。競合loserはrollbackしorphanを残さない | **過去snapshotはDB triggerでUPDATE/DELETE拒否。改定は新snapshot INSERT** |
| finding OPEN | →acknowledged / →解消 / →例外承認 | 状態CAS | rule再実行との競合 | OPENへ戻る（再検出） |
| acknowledged | →対応中→解消 / →例外承認 | 状態CAS | — | — |
| 解消 | 再検出でOPENへ戻りうる | fingerprint一致で同一findingを再利用 | — | — |
| 例外承認 | 承認期限まで抑止 | 期限切れでOPENへ | — | — |

- **finding は`(contract_id, code, condition_fingerprint)`でupsert**（design §2）。
  rule再実行のたびにinsertすると、ack済みfindingが毎回OPENで複製される。
  `UNIQUE(contract_id, code, condition_fingerprint)`をDB制約として置く。
- rule実行は**read-only＋upsert**。契約や勤怠の業務状態を変更しない。
- 既存4ruleのcode/挙動を維持する（design §2）。既存codeのseverityやmessage keyを変えない。
  回帰testで既存4ruleの出力を固定する。
- 帳票生成はarchive specへ登録する。生成の冪等キーは
  `(contract_id, document_type, template_version, snapshot_hash)`。
  同じsnapshotからの再生成で2件目を作らない。

### 5.5 F1 schema / history matrix（R5で確定する技術契約）

| 領域 | current row | append-only history / snapshot | NULL・競合規則 | 担当task |
|---|---|---|---|---|
| 公式mappingのtyped field | `t_contract_compliance_profile`の専用列（組織制限日、SRC-E⑱、料金、責任、福利厚生、人数、協定/無期/60歳） | worker/contract snapshotへ同じ列名で複写 | 公式項目を別名称・JSONだけで置換しない。未確認はNULL＋finding | T061 |
| worker-specific | current profileはcontract単位、worker識別は別参照 | `t_contract_compliance_worker_snapshot`をworker/version/hash単位でappend-only | 複数workerのPII・出力を同一snapshotへ混在させない | T061 |
| 苦情・雇用安定・教育・career・紹介予定 | currentは条件/窓口のみ | 各history tableを反復行として保存 | findingのresolution_noteやJSONへ圧縮しない | T061/T062/B1 |
| profile snapshot | mutable current profile＋`current_snapshot_id`/`current_snapshot_version` | `t_contract_compliance_snapshot`、`UNIQUE(contract_id,snapshot_version)`、`UNIQUE(contract_id,snapshot_hash)` | 1 transactionのversion予約→INSERT→pointer CAS。DB triggerがsnapshot UPDATE/DELETEを拒否 | T061/B1 |
| worker snapshot | worker current reference | `t_contract_compliance_worker_snapshot`、`UNIQUE(contract_id,worker_id,snapshot_version)`、`UNIQUE(contract_id,worker_id,snapshot_hash)` | 同一transactionでworker versionを予約。pointerとorphanを検証 | T061/B1 |
| explicit NULL | `FieldStrategy.ALWAYS`を唯一のclear mechanismとし、全fieldを送るfull DTO/update contract | snapshotは旧版不変、currentだけ明示NULL可 | `not_null` skipを許さず、CAS失敗はrollback。clearable inventoryを§6.2で列挙 | T061/T062 |
| permission boundary | internal entity/table（portal/AIへ直接公開しない） | snapshotと履歴を同一scopeで参照 | T061はprojection契約を持つ。実maskはT063 detail/list/count、T064 export/download/PDFで検証 | T063/T064 |
| migration path | fresh V1→V84 | V83実形状からV84、partial/repair、既存契約no-backfill | V84は既存表を前提にせず、FK順序と再開性をassert | T061 |

このmatrixで法的な文言・適用条件を新たに決めていない。`GATE-T060-ROLE`はruntime承認roleのassignment/監査だけを扱い、料金意味・2種抵触日の表示条件等の法的field semanticsは`GATE-T066-FIELD-SEMANTICS`へ分離する。法務判断はGATE-T066/2026-10/COOLING/EXTERNALへ残し、保存形状とfail-closed境界だけを確定する。

### 5.6 Snapshot write protocol / immutability enforcement

1. current profileを`SELECT ... FOR UPDATE`し、`current_snapshot_version + 1`を同一transaction内で予約する。
2. `t_contract_compliance_snapshot`へtyped fieldとJSON、hashをINSERTする。`UNIQUE(contract_id, snapshot_version)`と`UNIQUE(contract_id, snapshot_hash)`が同時実行と再実行を防ぐ。同一hash再実行は既存snapshotを返し、新versionを増やさない。
3. profileの`current_snapshot_id`/`current_snapshot_version`を期待version付きCASで切り替える。CAS失敗、UNIQUE違反、FK違反のいずれもtransaction全体をrollbackし、未参照snapshotを残さない。
4. snapshot tableにはMySQL/H2同等のBEFORE UPDATE/DELETE triggerを置き、`SIGNAL`で直接SQLのUPDATE/DELETEを拒否する。application mapperはINSERT/SELECTだけを公開し、通常のretention削除は行わない。法的hold解除を含む削除はT066/B1の別gateで明示承認する。
5. worker snapshotも`contract_id + worker_id`をscopeへ加え、同じtransaction/CAS/trigger規則を適用する。profile currentの`current_snapshot_id`は必ず存在するsnapshotを参照する。

## 6. テスト

rule境界、finding upsert/解消、帳票field mapping、deadline scheduler、profile snapshot、PII permission、
既存4rule回帰、法務fixture golden file。

T060 L0は、全mapping行の公式URL/版/確認日/effective period、mapping hash、`DRAFT -> PROVISIONAL_REVIEWED`条件、
特定自然人の事前固定がないことを検証する。実actor承認eventがないことは開発baselineの失敗条件にしない。
Mでは、runtime assignment/承認event/外部専門家Reviewのいずれかが欠ける場合に`ACTIVE`化と本番交付が拒否され、
対象hash不一致の承認eventが無効であることを検証する。

### 6.1 G2 gate test matrix

| test ID | level / task | setup | operation | expected |
|---|---|---|---|---|
| G2-GATE-L0-01 | L0 / T060 | 公式source/版/確認日/effective periodと全fieldを持つDRAFT。runtime assignmentなし | L0と独立Review | `PROVISIONAL_REVIEWED`。T061〜T065の開発開始可 |
| G2-GATE-L0-02 | L0 / T060 | G2正本、spec、mapping、seed候補 | 特定の氏名/user ID固定をscan | 固定0件。role codeとruntime指名規則のみ |
| G2-GATE-L2-01 | L2 / T061-T064 | PROVISIONAL_REVIEWED、active assignmentなし | ACTIVE化または本番交付 | fail-closed。状態不変、監査event |
| G2-GATE-L2-02 | L2 / T061-T064 | active assignmentあり、承認eventのmapping hashが不一致 | ACTIVE化 | 拒否。対象version/hash一致を要求 |
| G2-GATE-L2-03 | L2 / T061-T064 | 旧責任者の承認後に管理者が交代 | assignment終了/追加 | 旧承認actor snapshotと過去帳票は不変 |
| G2-GATE-M-01 | L4 / T066 | assignment/承認eventあり、外部専門家Reviewなし | M PASSまたは本番交付 | 拒否。PROVISIONAL_REVIEWEDを維持 |
| G2-GATE-M-02 | L4 / T066 | active assignment、対象hash承認event、外部専門家Reviewが有効 | ACTIVE化と本番交付 | 成功。version/hashと全証跡を監査保存 |

### 6.2 T061 F1 direct regression matrix（R5 fix scope）

| test ID | level | fixture / operation | expected |
|---|---|---|---|
| F1-MAP-01 | L1 | `FM-C-01`〜`FM-L-30`の96 stable row IDをschema manifestへ機械照合 | 96 IDすべてに専用columnまたは指定historyが1件。technical shape未解決0件。法務semanticsだけはGATE-T066-FIELD-SEMANTICS/T066として明示 |
| F1-SNAPSHOT-01 | L2 | snapshot Aを確定→current profileをBへ改定→Aを再取得。同じversionの別hash、同じhashの再実行、direct SQL UPDATE/DELETEも試す | `UNIQUE(contract_id,snapshot_version)`と`UNIQUE(contract_id,snapshot_hash)`が重複を拒否。同一hashは既存Aを返す。A/B両方取得可能、A不変、B新version、pointer CAS競合は片方だけ成功、direct UPDATE/DELETEはtriggerで拒否 |
| F1-SNAPSHOT-02 | L2 | worker snapshotを2workerで同時改定し、pointer切替途中でCAS失敗させる | `UNIQUE(contract_id,worker_id,snapshot_version/hash)`、1 transaction rollback、未参照orphan 0、current pointerは実在snapshotのみ |
| F1-NULL-01 | L2 | §4.2の全clearable field familyを値→明示NULLへ更新 | `FieldStrategy.ALWAYS`でDBがNULLとなり、旧値が残らず各`MISSING_*`再評価対象になる。省略PATCHはclearせずvalidation error |
| F1-NULL-02 | L2 | clear transactionのCAS失敗・例外rollback | current値、snapshot、findingがtransaction前へ戻り、部分clear/孤児snapshot 0 |
| F1-MYSQL-FRESH-01 | L1 | 空DBをV1→V84 | V1 baselineとV84が同じtable/FK/index/entity契約を形成し、skip 0 |
| F1-MYSQL-LEGACY-01 | L2 | exact provenance付きV83公開形状＋既存契約fixtureへV84適用 | V83 fixtureがV84前の4表なし、既存契約あり、`flyway_schema_history` success=true/checksum固定。4表・typed field・FKが作成され、推測backfill 0、未確認はfail-closed |
| F1-MYSQL-PARTIAL-SCHEMA-01 | L2 | 4表の一部、column欠落、index欠落、旧FK定義を個別に持つpartial fixtureでV84/retry | 各present/absent/old definitionを検出し、既存行を壊さず再開。schemaはfreshと一致 |
| F1-MYSQL-FAILED-HISTORY-REPAIR-01 | L2 | 途中DDL後にfailed `flyway_schema_history` rowとchecksum不一致を作り、repair→forward migration | repair対象・checksum・installed_onをassert。repair前は起動/交付をfail-closed、repair後はV84を一度だけ完了しforward migrationで復旧 |
| F1-MYSQL-POST-APPLY-ROLLBACK-01 | L2 | migration適用前のcommit revertと、適用後のcommit revertを別実行 | 適用前revertは安全。適用後revertはDB rollbackと扱わず、forward repair migration/runbookのみを許可し、release gateへ記録 |
| F1-PII-OWNERSHIP-01 | L1 | T061 entityを直接portal/AI DTOへ渡すconsumer scan | 直接公開0件。detail/list/countのfield maskはT063、export/download/PDFはT064の別matrixへ移管され、T061 PASSの証拠に混在しない |

