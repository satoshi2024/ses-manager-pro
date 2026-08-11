# G2 公式様式 field mapping（T060 provisional baseline）

> **状態: PROVISIONAL_REVIEWED / T060 COMPLETE（R10 T060 PASS、R4-P1-01 VERIFIED_CLOSED）**
>
> 本書は `dispatch-outsourcing-compliance-ledger` T060 の成果物であり、現時点では
> production code、DDL、migration、SecurityConfigを変更しない。項目をシステムへ対応付ける文書であり、
> 個別契約の法的適否を自動判定するものではない。`コンプライアンス責任者` は個人を固定しない
> application roleであり、runtimeで管理者が指名・交代する。role assignment、実actor承認event、資格/根拠確認、
> ページ設定された動的policyを満たす実在external ReviewはACTIVE化/M/本番gateで管理し、T060完了と後続開発をブロックしない。
> reviewer typeの具体値をcode/DDL/seedへ固定しない。R19-P1-01の現行governanceは
> `g2-gate-decision-delta-r19-p1-01.md`を正とし、96 stable mapping rowの内容は本deltaで変更しない。

## 1. source of truth と version / effective period

確認日はすべて **2026-08-09（JST）**。公式掲載ページは「労働者派遣に係る『契約書』『通知書』『台帳』関係様式例（令和8年7月版※令和8年10月改正対応）」を案内している。PDFは様式例ではなく記載例として掲載されているため、帳票生成の表示項目を特定する根拠として使用し、法的結論は内部責任者・外部専門家の確認に委ねる。

| ID | 資料 | 版・確認日・施行期間 | URL |
|---|---|---|---|
| SRC-INDEX | 北海道労働局「労働者派遣事業」掲載ページ | 令和8年7月版、令和8年10月改正対応／確認日: 2026-08-09／掲載情報の施行区分は下記 mapping version へ分離 | https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/hourei_seido_tetsuzuki/roudousha_haken/newpage_00448.html |
| SRC-C | 労働者派遣個別契約書（記載例） | 令和8年7月版（令和8年10月改正対応）／確認日: 2026-08-09／`MAPPING-2026-07` と `MAPPING-2026-10` に分離 | https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722622.pdf |
| SRC-E | 就業条件明示書（記載例） | 令和8年7月版（令和8年10月改正対応）／確認日: 2026-08-09／`MAPPING-2026-07` と `MAPPING-2026-10` に分離 | https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722631.pdf |
| SRC-N | 派遣先通知書（記載例） | 令和8年7月版（令和8年10月改正対応）／確認日: 2026-08-09／`MAPPING-2026-07` と `MAPPING-2026-10` に分離 | https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722633.pdf |
| SRC-L | 派遣元管理台帳（記載例） | 令和8年7月版（令和8年10月改正対応）／確認日: 2026-08-09／派遣終了日から3年をbaselineとする | https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722641.pdf |

### 1.1 mapping version の扱い

| version | effective period | 適用方針 |
|---|---|---|
| `MAPPING-2026-07` | 2026-07-01〜2026-09-30 | 令和8年7月版の項目を適用する。2026-10-01施行分の追加通知項目を過去の交付物へ遡及しない。 |
| `MAPPING-2026-10` | 2026-10-01〜 | 令和8年10月改正対応版を新versionとして保持する。待遇差説明を求める権利の通知項目は、公式一次source・正確な文言・対象が確定するまで未確定gateとし、旧版へ遡及しない。 |

契約・交付物は `template_version`、`effective_from`、`effective_to`、`snapshot_hash` を保持して、同じsnapshotを旧versionの帳票へ再計算しない。`2026-10-01` の具体的な法的文言・適用対象は、4公式PDFに確認できないためmapping表へ追加せず、`GATE-T060-2026-10` で直接示す一次sourceが特定されるまで未確定とする。mapping作成者が法的適否を推測して補完しない。

### 1.2 公式4PDFにない2026-10項目の扱い

`待遇差説明を求める権利の通知` は、今回照合したSRC-C/SRC-E/SRC-N/SRC-Lの公式記載例4PDF（計9ページ）に項目名・正確な文言・対象範囲を確認できなかったため、4帳票のfield mapping行には含めない。2026-10-01版のversion/effective periodだけを保持し、当該通知を追加する場合は、別の公式一次source URL、版、確認日、施行開始/終了、対象帳票、画面/出力位置を `GATE-T060-2026-10` で確定してから別行として起票する。`MAPPING-2026-07`へ遡及適用しない。

## 2. 既存資産・確定フィールドの凡例

- **既存**: 現行コードまたはV1 baselineに存在する列。DB column名で記載する。
- **typed snapshot**: 契約・worker・交付時点で列型を持つ不変snapshot。current masterを帳票生成時に再読しない。
- **append-only history**: 受付・処理・通知・訂正など時系列で反復する行。既存eventをUPDATE/DELETEせず、新eventで訂正する。
- **未決gate**: 法的意味、条件付き表示、保存categoryなど法務受入で確認する論点。保存先・履歴形状・NULL意味は本書で確定済み。

### 2.1 field permission code

| code | 可視範囲 |
|---|---|
| `P0_FULL` | 管理者・法務/HRが、認可scope内で全fieldを閲覧・export・downloadできる。 |
| `P1_MASK` | マネージャーが組織scope ∩ DataScopeで閲覧するが、待遇・保険・個人識別情報はmaskする。export/PDFも同じmask。 |
| `P2_LIMITED` | 営業が担当契約の業務遂行に必要な限定fieldだけ閲覧する。個人別待遇・保険・苦情詳細は出さない。 |
| `P3_SELF` | 要員本人がS14経由で自分の就業条件明示書に限り閲覧する。 |
| `P4_NONE` | portal userは本台帳を直接閲覧しない。 |
| `P5_SYSTEM` | schedulerは帳票生成・finding算定に必要なfieldのみ使い、人へ返す通知は宛先を個人指定する。 |

### 2.2 保存・asOfの共通規則

- `R3Y`: 派遣元/先管理台帳は派遣終了日を起算日とする3年baseline。legal holdまたは税務文書categoryに該当する場合は削除せず延長する。
- `ARCHIVE_PENDING`: 個別契約書、就業条件明示書、派遣先通知書の保存categoryと起算点はG2責任者の承認待ち。台帳の3年baselineをこれらへ黙って一般化しない。税務文書categoryならG2既定の10年を適用する。
- `ASOF_SNAPSHOT`: 帳票は交付・再生成要求時に指定されたtemplate versionと、契約時点の `typed snapshot` を読む。現在マスタの値で過去帳票を書き換えない。
- 明示NULLは「安全」「不要」を意味しない。`workplace_limitation_date` または `organization_limitation_date` がNULL は未算定、保険状態NULLは未確認、`confirmed_at IS NULL` は受領未確認としてfinding/状態へ渡す。
### 2.3 コンプライアンス責任者 role と承認event

内部のmapping承認主体は、法定帳票に記載される派遣元責任者・派遣先責任者とは別の application role とする。自然人の氏名・user IDをspec、seed、mappingへ事前固定しない。

| 項目 | 現行決定 |
|---|---|
| role code / 表示名 | `COMPLIANCE_RESPONSIBLE` / `コンプライアンス責任者` |
| assignment scope | workplace単位。contractから`t_contract_compliance_profile.workplace_id`をserver-side解決し、request workplaceを信用しない |
| assignment period | `[effective_from,effective_to)`、NULL endは無限未来。管理者が指名・終了し、同一tenant/workplace/asOfで1件だけ |
| 承認可能な主体 | asOf時点の有効assignmentへ指名されたuser本人。内部roleは管理者/HR/マネージャー。管理者も別actorのassignmentをbypassできない |
| approval action | append-only `APPROVE / REJECT / REVOKE`。target/supersedes/chain、mapping version/hash、review policy hash、evidence exact version/hashを保存 |
| 未指名/失効時 | ACTIVE化と新規formal generate/deliveryをfail-closed。過去delivery downloadはcurrent gateを再評価しない |

法定の派遣元責任者・派遣先責任者は、事業所/契約ごとのruntime master/assignmentであり、`valid_from`/`valid_to`を持つ別概念とする。交代時は旧行を終了し、新行の部署・役職・氏名・連絡先を帳票作成時にsnapshotする。過去帳票を上書きしない。

### 2.4 mapping lifecycle

| 状態 | 到達条件 | 許可 | 禁止 |
|---|---|---|---|
| `DRAFT` | 公式field mappingを起草中 | 文書編集、L0 | 後続実装baseline、本番交付 |
| `PROVISIONAL_REVIEWED` | 公式URL/版/確認日/effective period、全項目mapping、非空のdynamic review policy、L0、独立Review、mapping/policy hash固定 | T061〜T065の開発baseline | mapping/source/policy編集、M PASS、本番交付 |
| `ACTIVE` | tenant mappingが有効。指定approval eventのassignment/actor/hashと、全policy groupを満たす実在external review/evidenceをDB再解決 | target workplaceでcurrent assignment/approval/reviewを再評価した新規交付 | 他workplace・旧assignment approvalの流用 |
| `SUPERSEDED` | 後継versionがACTIVE | 過去帳票再現、監査参照 | 新規契約/交付への適用 |

`PROVISIONAL_REVIEWED`は法的適否の承認ではなく、開発に必要なfield対応が独立Review済みであることを示す。
実actor承認eventやexternal Reviewを捏造して開発gateを通過させず、実運用時に対象version/hash/policy hashへ結び付けて取得する。

## 3. 帳票別 field mapping

> §3.1〜3.4は公式行→resolutionの閲覧用対応表であり、schemaの正本は §3.5 manifest（96 stable ID）と §4 canonical resolution table である。DB column列にはcanonical resolutionだけを記載し、候補・別案は載せない。両者から異なるDDLが導出されることはない。

### 3.1 労働者派遣個別契約書（SRC-C）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column（F1 canonical resolution） | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 当事者、契約番号、契約締結日 | 個別契約を特定する基本情報 | 2026-07-01〜2026-09-30／MAPPING-2026-07; 2026-10-01〜／MAPPING-2026-10 | t_contract.contract_no, contract_date, customer_id, typed party_name/address/representative snapshot | 契約詳細・契約編集の基本情報 | 表紙・冒頭の甲乙・契約No/日付 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（相手先/契約識別のみ） | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先事業所の名称・所在地・電話 | 事業所単位の契約先を特定 | 同上 | m_workplace.id, typed workplace_name/address/phone snapshot | compliance profileの就業先 | 事業所欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業場所の名称・所在地・部署・電話 | 派遣労働者が実際に就業する場所。連絡可能な内容を記載 | 同上 | m_workplace.id, typed workplace_department/address/phone snapshot（WORKPLACE_ORG_SNAPSHOT。`t_contract.work_location`は使わない） | 就業先profileの就業場所 | 就業場所欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 組織単位（名称・組織の長の職名） | 個人単位の期間制限の基礎。組織単位の特定が必要 | 同上 | t_contract_compliance_snapshot.organization_unit, organization_head_title | profileの組織単位 | 就業場所の下段 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 業務内容・政令業務該当 | 役務の具体的内容、該当時は政令条項 | 同上 | work_description, statutory_job_flag/referenceの専用列＋snapshot（WORK_DESCRIPTION_TYPED。`t_contract.job_description`は使わない） | 契約detailの業務内容 | 業務内容欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 業務に伴う責任の程度・権限の有無・内容 | 権限なし/ありと、ありの場合の責任内容 | 同上 | t_contract_compliance_snapshot.responsibility_level, responsibility_detail | profileの責任の程度 | 業務内容の直後 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣期間（開始・終了） | 契約期間。開始/終了の期間整合性を検証 | 同上 | t_contract.start_date, t_contract.end_date, typed dispatch_from/to | 契約基本情報 | 派遣期間欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業日・休日・休暇除外 | 曜日、祝日、夏季等の除外日 | 同上 | t_contract_compliance_snapshot.work_day_code, holiday_calendar_code, excluded_date history | profileの就業日/休日 | 就業日欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 指揮命令者（部署・役職・氏名） | 派遣契約の指揮命令者 | 同上 | t_contract_compliance_snapshot.command_person_department/title/name/phone | profileの指揮命令者 | 指揮命令者欄 | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先責任者（部署・役職・氏名・電話） | 責任者の連絡先 | 同上 | t_contract_compliance_snapshot.client_responsible_department/title/name/phone | profileの派遣先責任者 | 責任者欄 | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣元責任者（部署・役職・氏名・電話） | 派遣元側の責任者 | 同上 | t_contract_compliance_snapshot.dispatch_responsible_department/title/name/phone | profileの派遣元責任者 | 責任者欄 | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業時間・休憩 | 始業終業、休憩時刻/分数 | 同上 | t_contract_compliance_snapshot.work_start_minute, work_end_minute, break_*_minute history | profileの就業時間 | 就業時間欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 時間外・休日労働 | 1日/月/年の上限、休日労働の範囲。派遣元36協定の範囲内 | 同上 | t_contract_compliance_snapshot.agreement_reference_id, overtime_daily/monthly/yearly_limit | profileの時間外 | 時間外欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 事業所単位の派遣可能期間の抵触日 | 期間制限に抵触する最初の日 | 同上 | t_contract_compliance_snapshot.workplace_limitation_date | profileの抵触日 | 就業条件明示書/関連通知へ出力 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 組織単位（個人単位）の派遣可能期間の抵触日 | 組織単位における個人単位の抵触日。無期雇用等は適用なしの場合あり | 同上 | t_contract_compliance_snapshot.organization_limitation_date | profileの抵触日 | 就業条件明示書/関連通知へ出力 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情申出先（派遣元） | 部署・役職・氏名・電話。申出を受ける者 | 同上 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | profileの苦情窓口 | 苦情欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（詳細はmask） | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情申出先（派遣先） | 部署・役職・氏名・電話 | 同上 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | profileの苦情窓口 | 苦情欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情処理方法・連携体制 | 即時連絡、責任者中心の処理、本人への結果通知 | 同上 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | profileの苦情処理 | 苦情欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 安全・衛生 | 甲乙の責任分担、適用規程 | 同上 | t_contract_compliance_snapshot.safety_responsibility_detail, safety_rule_reference | profileの安全衛生 | 安全衛生欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 福利厚生 | 待遇情報以外の便宜供与を具体記載 | 同上 | t_contract_compliance_snapshot.benefits_detail, benefits_provided_flag | profileの福利厚生 | 福利厚生欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（待遇詳細はmask） | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣人員 | 何人派遣するか | 同上 | t_contract_compliance_snapshot.dispatch_headcount + worker snapshot count | profileの派遣人員 | 派遣人員欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 協定対象派遣労働者に限定するか | 労使協定方式/派遣先均等均衡方式の区分 | 同上 | t_contract_compliance_snapshot.agreement_target_flag, treatment_scheme | profileの待遇方式 | 協定対象欄 | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 無期雇用/60歳以上の者に限定するか | 3区分（無期限定、60歳以上限定、限定しない） | 同上 | t_contract_compliance_worker_snapshot.employment_term_type/from/to, indefinite_worker_flag, age_over_60_flag, worker_restriction_type | profileのworker制限 | 制限欄 | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣労働者の雇用安定措置 | 解除前申入れ、就業機会確保、損害賠償等、理由明示 | 同上 | t_contract_compliance_snapshot.employment_stability_preference + t_employment_stability_history.request/response/action | profileの雇用安定措置 | 雇用安定措置欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先が雇用する場合の紛争防止措置 | 紹介可能/不可に応じた手数料または申出方法 | 条件付き（派遣元が職業紹介を行える場合） | t_direct_hire_dispute_history.measure/fee/request_method/effective_from/to | profileの紹介/紛争防止 | 紛争防止措置欄 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 期間制限を受けない業務に係る事項 | 有期プロジェクト、育休/介護休業代替、日数限定等 | 条件付き | t_contract_compliance_snapshot.limitation_exemption_type/detail/basis/from/to | profileの期間制限例外 | 備考欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 紹介予定派遣の予定労働条件 | 契約期間、更新、更新上限、業務/場所の変更範囲、試用期間、賃金、保険、喫煙措置、雇用主等 | 紹介予定派遣の場合のみ | t_planned_introduction_termsのsub-field列（契約期間・更新・業務/場所変更範囲・試用期間・賃金・保険・喫煙措置・雇用主）（PLANNED_INTRODUCTION_TERMS） | profileの条件付きセクション | 別紙 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |

### 3.2 就業条件明示書（SRC-E）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column（F1 canonical resolution） | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 宛先の派遣労働者氏名・派遣元名・住所・使用者職氏名 | worker-specific明示書の宛先と使用者 | 同上 | t_contract_compliance_worker_snapshot.worker_name/employer_name/employer_address/employer_title | profileのworker選択・派遣元情報 | 冒頭 | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P3_SELF`; `P1_MASK`,`P2_LIMITED`は原則不可 | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先事業所の名称・所在地・電話 | 就業先の特定 | 同上 | m_workplace.id, typed workplace_name/address/phone snapshot | 就業先profile | ①就業先 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業場所の名称・所在地・部署・電話 | 実就業場所の明示 | 同上 | m_workplace.id, typed workplace_department/address/phone snapshot（WORKPLACE_ORG_SNAPSHOT。`t_contract.work_location`は使わない） | 就業場所 | ①就業場所 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 組織単位 | 個人単位抵触日の算定単位 | 同上 | t_contract_compliance_snapshot.organization_unit, organization_head_title | 組織単位 | 就業場所欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 業務内容・責任の程度 | 業務と権限の明示 | 同上 | t_contract_compliance_snapshot.responsibility_level, responsibility_detail | 業務/責任 | ②③ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣期間 | 派遣開始/終了 | 同上 | t_contract.start_date, t_contract.end_date, typed dispatch_from/to | 契約基本情報 | ⑥/⑫/⑬ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 事業所単位の抵触日 | 事業所単位の期間制限の最初の日。延長の影響あり | 同上 | t_contract_compliance_snapshot.workplace_limitation_date | 抵触日欄 | 期間欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 個人単位の抵触日 | 組織単位の期間制限の最初の日。無期雇用等は適用なしの場合あり | 同上 | t_contract_compliance_snapshot.organization_limitation_date | 抵触日欄 | 期間欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業日・休日 | 曜日、祝日、休暇除外 | 同上 | t_contract_compliance_snapshot.work_day_code, holiday_calendar_code, excluded_date history | 就業日/休日 | ⑥ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 指揮命令者 | 部署・役職・氏名 | 同上 | t_contract_compliance_snapshot.command_person_department/title/name/phone | 指揮命令者 | ⑤ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先責任者・派遣元責任者 | 部署・役職・氏名・電話 | 同上 | t_contract_compliance_snapshot.client_responsible_department/title/name/phone | 責任者 | ⑭ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業時間・休憩 | 始業終業、休憩時刻/分数 | 同上 | t_contract_compliance_snapshot.work_start_minute, work_end_minute, break_*_minute history | 就業時間 | ⑦ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 時間外・休日労働 | 1日/月/年と休日労働。36協定範囲内 | 同上 | t_contract_compliance_snapshot.agreement_reference_id, overtime_daily/monthly/yearly_limit | 時間外 | ⑮ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 安全衛生 | 派遣先責任の明示 | 同上 | t_contract_compliance_snapshot.safety_responsibility_detail, safety_rule_reference | 安全衛生 | ⑧ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 福利厚生 | 制服、施設利用等の具体的便宜 | 同上 | t_contract_compliance_snapshot.benefits_detail, benefits_provided_flag | 福利厚生 | ⑯ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情申出先・処理方法・連携体制 | 派遣元/先の窓口と相互連携 | 同上 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | 苦情窓口 | ⑨ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF`（詳細mask） | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 雇用安定措置 | 契約解除時の新たな就業機会/休業/解雇予告等 | 同上 | t_contract_compliance_snapshot.employment_stability_preference + t_employment_stability_history.request/response/action | 雇用安定措置 | ⑩ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 紹介予定派遣 | 予定労働条件を条件付き明示 | 紹介予定派遣の場合のみ | t_planned_introduction_history（紹介時期・採否・非採用理由の反復行。予定労働条件sub-fieldはt_planned_introduction_termsを参照）（PLANNED_INTRODUCTION_HISTORY） | 条件付きセクション | ⑪別紙 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 紛争防止措置 | 派遣先雇用時の申出/手数料等 | 条件付き | t_direct_hire_dispute_history.measure/fee/request_method/effective_from/to | 紛争防止措置 | ⑰ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣料金 | 月額/日額/時間額 | 同上 | t_contract_compliance_snapshot.dispatch_fee_amount, t_contract_compliance_snapshot.dispatch_fee_basis, t_contract_compliance_snapshot.dispatch_fee_currency | compliance profileの派遣料金欄（売上/粗利列とは分離） | ⑳ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 社会保険の加入手続きが完了していない場合の理由（⑱） | 社会保険の加入手続きが未完了の場合のみ理由を記載する公式項目。完了済みを理由欄へ補完しない | 同上 | t_contract_compliance_snapshot.social_insurance_procedure_incomplete_reason（SRC-E⑱独立列） | 就業条件明示書profileの社会保険手続欄。未完了時のみ入力可能 | 就業条件明示書 備考⑱ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF`（理由詳細はmask） | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 保険/賃金/就業場所/喫煙措置（予定労働条件） | 紹介予定派遣の場合の条件付き項目 | 紹介予定派遣の場合のみ | t_planned_introduction_termsのsub-field列（就業場所・賃金・保険・喫煙措置）（PLANNED_INTRODUCTION_TERMS） | 条件付きセクション | 別紙 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |

### 3.3 派遣先通知書（SRC-N）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column（F1 canonical resolution） | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 通知日・宛先・派遣元所在地・事業所名・代表者 | 通知の発行主体と相手先 | 同上 | t_document_delivery.delivery_date, sender/recipient typed snapshot | 交付画面 | 冒頭 | delivery時点のtyped snapshotとrecipient/file scopeを固定。confirmed_at IS NULLは受領未確認 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 契約締結日・契約No | 対象契約の参照 | 同上 | t_contract.contract_no, contract_date, typed party snapshot（CONTRACT_PARTY_PERIOD_SNAPSHOT） | 契約detail | 冒頭 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣労働者の氏名 | worker-specific通知 | 同上 | t_contract_compliance_worker_snapshot.worker_name/employer_name（WORKER_PII_SNAPSHOT） | worker選択 | ① | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（営業は原則非表示） | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 性別 | 記載例の区分項目 | 同上 | t_contract_compliance_worker_snapshot.gender | worker profile（権限者のみ） | ① | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 年齢区分（45歳以上/18歳未満/その他） | 45歳以上はその旨、18歳未満は具体年齢。その他はチェック漏れ防止の補助 | 同上 | t_contract_compliance_worker_snapshot.age_band, age_at_reference_date | worker profile/通知プレビュー | ① | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 60歳以上/60歳未満 | 60歳以上か否か | 同上 | t_contract_compliance_worker_snapshot.employment_term_type/from/to, indefinite_worker_flag, age_over_60_flag, worker_restriction_type | worker profile/通知プレビュー | ④ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 健康保険の資格取得届提出有無 | 有/無。無の場合は具体的理由または手続状況 | 同上 | t_contract_compliance_worker_snapshot.insurance_status, missing_reason, expected_date | insurance section | ⑤ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 厚生年金保険の資格取得届提出有無 | 有/無。無の場合は具体的理由または手続状況 | 同上 | t_contract_compliance_worker_snapshot.insurance_status, missing_reason, expected_date | insurance section | ⑤ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 雇用保険の資格取得届提出有無 | 有/無。無の場合は具体的理由または手続状況 | 同上 | t_contract_compliance_worker_snapshot.insurance_status, missing_reason, expected_date | insurance section | ⑤ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 協定対象派遣労働者か否か | 協定対象/非対象の区分 | 同上 | t_contract_compliance_snapshot.agreement_target_flag, treatment_scheme | profile | ② | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣労働者の雇用期間 | 無期/有期、具体期間 | 同上 | t_contract_compliance_worker_snapshot.employment_term_type/from/to, indefinite_worker_flag, age_over_60_flag, worker_restriction_type | worker-contract section | ③ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は限定 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 契約内容と明示内容の差異（派遣期間・就業日） | 差異がある場合のみ記載 | 差異発生時 | t_notification_difference_history.difference_type, contract_snapshot_id, notice_snapshot_id, occurred_at | 差異入力/確認 | ⑥ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 契約内容と明示内容の差異（就業時間・休憩） | 差異がある場合のみ記載 | 差異発生時 | t_notification_difference_history.difference_type, contract_snapshot_id, notice_snapshot_id, occurred_at | 差異入力/確認 | ⑥ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 契約内容と明示内容の差異（責任者） | 派遣元/先責任者に差異がある場合 | 差異発生時 | t_notification_difference_history.difference_type, contract_snapshot_id, notice_snapshot_id, occurred_at | 差異入力/確認 | ⑥ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 契約内容と明示内容の差異（時間外・休日労働） | 差異がある場合のみ記載 | 差異発生時 | t_notification_difference_history.difference_type, contract_snapshot_id, notice_snapshot_id, occurred_at | 差異入力/確認 | ⑥ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 契約内容と明示内容の差異（その他） | 上記以外の差異 | 差異発生時 | t_notification_difference_history.difference_type, contract_snapshot_id, notice_snapshot_id, occurred_at | 差異入力/確認 | ⑥ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 保険証等の写しの提示/送付 | 派遣元から派遣先への提示/送付。未知file/scan障害はfail-closed | 同上 | t_document_delivery.document_id, recipient_contact_id, delivered_at, confirmed_at | document delivery | 添付/交付記録 | delivery時点のtyped snapshotとrecipient/file scopeを固定。confirmed_at IS NULLは受領未確認 | `ARCHIVE_PENDING` | `P0_FULL`; `P1_MASK`,`P2_LIMITED`不可 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |

### 3.4 派遣元管理台帳（SRC-L）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column（F1 canonical resolution） | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 派遣労働者氏名 | 個人別台帳の主キー相当 | 同上 | t_contract_compliance_worker_snapshot.worker_name/employer_name/employer_address/employer_title | ledger detail | ① | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`（mask可）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 無期/有期雇用・雇用期間 | 派遣期間と異なるため別記載 | 同上 | t_contract_compliance_worker_snapshot.employment_term_type/from/to, indefinite_worker_flag, age_over_60_flag, worker_restriction_type | worker/ledger | ③ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 協定対象派遣労働者か否か | 労使協定方式/均等均衡方式 | 同上 | t_contract_compliance_snapshot.agreement_target_flag, treatment_scheme | ledger profile | ② | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣期間 | 雇用期間と分けて記録 | 同上 | t_contract.start_date, t_contract.end_date, typed dispatch_from/to | contract/ledger | ④ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 60歳以上か否かの別（④） | 派遣労働者が60歳以上か60歳未満かを個人別台帳へ記載する公式項目 | 同上 | t_contract_compliance_worker_snapshot.employment_term_type/from/to, indefinite_worker_flag, age_over_60_flag, worker_restriction_type | worker/ledger profileの年齢区分。基準日を表示 | 派遣元管理台帳 ④ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`（年齢詳細mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 健康保険の提出有無・未加入理由・取得予定日 | 保険ごとに有/無、無の理由/手続状況 | 同上 | t_contract_compliance_worker_snapshot.insurance_status, missing_reason, expected_date | insurance section | ⑰ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 厚生年金保険の提出有無・未加入理由・取得予定日 | 同上 | 同上 | t_contract_compliance_worker_snapshot.insurance_status, missing_reason, expected_date | insurance section | ⑰ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 雇用保険の提出有無・未加入理由・取得予定日 | 同上 | 同上 | t_contract_compliance_worker_snapshot.insurance_status, missing_reason, expected_date | insurance section | ⑰ | worker-specific typed snapshotを作成し、基準日・effective periodを保存。current masterを再読しない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先名称 | 契約先の特定 | 同上 | t_contract.customer_id, typed client_name/address snapshot | ledger header | ⑤ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先事業所名称・所在地・電話 | 事業所を特定 | 同上 | m_workplace.id, typed workplace_name/address/phone snapshot | workplace section | ⑥⑦ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業場所名称・所在地・電話 | 実就業場所 | 同上 | m_workplace.id, typed workplace_department/address/phone snapshot（WORKPLACE_ORG_SNAPSHOT。`t_contract.work_location`は使わない） | workplace section | ⑦ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 組織単位 | 個人単位期間制限の単位 | 同上 | t_contract_compliance_snapshot.organization_unit, organization_head_title | workplace section | ⑦ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 業務内容・政令業務該当 | 業務の具体化 | 同上 | work_description, statutory_job_flag/referenceの専用列＋snapshot（WORK_DESCRIPTION_TYPED。`t_contract.job_description`は使わない） | ledger | ⑩ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 業務に伴う責任の程度 | 権限なし/あり、内容 | 同上 | t_contract_compliance_snapshot.responsibility_level, responsibility_detail | ledger | ⑪ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業日 | 実績との比較対象 | 同上 | t_contract_compliance_snapshot.work_day_code, holiday_calendar_code, excluded_date history | ledger | ⑧ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣先責任者 | 部署・役職・氏名・電話 | 同上 | t_contract_compliance_snapshot.client_responsible_department/title/name/phone | ledger | ⑭ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣元責任者 | 部署・役職・氏名・電話 | 同上 | t_contract_compliance_snapshot.dispatch_responsible_department/title/name/phone | ledger | ⑭ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 就業時間・休憩 | 始業/終業/休憩 | 同上 | t_contract_compliance_snapshot.work_start_minute, work_end_minute, break_*_minute history | ledger | ⑨ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 時間外・休日労働 | 36協定の範囲内 | 同上 | t_contract_compliance_snapshot.agreement_reference_id, overtime_daily/monthly/yearly_limit | ledger | ⑮ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 月次就業状況・タイムシート | 別添タイムシート。既存客先工数を流用するが雇用勤怠とは分離 | 同上 | t_ledger_work_snapshot.work_month, work_record_*, closed_at | ledger monthly tab | 別添・月次反復 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情処理状況（申出日） | 苦情申出を受けた日 | 反復履歴 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | findings/ledger complaint tab | ⑫反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（詳細mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情内容 | 申出内容 | 反復履歴 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | complaint tab | ⑫反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 苦情処理状況・結果通知 | 処理内容、本人通知 | 反復履歴 | t_contract_compliance_snapshot.source/client_complaint_contact_* + t_compliance_complaint_history.received_at/content/action/resolution/notified_at | complaint tab | ⑫反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 教育訓練の内容 | 日時、時間、研修内容 | 反復履歴 | t_training_history.occurred_at, minutes, content | training tab | ⑱反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| キャリア・コンサルティング | 日時・内容 | 反復履歴 | t_career_consulting_history.occurred_at, content | career tab | ⑲反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 希望する雇用安定措置 | 労働者の希望内容 | 条件/反復 | t_contract_compliance_snapshot.employment_stability_preference + t_employment_stability_history.request/response/action | stability tab | ⑳ | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 雇用安定措置の内容 | 依頼日時/方法、回答日時/内容、他派遣先紹介等 | 反復履歴 | t_contract_compliance_snapshot.employment_stability_preference + t_employment_stability_history.request/response/action | stability tab | ㉑反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 期間制限を受けない業務 | 例外業務の場合のみ | 条件付き | t_contract_compliance_snapshot.limitation_exemption_type/detail/basis/from/to | other | ⑯ | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状・NULL意味はF1で確定済み。法的条件/表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 紹介予定派遣に関する事項 | 該当時の紹介時期/内容、採否、非採用理由 | 条件付き・反復 | t_planned_introduction_history.introduction_date/outcome/reason | introduction tab | ⑬反復行 | append-only history。訂正・取消は新event、旧event不変。asOfは有効な最新eventを解決 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1で確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |
| 派遣終了日・保存満了予定日 | 管理台帳の3年保存起算日 | 同上 | t_contract.end_date, retention_due_date, legal_hold_flag | ledger metadata | metadata | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `R3Y` | `P0_FULL`,`P1_MASK` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | typed metadataはF1で確定済み。保存category・起算点はGATE-T066-RETENTION（T066 / 本番gate） |

### 3.5 stable mapping row ID manifest（F1-MAP-01正本）

公式mapping表の行順をstable IDへ固定する。IDはsourceごとに独立し、行の文言を修正しても再利用しない。新規公式項目は新IDを追加し、既存IDのresolution codeを空欄に戻さない。以下96行がF1-MAP-01の入力であり、各IDは既存column、typed F1 column、history columnのいずれかへ1件だけ解決する。

| stable row ID | source | 公式項目名（mapping行） | resolution code |
|---|---|---|---|
| FM-C-01 | SRC-C | 当事者、契約番号、契約締結日 | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-C-02 | SRC-C | 派遣先事業所の名称・所在地・電話 | WORKPLACE_ORG_SNAPSHOT |
| FM-C-03 | SRC-C | 就業場所の名称・所在地・部署・電話 | WORKPLACE_ORG_SNAPSHOT |
| FM-C-04 | SRC-C | 組織単位（名称・組織の長の職名） | WORKPLACE_ORG_SNAPSHOT |
| FM-C-05 | SRC-C | 業務内容・政令業務該当 | WORK_DESCRIPTION_TYPED |
| FM-C-06 | SRC-C | 業務に伴う責任の程度・権限の有無・内容 | RESPONSIBILITY_TYPED |
| FM-C-07 | SRC-C | 派遣期間（開始・終了） | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-C-08 | SRC-C | 就業日・休日・休暇除外 | WORK_CALENDAR_HISTORY |
| FM-C-09 | SRC-C | 指揮命令者（部署・役職・氏名） | RESPONSIBILITY_TYPED |
| FM-C-10 | SRC-C | 派遣先責任者（部署・役職・氏名・電話） | RESPONSIBILITY_TYPED |
| FM-C-11 | SRC-C | 派遣元責任者（部署・役職・氏名・電話） | RESPONSIBILITY_TYPED |
| FM-C-12 | SRC-C | 就業時間・休憩 | WORK_TIME_TYPED |
| FM-C-13 | SRC-C | 時間外・休日労働 | OVERTIME_AGREEMENT_SNAPSHOT |
| FM-C-14 | SRC-C | 事業所単位の派遣可能期間の抵触日 | LIMITATION_DUAL_TYPED |
| FM-C-15 | SRC-C | 組織単位（個人単位）の派遣可能期間の抵触日 | LIMITATION_DUAL_TYPED |
| FM-C-16 | SRC-C | 苦情申出先（派遣元） | COMPLAINT_HISTORY |
| FM-C-17 | SRC-C | 苦情申出先（派遣先） | COMPLAINT_HISTORY |
| FM-C-18 | SRC-C | 苦情処理方法・連携体制 | COMPLAINT_HISTORY |
| FM-C-19 | SRC-C | 安全・衛生 | SAFETY_TYPED |
| FM-C-20 | SRC-C | 福利厚生 | BENEFITS_TYPED |
| FM-C-21 | SRC-C | 派遣人員 | HEADCOUNT_TYPED |
| FM-C-22 | SRC-C | 協定対象派遣労働者に限定するか | AGREEMENT_FLAG_TYPED |
| FM-C-23 | SRC-C | 無期雇用/60歳以上の者に限定するか | WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT |
| FM-C-24 | SRC-C | 派遣労働者の雇用安定措置 | EMPLOYMENT_STABILITY_HISTORY |
| FM-C-25 | SRC-C | 派遣先が雇用する場合の紛争防止措置 | DIRECT_HIRE_DISPUTE_HISTORY |
| FM-C-26 | SRC-C | 期間制限を受けない業務に係る事項 | LIMITATION_EXEMPTION_TYPED |
| FM-C-27 | SRC-C | 紹介予定派遣の予定労働条件 | PLANNED_INTRODUCTION_TERMS |
| FM-E-01 | SRC-E | 宛先の派遣労働者氏名・派遣元名・住所・使用者職氏名 | WORKER_PII_SNAPSHOT |
| FM-E-02 | SRC-E | 派遣先事業所の名称・所在地・電話 | WORKPLACE_ORG_SNAPSHOT |
| FM-E-03 | SRC-E | 就業場所の名称・所在地・部署・電話 | WORKPLACE_ORG_SNAPSHOT |
| FM-E-04 | SRC-E | 組織単位 | WORKPLACE_ORG_SNAPSHOT |
| FM-E-05 | SRC-E | 業務内容・責任の程度 | RESPONSIBILITY_TYPED |
| FM-E-06 | SRC-E | 派遣期間 | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-E-07 | SRC-E | 事業所単位の抵触日 | LIMITATION_DUAL_TYPED |
| FM-E-08 | SRC-E | 個人単位の抵触日 | LIMITATION_DUAL_TYPED |
| FM-E-09 | SRC-E | 就業日・休日 | WORK_CALENDAR_HISTORY |
| FM-E-10 | SRC-E | 指揮命令者 | RESPONSIBILITY_TYPED |
| FM-E-11 | SRC-E | 派遣先責任者・派遣元責任者 | RESPONSIBILITY_TYPED |
| FM-E-12 | SRC-E | 就業時間・休憩 | WORK_TIME_TYPED |
| FM-E-13 | SRC-E | 時間外・休日労働 | OVERTIME_AGREEMENT_SNAPSHOT |
| FM-E-14 | SRC-E | 安全衛生 | SAFETY_TYPED |
| FM-E-15 | SRC-E | 福利厚生 | BENEFITS_TYPED |
| FM-E-16 | SRC-E | 苦情申出先・処理方法・連携体制 | COMPLAINT_HISTORY |
| FM-E-17 | SRC-E | 雇用安定措置 | EMPLOYMENT_STABILITY_HISTORY |
| FM-E-18 | SRC-E | 紹介予定派遣 | PLANNED_INTRODUCTION_HISTORY |
| FM-E-19 | SRC-E | 紛争防止措置 | DIRECT_HIRE_DISPUTE_HISTORY |
| FM-E-20 | SRC-E | 派遣料金 | DISPATCH_FEE_TYPED |
| FM-E-21 | SRC-E | 社会保険の加入手続きが完了していない場合の理由（⑱） | INSURANCE_TYPED |
| FM-E-22 | SRC-E | 保険/賃金/就業場所/喫煙措置（予定労働条件） | PLANNED_INTRODUCTION_TERMS |
| FM-N-01 | SRC-N | 通知日・宛先・派遣元所在地・事業所名・代表者 | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-N-02 | SRC-N | 契約締結日・契約No | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-N-03 | SRC-N | 派遣労働者の氏名 | WORKER_PII_SNAPSHOT |
| FM-N-04 | SRC-N | 性別 | WORKER_PII_SNAPSHOT |
| FM-N-05 | SRC-N | 年齢区分（45歳以上/18歳未満/その他） | WORKER_PII_SNAPSHOT |
| FM-N-06 | SRC-N | 60歳以上/60歳未満 | WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT |
| FM-N-07 | SRC-N | 健康保険の資格取得届提出有無 | INSURANCE_TYPED |
| FM-N-08 | SRC-N | 厚生年金保険の資格取得届提出有無 | INSURANCE_TYPED |
| FM-N-09 | SRC-N | 雇用保険の資格取得届提出有無 | INSURANCE_TYPED |
| FM-N-10 | SRC-N | 協定対象派遣労働者か否か | AGREEMENT_FLAG_TYPED |
| FM-N-11 | SRC-N | 派遣労働者の雇用期間 | WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT |
| FM-N-12 | SRC-N | 契約内容と明示内容の差異（派遣期間・就業日） | NOTIFICATION_DIFFERENCE_HISTORY |
| FM-N-13 | SRC-N | 契約内容と明示内容の差異（就業時間・休憩） | NOTIFICATION_DIFFERENCE_HISTORY |
| FM-N-14 | SRC-N | 契約内容と明示内容の差異（責任者） | NOTIFICATION_DIFFERENCE_HISTORY |
| FM-N-15 | SRC-N | 契約内容と明示内容の差異（時間外・休日労働） | NOTIFICATION_DIFFERENCE_HISTORY |
| FM-N-16 | SRC-N | 契約内容と明示内容の差異（その他） | NOTIFICATION_DIFFERENCE_HISTORY |
| FM-N-17 | SRC-N | 保険証等の写しの提示/送付 | DOCUMENT_DELIVERY |
| FM-L-01 | SRC-L | 派遣労働者氏名 | WORKER_PII_SNAPSHOT |
| FM-L-02 | SRC-L | 無期/有期雇用・雇用期間 | WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT |
| FM-L-03 | SRC-L | 協定対象派遣労働者か否か | AGREEMENT_FLAG_TYPED |
| FM-L-04 | SRC-L | 派遣期間 | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-L-05 | SRC-L | 60歳以上か否かの別（④） | WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT |
| FM-L-06 | SRC-L | 健康保険の提出有無・未加入理由・取得予定日 | INSURANCE_TYPED |
| FM-L-07 | SRC-L | 厚生年金保険の提出有無・未加入理由・取得予定日 | INSURANCE_TYPED |
| FM-L-08 | SRC-L | 雇用保険の提出有無・未加入理由・取得予定日 | INSURANCE_TYPED |
| FM-L-09 | SRC-L | 派遣先名称 | CONTRACT_PARTY_PERIOD_SNAPSHOT |
| FM-L-10 | SRC-L | 派遣先事業所名称・所在地・電話 | WORKPLACE_ORG_SNAPSHOT |
| FM-L-11 | SRC-L | 就業場所名称・所在地・電話 | WORKPLACE_ORG_SNAPSHOT |
| FM-L-12 | SRC-L | 組織単位 | WORKPLACE_ORG_SNAPSHOT |
| FM-L-13 | SRC-L | 業務内容・政令業務該当 | WORK_DESCRIPTION_TYPED |
| FM-L-14 | SRC-L | 業務に伴う責任の程度 | RESPONSIBILITY_TYPED |
| FM-L-15 | SRC-L | 就業日 | WORK_CALENDAR_HISTORY |
| FM-L-16 | SRC-L | 派遣先責任者 | RESPONSIBILITY_TYPED |
| FM-L-17 | SRC-L | 派遣元責任者 | RESPONSIBILITY_TYPED |
| FM-L-18 | SRC-L | 就業時間・休憩 | WORK_TIME_TYPED |
| FM-L-19 | SRC-L | 時間外・休日労働 | OVERTIME_AGREEMENT_SNAPSHOT |
| FM-L-20 | SRC-L | 月次就業状況・タイムシート | LEDGER_WORK_HISTORY |
| FM-L-21 | SRC-L | 苦情処理状況（申出日） | COMPLAINT_HISTORY |
| FM-L-22 | SRC-L | 苦情内容 | COMPLAINT_HISTORY |
| FM-L-23 | SRC-L | 苦情処理状況・結果通知 | COMPLAINT_HISTORY |
| FM-L-24 | SRC-L | 教育訓練の内容 | TRAINING_HISTORY |
| FM-L-25 | SRC-L | キャリア・コンサルティング | CAREER_HISTORY |
| FM-L-26 | SRC-L | 希望する雇用安定措置 | EMPLOYMENT_STABILITY_HISTORY |
| FM-L-27 | SRC-L | 雇用安定措置の内容 | EMPLOYMENT_STABILITY_HISTORY |
| FM-L-28 | SRC-L | 期間制限を受けない業務 | LIMITATION_EXEMPTION_TYPED |
| FM-L-29 | SRC-L | 紹介予定派遣に関する事項 | PLANNED_INTRODUCTION_HISTORY |
| FM-L-30 | SRC-L | 派遣終了日・保存満了予定日 | RETENTION_METADATA |

| resolution code | F1で確定する保存先・履歴形状 |
|---|---|
| CONTRACT_PARTY_PERIOD_SNAPSHOT | `t_contract`の契約基本値＋`t_contract_compliance_snapshot`の当事者・期間typed snapshot。法人/代表者はcurrent masterを再読しない |
| WORKPLACE_ORG_SNAPSHOT | `m_workplace`参照＋snapshotの名称・所在地・部署・組織・電話typed列。自由記述一括構造化データのみは禁止 |
| WORK_DESCRIPTION_TYPED | `work_description`、政令業務該当flag/referenceの専用列＋snapshot |
| RESPONSIBILITY_TYPED | `responsibility_level`、`responsibility_detail`と責任者contact snapshot |
| SAFETY_TYPED | 安全・衛生の責任分担、適用規程、referenceを専用列＋snapshot。法的適用可否はT066で確認 |
| WORK_TIME_TYPED | 始業・終業・休憩開始/終了を分整数で保存し、日跨ぎ・複数休憩を反復detailへ分解 |
| WORK_CALENDAR_HISTORY | 曜日・休日・休暇除外をcalendar codeとexcluded dateのtyped/history行で保存 |
| OVERTIME_AGREEMENT_SNAPSHOT | 時間外・休日上限、36協定reference、適用期間をtyped snapshot。未確認はNULL＋finding |
| LIMITATION_DUAL_TYPED | `workplace_limitation_date`と`organization_limitation_date`を別DATE列＋snapshot。旧単一`limitation_date`へ混在させない |
| COMPLAINT_HISTORY | source/client窓口typed snapshotと`t_compliance_complaint_history`の受付・内容・処理・通知append-only行 |
| BENEFITS_TYPED | `benefits_detail`、`benefits_provided_flag`を待遇方式から分離してworker snapshotへ複写 |
| HEADCOUNT_TYPED | `dispatch_headcount`とworker snapshot件数を整合検証 |
| AGREEMENT_FLAG_TYPED | `agreement_target_flag`を`treatment_scheme`から独立保持 |
| WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT | `employment_term_type/from/to`、`indefinite_worker_flag`、`age_over_60_flag`、`worker_restriction_type`をworker snapshotへ保存 |
| EMPLOYMENT_STABILITY_HISTORY | preference current＋依頼・回答・実施の`t_employment_stability_history`反復行 |
| DIRECT_HIRE_DISPUTE_HISTORY | 雇用時の紛争防止措置と手数料/申出を`t_direct_hire_dispute_history`へ条件付き保存 |
| LIMITATION_EXEMPTION_TYPED | 例外type/detail、根拠・期間を専用列＋snapshot。ruleが法的適用を自動確定しない |
| PLANNED_INTRODUCTION_HISTORY | 紹介時期・採否・非採用理由をt_planned_introduction_historyの反復行へ保存し、予定労働条件sub-fieldはt_planned_introduction_termsを参照 |
| PLANNED_INTRODUCTION_TERMS | 紹介予定派遣の契約期間・更新・業務/場所変更範囲・賃金・保険・喫煙措置・雇用主をt_planned_introduction_termsのsub-field列で保存し、単一一括構造化データに圧縮しない |
| DISPATCH_FEE_TYPED | `dispatch_fee_amount` DECIMAL、`dispatch_fee_basis`、`dispatch_fee_currency`を売上/粗利から分離 |
| INSURANCE_TYPED | SRC-E⑱単一理由とSRC-Lの健康/年金/雇用各status/reason/expected_dateを別列へ保存 |
| NOTIFICATION_DIFFERENCE_HISTORY | 派遣期間・就業日・時間/休憩・責任者・時間外等の差異typeと契約側/明示側snapshotを反復行へ保存 |
| DOCUMENT_DELIVERY | document/template/effective period/snapshot hash/recipient scope/delivery/confirmationを`t_document_delivery`へ保存 |
| WORKER_PII_SNAPSHOT | 氏名・性別・年齢区分・雇用主職氏名をworker-specific snapshotへ保存し、T063/T064のallow-listでmask |
| LEDGER_WORK_HISTORY | 月次就業状況・タイムシートを締め時点snapshotの反復行へ保存。雇用勤怠と混同しない |
| TRAINING_HISTORY | 教育訓練を実施日時・時間・内容のappend-only historyへ保存 |
| CAREER_HISTORY | キャリアconsultingを日時・内容のPII historyへ保存 |
| RETENTION_METADATA | 派遣終了日と保存満了予定日をtyped metadataへ保存。legal holdで削除を停止。保存category・起算点の確認はGATE-T066-RETENTION（T066 / 本番gate） |
## 4. F1 schema resolution（production変更なし）

4帳票96行のstable row ID（FM-C-01〜FM-L-30）を、以下の保存形状へ一意に解決する。technical shapeを未決のまま後続実装へ渡さない。法的意味・条件付き表示だけをGATE-T066へ残す。

| resolution code | F1で確定する保存先・列／履歴形状 |
|---|---|
| CONTRACT_PARTY_PERIOD_SNAPSHOT | t_contractの契約基本値＋t_contract_compliance_snapshotの当事者・期間typed列 |
| WORKPLACE_ORG_SNAPSHOT | m_workplace参照＋snapshotの名称・所在地・部署・組織・電話typed列。current masterの再読は禁止 |
| WORK_DESCRIPTION_TYPED | work_description、政令業務該当flag/referenceの専用列＋snapshot |
| RESPONSIBILITY_TYPED | responsibility_level、responsibility_detail、指揮命令者/責任者の連絡先typed列 |
| SAFETY_TYPED | 安全・衛生の責任分担、適用規程、referenceの専用列＋snapshot |
| WORK_TIME_TYPED | 始業・終業・休憩を分整数で保存し、日跨ぎflagと複数休憩の反復detail列を持つ |
| WORK_CALENDAR_HISTORY | 曜日・休日・休暇除外をcalendar codeとexcluded dateのtyped/history行で保存 |
| OVERTIME_AGREEMENT_SNAPSHOT | 時間外・休日上限、36協定reference、適用期間をtyped列で保存。未確認はNULL＋finding |
| LIMITATION_DUAL_TYPED | workplace_limitation_dateとorganization_limitation_dateを別DATE列＋snapshot |
| COMPLAINT_HISTORY | source/client窓口typed列とt_compliance_complaint_historyの受付・内容・処理・通知append-only行 |
| BENEFITS_TYPED | benefits_detail、benefits_provided_flagを待遇方式から分離してworker snapshotへ保存 |
| HEADCOUNT_TYPED | dispatch_headcountとworker snapshot件数を同一versionで整合検証 |
| AGREEMENT_FLAG_TYPED | agreement_target_flagとtreatment_schemeを独立保存 |
| WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT | employment_term_type/from/to、indefinite_worker_flag、age_over_60_flag、worker_restriction_typeをworker snapshotへ保存 |
| EMPLOYMENT_STABILITY_HISTORY | preference current＋依頼・回答・実施のt_employment_stability_history反復行 |
| DIRECT_HIRE_DISPUTE_HISTORY | 紛争防止措置と手数料/申出をt_direct_hire_dispute_historyへ条件付き保存 |
| LIMITATION_EXEMPTION_TYPED | 例外type/detail、根拠・期間を専用列＋snapshot |
| PLANNED_INTRODUCTION_HISTORY | 紹介時期・採否・非採用理由をt_planned_introduction_historyの反復行へ保存（予定労働条件sub-fieldはt_planned_introduction_termsを参照） |
| PLANNED_INTRODUCTION_TERMS | 契約期間・更新・業務/場所変更範囲・賃金・保険・喫煙措置・雇用主をt_planned_introduction_termsのsub-field列へ分解保存 |
| DISPATCH_FEE_TYPED | dispatch_fee_amount DECIMAL、dispatch_fee_basis、dispatch_fee_currencyを売上/粗利から分離 |
| INSURANCE_TYPED | SRC-E⑱の単一理由とSRC-Lの健康/年金/雇用各status/missing_reason/expected_dateを別列で保持 |
| NOTIFICATION_DIFFERENCE_HISTORY | 差異typeと契約側/明示側snapshotをt_notification_difference_historyへ反復保存 |
| DOCUMENT_DELIVERY | document/template/effective period/snapshot hash/recipient_contact_id/delivery/confirmationをt_document_deliveryへ保存 |
| WORKER_PII_SNAPSHOT | 氏名・性別・年齢区分・雇用主情報をworker-specific snapshotへ保存し、T063/T064のallow-listでmask |
| LEDGER_WORK_HISTORY | 月次就業状況・タイムシートを締め時点snapshotの反復行へ保存。雇用勤怠と混同しない |
| TRAINING_HISTORY | 教育訓練を実施日時・時間・内容のappend-only historyへ保存 |
| CAREER_HISTORY | キャリアconsultingを日時・内容のPII historyへ保存 |
| RETENTION_METADATA | 派遣終了日と保存満了予定日をtyped metadataへ保存。legal holdで削除を停止。保存category・起算点の確認はGATE-T066-RETENTION（T066 / 本番gate） |

### 4.1 Snapshot version / operation idempotency / current pointer

- t_contract_compliance_snapshotはUNIQUE(contract_id, snapshot_version)のみをversion一意制約とする。snapshot_hashは内容hashの索引であり、異なるversionに同じ内容が再登場することを許容する。
- retryの冪等性は内容hashと分離したt_compliance_snapshot_operationのoperation_idとexpected current versionで担保する。同じoperationの再送は同じresulting snapshotを返す。新しいoperationで同じ内容を保存する場合は新versionを追加する。
- t_contract_compliance_profile.current_snapshot_idとcurrent_snapshot_versionはcurrent pointerであり、snapshotへのFKを持つ。A(v1,hA)→B(v2,hB)→A(v3,hA)を許可し、v1/v2/v3をすべて保持する。
- workerはt_contract_compliance_worker_snapshotとt_contract_compliance_worker_stateを持つ。worker snapshotはUNIQUE(contract_id, worker_id, snapshot_version)、stateはcontract_id, worker_id, current_snapshot_id, current_snapshot_version, versionとFK/CASを持つ。2 workerのcurrent pointerは独立する。
- operation scopeはcontractまたはcontract+workerとし、operation_id、expected version、resulting snapshot id、request hash、statusを保存する。content hashをidempotency keyとして扱わない。

### 4.2 Immutable snapshot write protocol / purge boundary

1. current pointerをSELECT ... FOR UPDATEで取得し、expected current versionを検証する。
2. operation idempotency rowを同一transactionへINSERTし、同じoperationが既に成功していればresulting snapshotを返す。
3. next versionを予約し、typed snapshotをINSERTする。snapshot hash重複は拒否条件にしない。
4. current pointerをexpected version付きCASで切り替える。CAS/FK/一意制約失敗はoperation row、snapshot row、pointerを全rollbackし、orphanを残さない。
5. snapshot tableとworker snapshot tableはDB trigger/権限境界でUPDATE/DELETEを拒否する。通常mapperはINSERT/SELECTだけを公開する。
6. retention purgeが必要な場合だけ、legal hold確認、承認済みpurge operation id、権限分離procedure、監査event、対象versionを要求する。通常の更新・削除経路からは到達できない。

### 4.3 Mutable current explicit NULL と append-only history correction

- current profile/workplace/schedule/contact/limitation/fee/benefit/worker/insuranceのnullable列だけをFieldStrategy.ALWAYSのfull DTOで値→NULLへ更新する。省略PATCHはNULLを意味せず、validation errorとする。
- historyはNULL clear inventoryへ含めない。history eventはevent_id、event_type（CREATED/CORRECTED/CANCELLED）、supersedes_event_id、correction_reason、actor、occurred_at、effective_from/to、asOf keyを持つ。
- 訂正・取消は新event INSERTだけで表現し、旧eventはUPDATE/DELETEしない。asOf解決はeffective intervalと最新有効eventを使い、current列の明示NULLとhistory訂正を混同しない。

### 4.4 Direct regression contract

F1-MAP-01は96 stable IDすべてを専用typed columnまたは指定historyへ照合し、technical shape未解決を0件とする。F1-SNAPSHOT-01は同じoperation retry=1行、A/B/A=3version、同じcontent hashのversion重複を許容、direct mutation拒否、orphan 0を検証する。F1-SNAPSHOT-02は2 worker独立current、CAS 1勝、rollback後orphan 0を検証する。F1-NULL-01はcurrent列だけの値→NULL、省略PATCH拒否、CAS rollbackを検証する。F1-HISTORY-CORRECTION-01は旧history不変、新CORRECTED/CANCELLED event、asOf最新解決を検証する。

## 5. 決定表の適用

### 5.1 時間・asOf

`customer-product-expansion-2026/platform-invariants.md` と `design.md` §5.1/§5.2をそのまま適用する。帳票は交付日時点のsnapshotを読む。履歴行がない場合と、履歴行が存在して明示NULLの場合を `COALESCE` で混同しない。`workplace_limitation_date` と `organization_limitation_date` の未設定は「抵触日なし」ではなく、それぞれ `MISSING_WORKPLACE_LIMITATION_DATE` / `MISSING_ORGANIZATION_LIMITATION_DATE` の対象である。旧単一`limitation_date`は使用しない。期間の重なり、同日開始、未来開始、更新chain、組織単位変更、クーリングはT062/T065で具体化する。

### 5.2 主体 × 操作 × 可視母集団

`design.md` §5.3をそのまま適用する。画面、list/detail/count、CSV/Excel/PDF、download、notification、schedulerを同じscopeへ対応付ける。待遇、保険、性別、年齢、苦情詳細、キャリア内容などのsensitive fieldはfield単位でmaskし、export/PDFでmaskを解除しない。portal userは不可視、schedulerは帳票生成に必要な最小fieldだけを使う。
### 5.3 状態機械と競合

`design.md` §5.4と§7を適用する。profile確定は `version` CAS、findingは `(contract_id, code, condition_fingerprint)` のDB UNIQUE + upsertとする。V102後の帳票冪等keyにはmapping version/hash、review policy hash、gate snapshot hashを含める。mappingはL0と独立Reviewで`PROVISIONAL_REVIEWED`としてT061以降へ渡せるが、`ACTIVE`化とformal generate/deliveryはworkplace assignment、対象mapping/policy hashへの実actor approval、freeze済みdynamic policyを満たす実在external Review/CLEAN evidenceが揃わなければfail-closedとする。

## 6. 未決gate（role assignment / 後続実装・本番gate）

| gate ID | 未決事項 | owner / 承認対象 | 影響 | 状態 |
|---|---|---|---|---|
| GATE-T066-FIELD-SEMANTICS | 派遣料金の意味・表示可否、2種抵触日の法的表示条件、複数就業場所・直接雇用紛争防止・紹介予定派遣の条件解釈 | T066法務受入責任者が技術mappingを確認し、tenant画面でfreezeしたreview policyを満たす実在external Reviewを本番gateで記録する。`COMPLIANCE_RESPONSIBLE` runtime assignmentは承認actorを提供するが、法的field semanticsのownerではない | F1の保存形状は維持したまま、T062/T064/B1の算定・表示・交付をfail-closed | **OPEN（T066 / 本番gate）** |
| COMPLIANCE_RESPONSIBLE runtime role | `COMPLIANCE_RESPONSIBLE` のrole code、承認可能操作、`未確認/要確認/確認済`、監査項目、runtime指名・交代、未指名時fail-closed | 管理者がruntimeでassignmentを作成・終了する。自然人の氏名/user IDをT060の成果物へ事前固定しない | `ACTIVE`化、M PASS、本番確認済化 | **T060定義済み／M・本番assignment gate** |
| GATE-T060-2026-10 | 2026-10-01施行分の待遇差説明を求める権利の正確な文言、対象、適用境界、旧版非遡及 | `COMPLIANCE_RESPONSIBLE` roleのruntime approvalと、freeze済みpolicyを満たす実在external Review | B1 template version、2026-10交付 | **OPEN（後続・本番gate）** |
| GATE-T066-RETENTION | 個別契約書・就業条件明示書・派遣先通知書のarchive category/保存起算点。台帳R3Y以外を推測しない | T066法務受入責任者が技術mappingを確認し、管理者/法務がrole assignment経由で保持category、tax category、legal holdを確認。freeze済みpolicyを満たす実在external Reviewを本番gateで記録する | B1 retention/deletion。T066 PASSまでfail-closed | **OPEN（T066 / 本番gate）** |
| GATE-T060-COOLING | クーリング期間の日数、組織単位変更を同一実体とみなす確認基準 | `COMPLIANCE_RESPONSIBLE` roleが `m_system_config` 値と運用基準を承認 | T062/T065の抵触日算定 | **OPEN（T062/T065具体化gate）** |
| GATE-T060-EXTERNAL | tenant画面で動的設定・freezeしたrequirement group/type/minimumを満たす実在external reviewerによる照合 | 発注者がT066 M / 本番release gateとして管理。具体type/組合せ/人数をcode既定にしない | 本番法定帳票交付、S10最終PASS | **RELEASE GATE（T060起草は非block）** |
| GATE-T066-HISTORY | 月次実績、苦情処理、教育訓練、career、紹介予定、紛争防止、差異通知のwrite/asOf/correction/permission/golden | 後続history spec | 対象fieldを必要とするproduction帳票だけを禁止。S10 PASS/S12開始は阻害しない | **TRACKED P2 / PRODUCTION RELEASE GATE（未実装・未受入）** |

承認event発生時は、runtimeの実actorについて `actor_user_id`、`actor_display_name_snapshot`、`actor_role_code`、承認権限、操作、承認日時、対象commit hash、mapping version/hash、根拠資料URL/版、コメントを監査履歴へ保存する。actorは事前に書き死にさせず、管理者が指名・交代できる。法定の派遣元責任者・派遣先責任者は別の事業所/契約assignmentとして有効期間を持ち、帳票生成時に氏名・役職・連絡先をsnapshotする。

## 7. T060 L0 検証項目

- [x] 公式掲載ページ、個別契約書、就業条件明示書、派遣先通知書、派遣元管理台帳をsource registryへ登録した。
- [x] 各sourceへ版、確認日、施行期間/versionを記載した。
- [x] 4帳票を別表に分け、反復項目と条件付き項目（差異通知、期間制限例外、紹介予定派遣、苦情/教育/キャリア/雇用安定履歴）を分解した。
- [x] 事業所snapshot、組織snapshot、2種抵触日、責任の程度、福利厚生、派遣人員、協定/無期/60歳制限、苦情、雇用安定措置、保険3種の状態/理由/予定日、worker-specific snapshotをmapping行へ記載した。
- [x] permission、retention、snapshot/asOf、未決gate列を全mapping表へ付与した。
- [x] `2026-10-01`版を別versionとして保持し、4公式PDFにない待遇差説明を求める権利の通知項目をmapping表へ追加せず、一次source特定gateへ戻した。旧版へ遡及しない。
- [x] SRC-Eの「社会保険の加入手続きが完了していない場合の理由（⑱）」を独立mapping行としてDB列（canonical）・画面・出力位置へ対応付けた。
- [x] SRC-Lの「60歳以上か否かの別（④）」を独立mapping行としてDB列（canonical）・画面・出力位置へ対応付けた。
- [x] 公式4PDF（SRC-C/E/N/L）の項目番号→mapping行の照合で、今回問題となった欠落2件と根拠なしの2026-10行を除去した。
- [x] `COMPLIANCE_RESPONSIBLE` role code、承認可能操作、3状態、監査項目、runtime assignment/交代、未指名時fail-closedを定義した。
- [x] 法定の派遣元責任者・派遣先責任者を内部mapping承認roleと分離し、runtime有効期間と帳票生成時snapshotを定義した。
- [x] 特定の自然人名またはuser IDを事前固定せず、承認event時のactor ID・表示名snapshot・role・日時・mapping version/hash・根拠資料を保存する規則を定義した。
- [x] 実actor承認eventはT060の開発完了条件ではなく、`ACTIVE`化、M PASS、本番交付のgateとして分離した。未取得を虚偽に補完しない。
- [x] G2正本、spec、決定表、L0 matrix、派工対話を含む本fix deltaのL0 PASS、`SpecDispatchConsistencyTest` 8/8、`git diff --check` exit 0。form mapping 96行、SRC-E ⑱=1行、SRC-L ④=1行、根拠なし2026-10 mapping行=0行を維持した。

## 8. R19-P1-01 governance delta（96 mapping row不変）

本sectionはmapping field内容ではなくACTIVE/delivery gateのgovernanceを具体化する。§3の96 stable row、公式source URL、
source version、effective period、DB resolutionは変更しない。今後の`mapping_hash`はMarkdown blob全体ではなく、
`g2-gate-decision-delta-r19-p1-01.md` §6.2のcanonical mapping/source payloadから計算する。
review policyは別の`review_policy_hash`、delivery採用証跡は`gate_snapshot_hash`であり、3つを混同しない。

- mappingはtenant scope、assignmentはworkplace scope。contract workplaceはprofileからserver-side解決する。
- assignmentは半開区間で、approvalは有効assignment actor本人だけがappendできる。
- reviewer typeはtenant画面で動的設定し、group AND / type OR / minimum distinct reviewerで評価する。
- DRAFTでpolicyを作成し、PROVISIONAL_REVIEWED以降はtype snapshotとpolicy hashをfreezeする。
- ACTIVE化に使うapproval event IDをrequestで指定するが、tenant/workplace/assignment/actor/hashはDB再解決する。
- formal generate/deliveryはtarget workplaceのcurrent gateを毎回再評価し、past delivery downloadはcurrent gateを再評価しない。
- formal generate/deliveryは交付時にFULL/MASK/LIMITEDのimmutable document versionを同一`rendition_group_id`で保存し、
  `t_document_delivery`へcontract/profile/worker/workplace/render input snapshot ID/hashと各version/hashを記録する。
  downloadはcurrent master/configを再render入力へ使わず、保存済みrole renditionだけを返す。
- state-changing operationは`t_compliance_operation_ledger`でtenant+operation type+idempotency key/request hash/result referenceを管理し、
  response喪失再送、同key異payload、同時再送、rollback後再送を決定的に処理する。既存snapshot operationとは別契約である。
- external reviewer credentialは専用AES-GCM envelope、random IV、key version、rotation、prod key必須、復号失敗fail-closedを適用し、
  MFA/Freee/BP用鍵を流用しない。mapping sourceはDRAFTだけ編集可、freeze後はDB direct UPDATE/DELETEを拒否する。
- R10の`ACCEPTED_FOR_IMPLEMENTATION`前は本sectionを含むdocs-onlyで停止し、V102や実装/testを作成しない。
