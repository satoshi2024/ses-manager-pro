# Design — 派遣・準委任コンプライアンス台帳

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V82）

- `m_workplace(id, customer_id, name, address, organization_unit, phone)`。
- `t_contract_compliance_profile(contract_id, contract_type_detail, workplace_id, work_description,
  work_time/break/holidays/overtime, command_person_contact_id, client/responsible_person,
  dispatch_responsible_user_id, complaint_contact, treatment_scheme, limitation_date,
  training/safety/insurance fields, instruction_route, subcontract_allowed, acceptance_method,
  snapshot_json, version)`。
- `t_compliance_finding(id, contract_id, code, severity, status, detected_at, due_date,
  acknowledged_by/at, resolution_note, evidence_document_id)`。
- `t_document_delivery(document_id, recipient_contact_id, delivery_method, delivered_at, confirmed_at)`。

## 2. Rule engine

- 既存`LaborComplianceService`を`ComplianceRule`群へ分解するが、4既存code/挙動を維持。
- Rule inputはcontract/profile/BP tier/attendance/acceptance/document delivery。
- findingsは同じ`contract+code+condition fingerprint`でupsertし、解消時にstatus更新。毎回重複insertしない。
- 法的適用判定ではなく`MISSING_*`, `DEADLINE_*`, `RISK_*`を返す。

## 3. 帳票

- `ComplianceDocumentGenerator`と帳票種別別template version。
- 公式様式の項目対応表を`field-mapping.md`としてG2確認付きで保存。
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
| 就業条件 | `t_contract_compliance_profile`現在値 | `version`＋監査 | `snapshot_json`（帳票生成時に固定） | **帳票の交付日時点のsnapshot** | 未入力＝`MISSING_*` finding対象 |
| 就業先 | `m_workplace`現在値 | — | profile snapshotへ名称/住所を含める | 契約時点のsnapshot | 未設定＝finding |
| 指揮命令者 | `command_person_contact_id` | contact側の期間 | snapshotへ氏名を含める | 契約時点 | **未設定＝派遣ではfinding、準委任では正常** |
| 抵触日 | `limitation_date` | — | — | 現在値 | **未算定**。「抵触日なし」ではない。算定不能をfindingにする |
| 保険/教育訓練 | profile各field | — | snapshot | 契約時点 | **未確認**（「不要」ではない） |
| finding | `t_compliance_finding.status` | `detected_at`＋解消履歴 | — | 現在値 | — |
| 帳票交付 | `t_document_delivery` | 交付ごとに行追加 | — | 交付日 | `confirmed_at IS NULL`＝**受領未確認**（未交付ではない） |

- `limitation_date IS NULL` を「抵触日なし＝安全」と扱わない。**算定できていない**状態であり、
  `MISSING_LIMITATION_DATE` findingを出す。S02の「履歴なしとNULLの混同」と同型の事故になる。
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
| profile 未入力 | →入力済 | 状態CAS | — | — |
| 入力済 | →確定（帳票生成可） | `version` CAS | 同時編集 | 入力済へ |
| 確定 | →改定（新version） | `version` CAS | — | **過去snapshotは不変** |
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

## 6. テスト

rule境界、finding upsert/解消、帳票field mapping、deadline scheduler、profile snapshot、PII permission、
既存4rule回帰、法務fixture golden file。

