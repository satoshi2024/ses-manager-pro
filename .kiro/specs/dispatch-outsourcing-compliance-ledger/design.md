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
- `t_contract_compliance_snapshot(contract_id, snapshot_version, snapshot_hash, typed fields, typed snapshot)` と
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

### 3.1 T064実装時の逸脱・範囲（R15指摘対応・決定済み）

- **archive正本は常にFULLで生成し、downloadはviewer roleで再maskする**（R4.2）。
  冪等キー`(contract_id, document_type, template_version, snapshot_hash)`はrole非依存で全roleが共有する。
  マネージャーはMASK済みPDF、営業はLIMITED済みPDFのみ取得できる。配信物は再レンダリングであり、
  archive正本（FULL）とはバイト列が異なる。scanStatus CLEANの正本登録がdownloadの前提gate。
- **営業の帳票API**（R4.1/R4.2の「同左」を適用）: 一覧とmasked（LIMITED）downloadは可。
  生成・受領確認はwriteであり、営業は403（fail-closed。決定表にwrite列が無い中の安全側判断）。
- **帳票の出力項目scope**: generatorはsnapshot typed列に存在する項目を出力する（MAPPING-2026-07）。
  履歴table・worker snapshot由来の項目（苦情処理状況・キャリアconsulting・教育訓練・紹介予定・
  紛争防止・差異通知・性別/年齢/雇用期間・無期/60歳区分など）は、それらの行を作成する実装が
  存在しないためT064では出力せず、**T066（M）で履歴連携と共に全項目化**する。
  template versionは`m_system_config`の`compliance.template.<TYPE>.version`（既定1）。
  - **T066 Mでの最終化（R18）**: worker snapshot由来項目（性別・年齢区分・雇用期間種別/期間・
    無期雇用flag・60歳以上flag・労働者制限種別）は、`t_contract_compliance_worker_snapshot`が
    存在する場合に派遣元管理台帳へ出力する（`ComplianceDocumentGenerator`のworker引数。
    worker snapshotは帳票の交付日時点以前で最も新しい確定版だけを選び、交付後の版や
    `snapshot_at`不明の版は出力しない。worker snapshot未作成時も出力しない）。**履歴table由来項目（苦情処理状況・キャリアconsulting・
    教育訓練・紹介予定・紛争防止・差異通知）は、当該履歴を作成する書き込み経路が本specの実装範囲に
    存在しないため、受入対象外としてGATE-T066-HISTORYに記録する**（既存の反復履歴tableはT061で
    整備済み。書き込み経路は別spec/将来実装）。
- **当事者（派遣元=自社）** は`company.name`/`company.address`/`company.representative`
  （m_system_config）をsnapshot化して出力する。自社マスタが未実装のためconfigを正とする。

### 3.2 T065実装時の逸脱・決定（R3.3/R3.4）

- **期限通知の源**: 抵触日・文書期限の90/60/30日前通知は`t_compliance_finding.due_date`を正とする
  （ruleが算定した抵触日等）。帳票特有の期限はT066で`DEADLINE_*`系findingへ拡張する。
- **通知宛先は個人指定**（design §5.3）: 担当営業（契約sales_user_id）とHRユーザー（role=HR・有効）
  の各ユーザーへ`publishToUser`で発行する。組織一斉通知はしない。
  通知のdedupeKeyは`COMPLIANCE_DEADLINE:{findingId}:{段階}:user:{userId}`（宛先込みで1回）。
- **段階**: 90/60/30日前の各段階は初回該当時に1回だけ通知（同日・同段階の再実行で増えない）。
  91日=通知なし、90日ちょうど=90日前段階、89日=追加なし（60日前段階は60日ちょうどに発火）。
- **例外承認の失効**: EXCEPTION_APPROVEDには`t_compliance_finding.exception_expires_at`
  （V85で追加、V1は定義しない=MigrationScriptIntegrityTest規則）を保存し、
  期限超過を日次scheduler（ShedLock）でOPENへ戻す。
- **finding対応操作**: ack（OPEN/IN_PROGRESS→ACKNOWLEDGED）、in-progress、resolve（根拠note必須、
  任意evidence_document_id）、exception（note＋未来expiresAt必須）をAPI化。管理者/HR/マネージャーのみ
  （営業403）。遷移は@Version CASでrule実行との競合を制御。

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
| 就業条件 | `t_contract_compliance_profile`現在値 | `t_contract_compliance_snapshot` append-only | snapshot rowのtyped列＋`typed snapshot` | **帳票の交付日時点のsnapshot** | 未入力＝`MISSING_*` finding対象 |
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
| 入力済 | →確定（帳票生成可） | version CAS | 同時編集 | 入力済へ |
| 確定 | →改定（新version） | current pointerのexpected-version CAS | pointerをロックしてversion予約→snapshot INSERT→pointer切替 | CAS/一意/FK失敗は全rollback、A/B/Aの履歴を保持 |
| snapshot history | ←改定/訂正 | UNIQUE(contract_id,snapshot_version)、content hashは索引のみ | operation retryと新operationを区別 | 通常UPDATE/DELETE禁止。承認済みretention purge以外は削除不可 |
| finding OPEN | →acknowledged / →解消 / →例外承認 | 状態CAS | rule再実行との競合 | OPENへ戻る（再検出） |
| acknowledged | →対応中→解消 / →例外承認 | 状態CAS | — | — |
| 解消 | 再検出でOPENへ戻りうる | fingerprint一致で同一findingを再利用 | — | — |
| 例外承認 | 承認期限まで抑止 | 期限切れでOPENへ | — | — |

- findingは(contract_id, code, condition_fingerprint)でupsertし、既存4ruleのcode/挙動を維持する。
- content hashは履歴の同一性を示すがretryの冪等性キーではない。A(v1,hA)→B(v2,hB)→A(v3,hA)を許可する。
- 帳票生成の冪等キーは従来どおり(contract_id, document_type, template_version, snapshot_hash)とし、snapshot保存のoperation idempotencyとは別契約にする。

### 5.5 F1 schema / history matrix（R5で確定する技術契約）

| 領域 | current row | append-only history / snapshot | NULL・競合規則 | 担当task |
|---|---|---|---|---|
| 公式mappingのtyped field | t_contract_compliance_profileの専用列（2種制限日、SRC-E⑱、料金、責任、福利厚生、人数、協定/無期/60歳） | t_contract_compliance_snapshotへ同じ列名で複写 | 96 stable IDは専用列または指定historyへ1対1。未確認はNULL＋finding | T061 |
| workplace/organization | current master reference | t_contract_compliance_snapshotのtyped workplace/organization列 | master更新は過去snapshotを変更しない | T061 |
| worker-specific | current profileはcontract単位 | t_contract_compliance_worker_snapshot＋t_contract_compliance_worker_state | UNIQUE(contract_id,worker_id,snapshot_version)、worker current pointer/CASはworker別 | T061 |
| 苦情・雇用安定・教育・career・紹介予定 | currentは条件/窓口のみ | 各history tableを反復行として保存 | event訂正は新event INSERT、旧event不変 | T061/T062/B1 |
| profile snapshot | current profile＋current_snapshot_id/current_snapshot_version | t_contract_compliance_snapshot、UNIQUE(contract_id,snapshot_version)、content hashは非一意索引 | operation idempotencyはoperation_idで分離。A/B/Aを3version保持 | T061/B1 |
| snapshot operation | current pointerには含めない | t_compliance_snapshot_operation（operation_id、expected version、resulting snapshot、request hash、status） | 同じoperation retryは1行、新operationは同じcontentでも新version | T061 |
| explicit NULL | mutable current nullable columns only | history/snapshotは不変 | FieldStrategy.ALWAYS＋full DTO。省略PATCHはreject、CAS失敗はrollback | T061/T062 |
| history correction | current clear inventoryには含めない | event_id/event_type/supersedes_event_id/correction_reason/actor/occurred_at/effective interval/asOf key | 旧行UPDATE/DELETE禁止、CORRECTED/CANCELLEDは新行 | T061/T064 |
| retention purge | 通常mapperには削除操作なし | legal hold対象は保持 | 承認済みpurge operation id＋権限分離procedure＋監査eventのみ | T066/B1 |
| permission boundary | internal entity/tableを直接portal/AIへ渡さない | current/historyを同一tenant/data/org/file scopeで読む | detail/list/countはT063、CSV/Excel/PDF/downloadはT064 | T061/T063/T064 |
| migration path | fresh V1→V84 | exact V83 legacy、partial、failed history/repair | existing契約no-backfill、post-applyはforward repair | T061 |

このmatrixはlegal semanticsを新規確定しない。COMPLIANCE_RESPONSIBLEはruntime承認roleのassignment/監査だけを扱い、料金意味・2種制限日の法的表示条件・条件付き項目の適法性はGATE-T066-FIELD-SEMANTICSへ分離する。保存形状・NULL意味・競合境界はこのmatrixで確定済みである。

### 5.6 Snapshot write protocol / immutability enforcement

1. contractまたはworkerのcurrent stateをSELECT ... FOR UPDATEし、operationのexpected current versionを検証する。
2. t_compliance_snapshot_operationへoperation idempotencyを記録する。同じ成功operationのretryはresulting snapshotを返し、再INSERTしない。
3. next snapshot versionを予約し、typed snapshotをINSERTする。content hashの重複は許容する。
4. current pointerをexpected-version CASで切り替える。CAS/FK/一意制約失敗はoperation、snapshot、pointerを全rollbackし、orphan 0にする。
5. snapshot/history tableはDB triggerまたは権限境界でUPDATE/DELETEを拒否する。application mapperはINSERT/SELECTのみを公開する。
6. legal hold確認済みのretention purgeだけは承認済みpurge operation id、権限分離procedure、監査eventを伴う明示経路とする。通常endpointから呼べない。
7. history訂正はevent_type=CORRECTED/CANCELLED、supersedes_event_id、correction_reasonを持つ新行INSERTで行い、asOf解決は最新の有効eventを読む。

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
| G2-GATE-L0-02 | L0 / T060 | G2正本、spec、mapping、seed data | 特定の氏名/user ID固定をscan | 固定0件。role codeとruntime指名規則のみ |
| G2-GATE-L2-01 | L2 / T061-T064 | PROVISIONAL_REVIEWED、active assignmentなし | ACTIVE化または本番交付 | fail-closed。状態不変、監査event |
| G2-GATE-L2-02 | L2 / T061-T064 | active assignmentあり、承認eventのmapping hashが不一致 | ACTIVE化 | 拒否。対象version/hash一致を要求 |
| G2-GATE-L2-03 | L2 / T061-T064 | 旧責任者の承認後に管理者が交代 | assignment終了/追加 | 旧承認actor snapshotと過去帳票は不変 |
| G2-GATE-M-01 | L4 / T066 | assignment/承認eventあり、外部専門家Reviewなし | M PASSまたは本番交付 | 拒否。PROVISIONAL_REVIEWEDを維持 |
| G2-GATE-M-02 | L4 / T066 | active assignment、対象hash承認event、外部専門家Reviewが有効 | ACTIVE化と本番交付 | 成功。version/hashと全証跡を監査保存 |

### 6.2 T061 F1 direct regression matrix（R5 fix scope）

| test ID | level | fixture / operation | expected |
|---|---|---|---|
| F1-MAP-01 | L1 | FM-C-01〜FM-L-30の96 stable row IDをcanonical schema manifestへ照合 | 全96 IDが専用typed columnまたは指定historyへ1件ずつ解決。technical shape未解決0件 |
| F1-SNAPSHOT-01 | L2 | 同じoperation retry、A(v1,hA)→B(v2,hB)→A(v3,hA)、direct SQL UPDATE/DELETE、失敗rollback | 同じoperationは1行、A/B/Aは3version、同じcontent hashを許容、pointerはv3、旧snapshot不変、orphan 0、直接変更拒否 |
| F1-SNAPSHOT-02 | L2 | worker A/Bを独立に同時改定し、expected current versionを競合させる | workerごとにcurrent pointer/version/CASが独立し、各競合は1勝、FK/rollback後orphan 0 |
| F1-NULL-01 | L2 | field-mapping §4.3のcurrent clearable field familyを値→明示NULL、field省略PATCH、CAS失敗 | current列だけNULL化、旧値残存なし、CAS失敗は全rollback。T061ではraw SQL＋MyBatis-Plus full DTOのmapper test（F1NullClearMapperTest）で値→NULLとCAS 0行を担保し、省略PATCH rejectはT063のAPI導入時にvalidationとして担保する |
| F1-HISTORY-CORRECTION-01 | L2 | complaint/direct-hire/notification differenceの原event→CORRECTED/CANCELLED event | 旧eventは不変、新eventにsupersedes/correction reason/actorを保存、asOfは最新有効eventを解決 |
| F1-MYSQL-FRESH-01 | L1 | 空DBをV1→V84 | V1 baselineとV84が同じtable/FK/index/entity契約を形成し、skip 0 |
| F1-MYSQL-LEGACY-01 | L2 | exact provenance付きV83公開形状＋既存契約fixtureへV84適用 | V83 fixture、既存契約、success/checksumを固定し、推測backfill 0、未確認はfail-closed |
| F1-MYSQL-PARTIAL-SCHEMA-01 | L2 | table/column/index/FKのpresent/absent/old definitionを持つpartial fixtureでV84/retry | 各差分を検出して再開し、freshと同じschemaへ収束 |
| F1-MYSQL-FAILED-HISTORY-REPAIR-01 | L2 | failed history row、checksum不一致、repair→forward migration | repair前は起動/交付fail-closed、repair/checksum/installed_onをassert、V84は一度だけ完了 |
| F1-MYSQL-POST-APPLY-ROLLBACK-01 | L2 | 適用前commit revertと適用後commit revertを別実行 | 適用前revertは安全、適用後revertはDB rollback扱いせずforward repairのみ許可 |
| F1-PII-OWNERSHIP-01 | L1 | T061 entityのportal/AI consumer scan | 直接公開0件。detail/list/countはT063、CSV/Excel/PDF/downloadはT064のmatrixで証明 |

### 6.3 T066 M direct regression matrix

| test ID | level | fixture / operation | expected |
|---|---|---|---|
| T066-ASOF-01 | L2 | H2実APIでworker snapshotを交付日時点の前・同時刻・後・`snapshot_at` NULLで用意し、生成archive・FULL download・MASK/LIMITED download・template version切替・再生成を実行 | 生成時に一度だけ確定した`deliveredAt`をdeliveryへ保存し、archiveとdownloadが同じ交付時点の最新確定版だけを使う。交付後またはasOf不明のworker項目は出力せず、mask・template切替・冪等性を維持する |

