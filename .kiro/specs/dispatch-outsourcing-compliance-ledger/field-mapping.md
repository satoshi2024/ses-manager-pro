# G2 公式様式 field mapping（T060 draft）

> **状態: PROVISIONAL MAPPING COMPLETE / L0 PASS候補**
>
> 本書は `dispatch-outsourcing-compliance-ledger` T060 の成果物であり、現時点では
> production code、DDL、migration、SecurityConfigを変更しない。項目をシステムへ対応付ける文書であり、
> 個別契約の法的適否を自動判定するものではない。`コンプライアンス責任者` は個人を固定しない
> application roleであり、runtimeで管理者が指名・交代する。role assignment、資格/根拠確認、外部専門家Reviewは
> M/本番gateで管理し、T060のprovisional mapping起草・L0検証をブロックしない。

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
| `MAPPING-2026-10` | 2026-10-01〜 | 令和8年10月改正対応版を新versionとして適用する。待遇差説明を求める権利の通知項目はこのversionにだけ紐付ける。 |

契約・交付物は `template_version`、`effective_from`、`effective_to`、`snapshot_hash` を保持して、同じsnapshotを旧versionの帳票へ再計算しない。`2026-10-01` の具体的な法的文言・適用対象は `GATE-T060-2026-10` の承認対象とし、mapping作成者が法的適否を推測して補完しない。

## 2. 既存資産・候補フィールドの凡例

- **既存**: 現行コードまたはV1 baselineに存在する列。候補名はJava propertyではなくDB column名で記載する。
- **F1候補**: `design.md` のV82 `t_contract_compliance_profile` / `m_workplace` に置く予定の項目。T060ではschemaを変更しない。
- **要追加候補**: 既存列とdesignのDDL候補だけでは1対1対応できず、F1以降で具体化が必要な項目。
- `snapshot_json` は参照マスタを含む帳票生成時の不変snapshotであり、現在の `m_customer`、`t_customer_contact`、`t_engineer`、`t_contract` を後から再読しない。

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
- `ASOF_SNAPSHOT`: 帳票は交付・再生成要求時に指定されたtemplate versionと、契約時点の `snapshot_json` を読む。現在マスタの値で過去帳票を書き換えない。
- 明示NULLは「安全」「不要」を意味しない。`limitation_date IS NULL` は未算定、保険状態NULLは未確認、`confirmed_at IS NULL` は受領未確認としてfinding/状態へ渡す。

### 2.3 コンプライアンス責任者 role と承認状態

内部のmapping承認主体は、法定帳票に記載される派遣元責任者・派遣先責任者とは別の application role とする。自然人の氏名・user IDをspec、seed、mappingへ事前固定しない。

| 項目 | T060で確定する値 |
|---|---|
| role code / 表示名 | `COMPLIANCE_RESPONSIBLE` / `コンプライアンス責任者` |
| 承認可能な操作 | mappingの閲覧、公式source/version/effective periodの確認、mapping version/hashの承認、`未確認`→`要確認`→`確認済`のstatus遷移、差戻し、承認取消し（理由必須）。法的適否の自動確定は不可。 |
| approval status | `UNCONFIRMED`（未確認）、`REVIEW_REQUIRED`（要確認）、`CONFIRMED`（確認済）。productionではassignment/資格/根拠資料が不足している場合 `CONFIRMED` と帳票交付をfail-closedする。developmentでは未指名・未確認fixtureを許容する。 |
| runtime assignment | 管理者が `role_code`、`user_id`、`valid_from`、`valid_to`、任命理由、active flagをruntimeで指名・交代する。旧assignmentを終了して新assignmentを追加し、自然人を事前固定しない。 |
| 承認event監査 | `actor_user_id`、`actor_display_name_snapshot`、`actor_role_code`、操作、before/after status、mapping version、mapping hash、根拠資料URL/版、理由、`occurred_at`、correlation IDを保存する。 |
| 未指名/失効時 | productionのmapping確認済化、法定帳票本番交付、期限運用の確定処理を拒否する。開発・テストは `UNCONFIRMED` のまま継続できる。 |

法定の派遣元責任者・派遣先責任者は、事業所/契約ごとのruntime master/assignmentであり、`valid_from`/`valid_to`を持つ別概念とする。交代時は旧行を終了し、新行の部署・役職・氏名・連絡先を帳票作成時にsnapshotする。過去帳票を上書きしない。

## 3. 帳票別 field mapping

### 3.1 労働者派遣個別契約書（SRC-C）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column候補 | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 当事者、契約番号、契約締結日 | 個別契約を特定する基本情報 | 2026-07-01〜2026-09-30／MAPPING-2026-07; 2026-10-01〜／MAPPING-2026-10 | 既存 `t_contract.contract_no`, `contract_date`, `customer_id`; 要追加候補: 派遣元法人snapshot | 契約詳細・契約編集の基本情報 | 表紙・冒頭の甲乙・契約No/日付 | `snapshot_json`へ法人名・住所・代表者を保存 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（相手先/契約識別のみ） | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 法人snapshotの正式列と保存categoryをGATE-T060-RETENTIONで確定 |
| 派遣先事業所の名称・所在地・電話 | 事業所単位の契約先を特定 | 同上 | F1候補 `workplace_id`; 要追加候補: `workplace_name_snapshot`, `workplace_address_snapshot`, `workplace_phone_snapshot` | compliance profileの就業先 | 事業所欄 | `m_workplace`現在値でなく契約時snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | `m_workplace`のsnapshot列をF1で構造化するかGATE-T060-ROLEで確認 |
| 就業場所の名称・所在地・部署・電話 | 派遣労働者が実際に就業する場所。連絡可能な内容を記載 | 同上 | 既存 `t_contract.work_location`; F1候補 `workplace_id`; 要追加候補: 部署・電話のsnapshot | 就業先profileの就業場所 | 就業場所欄 | worker-specific profileへ名称/住所/部署/電話を固定 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 複数就業場所と変更範囲の扱いを具体化 |
| 組織単位（名称・組織の長の職名） | 個人単位の期間制限の基礎。組織単位の特定が必要 | 同上 | F1候補 `organization_unit`; 要追加候補: `organization_unit_head_title_snapshot` | profileの組織単位 | 就業場所の下段 | 契約時の組織単位名・長の職名をsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 組織マスタとの結合と自由記述の許容範囲 |
| 業務内容・政令業務該当 | 役務の具体的内容、該当時は政令条項 | 同上 | 既存 `t_contract.job_description`; F1候補 `work_description` | 契約detailの業務内容 | 業務内容欄 | 交付時点snapshot。現在の案件説明で置換しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 法令該当の自動確定はせず、選択/根拠を人が確認 |
| 業務に伴う責任の程度・権限の有無・内容 | 権限なし/ありと、ありの場合の責任内容 | 同上 | 要追加候補 `responsibility_level`, `responsibility_detail` | profileの責任の程度 | 業務内容の直後 | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | F1 DDL候補に明示列がないため追加設計必須 |
| 派遣期間（開始・終了） | 契約期間。開始/終了の期間整合性を検証 | 同上 | 既存 `t_contract.start_date`, `end_date` | 契約基本情報 | 派遣期間欄 | 期間値をsnapshot。現在の更新契約で上書きしない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 期間外稼動findingと同一のtimezone/日付境界をF2で固定 |
| 就業日・休日・休暇除外 | 曜日、祝日、夏季等の除外日 | 同上 | F1候補 `holidays`; 要追加候補 `work_days_json`, `excluded_dates_json` | profileの就業日/休日 | 就業日欄 | `ASOF_SNAPSHOT`; calendarを後から再解釈しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 休日calendarのsourceと個別日例外を具体化 |
| 指揮命令者（部署・役職・氏名） | 派遣契約の指揮命令者 | 同上 | F1候補 `command_person_contact_id`; 要追加候補: person snapshot | profileの指揮命令者 | 指揮命令者欄 | contact履歴のasOf解決後に氏名等をsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 顧客contactの有効期間と履歴NULLを区別 |
| 派遣先責任者（部署・役職・氏名・電話） | 責任者の連絡先 | 同上 | F1候補 `client_responsible_person`; 要追加候補: contact snapshot | profileの派遣先責任者 | 責任者欄 | 契約時snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 個人連絡先のmask範囲を責任者が承認 |
| 派遣元責任者（部署・役職・氏名・電話） | 派遣元側の責任者 | 同上 | F1候補 `dispatch_responsible_user_id`; 要追加候補: name/phone snapshot | profileの派遣元責任者 | 責任者欄 | user current値ではなくsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 連絡先のP2_LIMITED表示を承認 |
| 就業時間・休憩 | 始業終業、休憩時刻/分数 | 同上 | F1候補 `work_time`, `break`; 要追加候補: start/end/break detail | profileの就業時間 | 就業時間欄 | snapshot。分の整数を保存する方針はplatform invariantに従う | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 日跨ぎ/複数休憩の形状をF1で具体化 |
| 時間外・休日労働 | 1日/月/年の上限、休日労働の範囲。派遣元36協定の範囲内 | 同上 | F1候補 `overtime`; 要追加候補: agreement reference snapshot | profileの時間外 | 時間外欄 | 交付時の協定設定をsnapshot。現行36協定で過去を再計算しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 法人別36協定はS11/G6側のgate。未確認を安全扱いしない |
| 事業所単位の派遣可能期間の抵触日 | 期間制限に抵触する最初の日 | 同上 | 要追加候補 `workplace_limitation_date`（現設計の単一 `limitation_date` では不足） | profileの抵触日 | 就業条件明示書/関連通知へ出力 | chain・組織変更を考慮する共通ResolverのasOf | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 事業所単位と個人単位を別列にするF1設計が必要 |
| 組織単位（個人単位）の派遣可能期間の抵触日 | 組織単位における個人単位の抵触日。無期雇用等は適用なしの場合あり | 同上 | 要追加候補 `organization_limitation_date` | profileの抵触日 | 就業条件明示書/関連通知へ出力 | 契約chainと組織単位変更を含む。NULLは未算定 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 2種制限日の優先/表示条件をGATE-T060-ROLEで確認 |
| 苦情申出先（派遣元） | 部署・役職・氏名・電話。申出を受ける者 | 同上 | F1候補 `complaint_contact`; 要追加候補: source/client separate contacts | profileの苦情窓口 | 苦情欄 | snapshot。個別苦情の処理経過は別反復履歴 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（詳細はmask） | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | source/clientを1列へ潰さない |
| 苦情申出先（派遣先） | 部署・役職・氏名・電話 | 同上 | 要追加候補 `client_complaint_contact_snapshot` | profileの苦情窓口 | 苦情欄 | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 顧客contact履歴/asOfを具体化 |
| 苦情処理方法・連携体制 | 即時連絡、責任者中心の処理、本人への結果通知 | 同上 | 要追加候補 `complaint_handling_method`, `complaint_coordination` | profileの苦情処理 | 苦情欄 | 契約条件snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 手順と実績を別モデルにする |
| 安全・衛生 | 甲乙の責任分担、適用規程 | 同上 | F1候補 `safety`; 要追加候補: rule reference snapshot | profileの安全衛生 | 安全衛生欄 | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 法的責任の自動判定をしない |
| 福利厚生 | 待遇情報以外の便宜供与を具体記載 | 同上 | 要追加候補 `benefits_detail` | profileの福利厚生 | 福利厚生欄 | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（待遇詳細はmask） | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | F1 DDL候補に欠落。待遇情報との境界を承認 |
| 派遣人員 | 何人派遣するか | 同上 | 要追加候補 `dispatch_headcount` | profileの派遣人員 | 派遣人員欄 | snapshot。複数workerの場合はworker別行と一致させる | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 現行Contract=1要員前提との整合をF1で確認 |
| 協定対象派遣労働者に限定するか | 労使協定方式/派遣先均等均衡方式の区分 | 同上 | 要追加候補 `agreement_worker_flag`, `treatment_scheme` | profileの待遇方式 | 協定対象欄 | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | `treatment_scheme`だけで2値を表せるか承認 |
| 無期雇用/60歳以上の者に限定するか | 3区分（無期限定、60歳以上限定、限定しない） | 同上 | 要追加候補 `worker_restriction_type` | profileのworker制限 | 制限欄 | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 無期・年齢の判定を法的適否と混同しない |
| 派遣労働者の雇用安定措置 | 解除前申入れ、就業機会確保、損害賠償等、理由明示 | 同上 | 要追加候補 `employment_stability_measures` | profileの雇用安定措置 | 雇用安定措置欄 | 条件と実施結果を別snapshot/履歴 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 実施履歴を管理台帳の反復グループへ接続 |
| 派遣先が雇用する場合の紛争防止措置 | 紹介可能/不可に応じた手数料または申出方法 | 条件付き（派遣元が職業紹介を行える場合） | 要追加候補 `direct_hire_dispute_prevention` | profileの紹介/紛争防止 | 紛争防止措置欄 | 条件成立時だけsnapshotへ含める | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 職業紹介可否と手数料表のsourceを承認 |
| 期間制限を受けない業務に係る事項 | 有期プロジェクト、育休/介護休業代替、日数限定等 | 条件付き | 要追加候補 `limitation_exemption_type`, `limitation_exemption_detail` | profileの期間制限例外 | 備考欄 | 例外根拠と期間をsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 例外の適用をruleが自動確定しない |
| 紹介予定派遣の予定労働条件 | 契約期間、更新、更新上限、業務/場所の変更範囲、試用期間、賃金、保険、喫煙措置、雇用主等 | 紹介予定派遣の場合のみ | 要追加候補 `planned_introduction_terms_json` | profileの条件付きセクション | 別紙 | 条件成立時のworker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 項目をJSON一括でなくsub-fieldへ分解するかF1で決定 |

### 3.2 就業条件明示書（SRC-E）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column候補 | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 宛先の派遣労働者氏名・派遣元名・住所・使用者職氏名 | worker-specific明示書の宛先と使用者 | 同上 | 既存 `t_engineer.full_name`; 要追加候補: worker/employer snapshot | profileのworker選択・派遣元情報 | 冒頭 | 作成時のworker/employer snapshotを固定 | `ARCHIVE_PENDING` | `P0_FULL`,`P3_SELF`; `P1_MASK`,`P2_LIMITED`は原則不可 | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 要員本人公開経路はS14境界で再確認 |
| 派遣先事業所の名称・所在地・電話 | 就業先の特定 | 同上 | F1候補 `workplace_id`; snapshot fields要追加候補 | 就業先profile | ①就業先 | 事業所snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 就業場所の名称・所在地・部署・電話 | 実就業場所の明示 | 同上 | 既存 `t_contract.work_location`; F1候補 `workplace_id`; 要追加候補: department/phone snapshot | 就業場所 | ①就業場所 | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 複数場所/変更範囲をF1で分解 |
| 組織単位 | 個人単位抵触日の算定単位 | 同上 | F1候補 `organization_unit` | 組織単位 | 就業場所欄 | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 業務内容・責任の程度 | 業務と権限の明示 | 同上 | 既存 `job_description`; 要追加候補 `responsibility_level/detail` | 業務/責任 | ②③ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣期間 | 派遣開始/終了 | 同上 | 既存 `start_date`, `end_date` | 契約基本情報 | ⑥/⑫/⑬ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 事業所単位の抵触日 | 事業所単位の期間制限の最初の日。延長の影響あり | 同上 | 要追加候補 `workplace_limitation_date` | 抵触日欄 | 期間欄 | 共通Resolverの算定結果をsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 2種制限日分離を承認 |
| 個人単位の抵触日 | 組織単位の期間制限の最初の日。無期雇用等は適用なしの場合あり | 同上 | 要追加候補 `organization_limitation_date` | 抵触日欄 | 期間欄 | chain/asOfをsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | NULL=未算定を固定 |
| 就業日・休日 | 曜日、祝日、休暇除外 | 同上 | 要追加候補 `work_days_json`, `excluded_dates_json` | 就業日/休日 | ⑥ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 指揮命令者 | 部署・役職・氏名 | 同上 | F1候補 `command_person_contact_id`; person snapshot要追加候補 | 指揮命令者 | ⑤ | contact asOf後snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣先責任者・派遣元責任者 | 部署・役職・氏名・電話 | 同上 | F1候補 `client_responsible_person`, `dispatch_responsible_user_id`; snapshot要追加候補 | 責任者 | ⑭ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 就業時間・休憩 | 始業終業、休憩時刻/分数 | 同上 | F1候補 `work_time`, `break` | 就業時間 | ⑦ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 複数休憩/日跨ぎをF1で決定 |
| 時間外・休日労働 | 1日/月/年と休日労働。36協定範囲内 | 同上 | F1候補 `overtime` | 時間外 | ⑮ | 協定設定snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | S11/G6の法人別協定確認 |
| 安全衛生 | 派遣先責任の明示 | 同上 | F1候補 `safety` | 安全衛生 | ⑧ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 福利厚生 | 制服、施設利用等の具体的便宜 | 同上 | 要追加候補 `benefits_detail` | 福利厚生 | ⑯ | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | F1追加候補 |
| 苦情申出先・処理方法・連携体制 | 派遣元/先の窓口と相互連携 | 同上 | 要追加候補 `complaint_source_contact`, `complaint_client_contact`, `complaint_handling_method` | 苦情窓口 | ⑨ | 条件snapshot、実績は反復履歴 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF`（詳細mask） | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 反復履歴設計 |
| 雇用安定措置 | 契約解除時の新たな就業機会/休業/解雇予告等 | 同上 | 要追加候補 `employment_stability_measures` | 雇用安定措置 | ⑩ | 条件・実施履歴を分離 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | — |
| 紹介予定派遣 | 予定労働条件を条件付き明示 | 紹介予定派遣の場合のみ | 要追加候補 `planned_introduction_terms` | 条件付きセクション | ⑪別紙 | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | sub-field分解とS14公開境界 |
| 紛争防止措置 | 派遣先雇用時の申出/手数料等 | 条件付き | 要追加候補 `direct_hire_dispute_prevention` | 紛争防止措置 | ⑰ | 条件成立時のみsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 職業紹介可否/手数料表のowner確認 |
| 派遣料金 | 月額/日額/時間額 | 同上 | 既存 `t_contract.selling_price`（意味が一致するか要確認）; 要追加候補 `dispatch_fee` | 契約金額 | ⑳ | 契約時の金額snapshot。円・BigDecimal | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 売上単価と法定帳票の派遣料金を同一視するかGATE-T060-ROLE |
| 保険/賃金/就業場所/喫煙措置（予定労働条件） | 紹介予定派遣の場合の条件付き項目 | 紹介予定派遣の場合のみ | 要追加候補 `planned_introduction_terms` の sub-field | 条件付きセクション | 別紙 | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`,`P3_SELF` | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | 法定明示と予定条件を別versionで保持 |
| 2026-10-01待遇差説明を求める権利の通知 | 令和8年10月改正対応版で追加・変更される通知項目 | `MAPPING-2026-10`のみ／2026-10-01〜 | 要追加候補 `treatment_difference_explanation_right_notice` | profile/交付設定のversion別項目 | 明示書の改正対応箇所 | `MAPPING-2026-07`へ遡及せず、改正versionでのみsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`（待遇詳細mask） | SRC-E／令和8年7月版・10月改正対応／2026-08-09 | **GATE-T060-2026-10**: exact wording/対象/適用日をroleが承認 |

### 3.3 派遣先通知書（SRC-N）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column候補 | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 通知日・宛先・派遣元所在地・事業所名・代表者 | 通知の発行主体と相手先 | 同上 | 要追加候補: `delivery_date`, sender/recipient snapshot | 交付画面 | 冒頭 | delivery時点の法人/recipient snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | `t_document_delivery`との責任分界 |
| 契約締結日・契約No | 対象契約の参照 | 同上 | 既存 `contract_date`, `contract_no` | 契約detail | 冒頭 | profile snapshotの契約識別子 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣労働者の氏名 | worker-specific通知 | 同上 | 既存 `t_engineer.full_name`; 要追加候補 `worker_snapshot_json` | worker選択 | ① | 通知作成時のworker snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`（営業は原則非表示） | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | PIIをfield単位でmaskする境界 |
| 性別 | 記載例の区分項目 | 同上 | 既存 `t_engineer.gender`; 要追加候補 `worker_snapshot_json` | worker profile（権限者のみ） | ① | worker-specific snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 年齢区分（45歳以上/18歳未満/その他） | 45歳以上はその旨、18歳未満は具体年齢。その他はチェック漏れ防止の補助 | 同上 | 既存 `t_engineer.birth_date`; 要追加候補 `age_band_snapshot`, `age_exact_snapshot`（18歳未満条件） | worker profile/通知プレビュー | ① | 基準日をsnapshotへ保存し現在年齢で再計算しない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 年齢計算基準日とPII保持を承認 |
| 60歳以上/60歳未満 | 60歳以上か否か | 同上 | 既存 `birth_date`; 要追加候補 `over60_flag_snapshot` | worker profile/通知プレビュー | ④ | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 健康保険の資格取得届提出有無 | 有/無。無の場合は具体的理由または手続状況 | 同上 | 要追加候補 `health_insurance_status`, `health_insurance_reason`, `health_insurance_expected_date` | insurance section | ⑤ | status/reason/予定日を別fieldでsnapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 保険ごとに同じ3属性を持つF1設計 |
| 厚生年金保険の資格取得届提出有無 | 有/無。無の場合は具体的理由または手続状況 | 同上 | 要追加候補 `pension_insurance_status`, `pension_insurance_reason`, `pension_insurance_expected_date` | insurance section | ⑤ | 同上 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 雇用保険の資格取得届提出有無 | 有/無。無の場合は具体的理由または手続状況 | 同上 | 要追加候補 `employment_insurance_status`, `employment_insurance_reason`, `employment_insurance_expected_date` | insurance section | ⑤ | 同上 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は非表示 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 協定対象派遣労働者か否か | 協定対象/非対象の区分 | 同上 | 要追加候補 `agreement_worker_flag` / F1 `treatment_scheme` | profile | ② | snapshot | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣労働者の雇用期間 | 無期/有期、具体期間 | 同上 | 既存 `t_engineer.employment_type` は雇用形態であり期間ではない; 要追加候補 `employment_term_type/from/to` | worker-contract section | ③ | worker-specific snapshot。派遣期間と別値 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`は限定 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | 雇用期間の正sourceをF1で定義 |
| 契約内容と明示内容の差異（派遣期間・就業日） | 差異がある場合のみ記載 | 差異発生時 | 要追加候補 `notification_difference_json` | 差異入力/確認 | ⑥ | 差異発生時の両方のsnapshotを保持 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | JSON一括か差異type別行かF1で決定 |
| 契約内容と明示内容の差異（就業時間・休憩） | 差異がある場合のみ記載 | 差異発生時 | 同上 | 差異入力/確認 | ⑥ | 同上 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 契約内容と明示内容の差異（責任者） | 派遣元/先責任者に差異がある場合 | 差異発生時 | 同上 | 差異入力/確認 | ⑥ | 同上 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 契約内容と明示内容の差異（時間外・休日労働） | 差異がある場合のみ記載 | 差異発生時 | 同上 | 差異入力/確認 | ⑥ | 同上 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 契約内容と明示内容の差異（その他） | 上記以外の差異 | 差異発生時 | 同上 | 差異入力/確認 | ⑥ | 同上 | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | — |
| 保険証等の写しの提示/送付 | 派遣元から派遣先への提示/送付。未知file/scan障害はfail-closed | 同上 | 要追加候補: archive `document_id`、delivery record | document delivery | 添付/交付記録 | 原本はarchive、downloadはfile scope + field permission | `ARCHIVE_PENDING` | `P0_FULL`; `P1_MASK`,`P2_LIMITED`不可 | SRC-N／令和8年7月版・10月改正対応／2026-08-09 | file category、scan、recipient scopeをB1で具体化 |

### 3.4 派遣元管理台帳（SRC-L）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column候補 | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 派遣労働者氏名 | 個人別台帳の主キー相当 | 同上 | 既存 `engineer_id`; 要追加候補 worker snapshot | ledger detail | ① | 契約/worker snapshot。氏名変更後も過去台帳は不変 | `R3Y` | `P0_FULL`,`P1_MASK`（mask可）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 個人別台帳の営業可視性をGATE-T060-ROLEで確認 |
| 無期/有期雇用・雇用期間 | 派遣期間と異なるため別記載 | 同上 | 要追加候補 `employment_term_type/from/to` | worker/ledger | ③ | worker-specific snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 協定対象派遣労働者か否か | 労使協定方式/均等均衡方式 | 同上 | F1候補 `treatment_scheme`; 要追加候補 flag | ledger profile | ② | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣期間 | 雇用期間と分けて記録 | 同上 | 既存 `start_date`,`end_date` | contract/ledger | ④ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 健康保険の提出有無・未加入理由・取得予定日 | 保険ごとに有/無、無の理由/手続状況 | 同上 | 要追加候補 `health_insurance_status/reason/expected_date` | insurance section | ⑰ | worker-specific snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | F1で3保険を独立field化 |
| 厚生年金保険の提出有無・未加入理由・取得予定日 | 同上 | 同上 | 要追加候補 `pension_insurance_status/reason/expected_date` | insurance section | ⑰ | 同上 | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 雇用保険の提出有無・未加入理由・取得予定日 | 同上 | 同上 | 要追加候補 `employment_insurance_status/reason/expected_date` | insurance section | ⑰ | 同上 | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣先名称 | 契約先の特定 | 同上 | 既存 `customer_id`; `m_customer.company_name` snapshot | ledger header | ⑤ | customer master変更から分離 | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣先事業所名称・所在地・電話 | 事業所を特定 | 同上 | F1 `workplace_id`; snapshot fields要追加候補 | workplace section | ⑥⑦ | `m_workplace`の契約時snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 就業場所名称・所在地・電話 | 実就業場所 | 同上 | `workplace_id`; location snapshot要追加候補 | workplace section | ⑦ | worker-specific snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 組織単位 | 個人単位期間制限の単位 | 同上 | F1 `organization_unit`; head title要追加候補 | workplace section | ⑦ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 業務内容・政令業務該当 | 業務の具体化 | 同上 | `job_description`/F1 `work_description` | ledger | ⑩ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 業務に伴う責任の程度 | 権限なし/あり、内容 | 同上 | 要追加候補 `responsibility_level/detail` | ledger | ⑪ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | F1欠落候補 |
| 就業日 | 実績との比較対象 | 同上 | F1 `work_days_json` | ledger | ⑧ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣先責任者 | 部署・役職・氏名・電話 | 同上 | F1 client responsible + snapshot | ledger | ⑭ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣元責任者 | 部署・役職・氏名・電話 | 同上 | F1 dispatch responsible + snapshot | ledger | ⑭ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 就業時間・休憩 | 始業/終業/休憩 | 同上 | F1 `work_time`,`break` | ledger | ⑨ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 時間外・休日労働 | 36協定の範囲内 | 同上 | F1 `overtime` | ledger | ⑮ | 協定設定snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 法人別協定確認 |
| 月次就業状況・タイムシート | 別添タイムシート。既存客先工数を流用するが雇用勤怠とは分離 | 同上 | 既存 `t_work_record`/`t_work_record_daily`; 要追加候補 `ledger_work_snapshot` | ledger monthly tab | 別添・月次反復 | 月次締めsnapshot。現在工数で過去台帳を書き換えない | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | S11/G6の雇用勤怠sourceとの比較表示を具体化 |
| 苦情処理状況（申出日） | 苦情申出を受けた日 | 反復履歴 | 要追加候補 `complaint_history.received_at` | findings/ledger complaint tab | ⑫反復行 | 受付時点の履歴をappend-only。ack/resolutionと混同しない | `R3Y` | `P0_FULL`,`P1_MASK`（詳細mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | `t_compliance_finding`だけでは履歴を表せない |
| 苦情内容 | 申出内容 | 反復履歴 | 要追加候補 `complaint_history.content` | complaint tab | ⑫反復行 | PII/自由記述を必要最小限 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 機微情報のmask/AI送信禁止 |
| 苦情処理状況・結果通知 | 処理内容、本人通知 | 反復履歴 | 要追加候補 `complaint_history.resolution_note`, `notified_at` | complaint tab | ⑫反復行 | append-only履歴 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 教育訓練の内容 | 日時、時間、研修内容 | 反復履歴 | F1 `training`; 要追加候補 `training_history` | training tab | ⑱反復行 | 実施時点snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| キャリア・コンサルティング | 日時・内容 | 反復履歴 | 要追加候補 `career_consulting_history` | career tab | ⑲反復行 | 内容は個人情報。過去行を更新しない | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | 個人情報の可視性をowner確認 |
| 希望する雇用安定措置 | 労働者の希望内容 | 条件/反復 | 要追加候補 `employment_stability_preference` | stability tab | ⑳ | worker-specific snapshot | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 雇用安定措置の内容 | 依頼日時/方法、回答日時/内容、他派遣先紹介等 | 反復履歴 | 要追加候補 `employment_stability_history` | stability tab | ㉑反復行 | append-only、回答後の変更は訂正履歴 | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 期間制限を受けない業務 | 例外業務の場合のみ | 条件付き | 要追加候補 `limitation_exemption_type/detail` | other | ⑯ | snapshot | `R3Y` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 紹介予定派遣に関する事項 | 該当時の紹介時期/内容、採否、非採用理由 | 条件付き・反復 | 要追加候補 `planned_introduction_history` | introduction tab | ⑬反復行 | worker-specific snapshot + outcome history | `R3Y` | `P0_FULL`,`P1_MASK`（mask）, `P2_LIMITED`不可 | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | — |
| 派遣終了日・保存満了予定日 | 管理台帳の3年保存起算日 | 同上 | 既存 `t_contract.end_date`; 要追加候補 `retention_due_date` | ledger metadata | metadata | `end_date`確定後 `+3年`。legal holdで停止 | `R3Y` | `P0_FULL`,`P1_MASK` | SRC-L／令和8年7月版・10月改正対応／2026-08-09 | retention dueのtimezone/hold連携 |

## 4. F1 DDL候補との突合（production変更なし）

`design.md` の候補は、就業先、契約形態詳細、業務内容、就業時間/休憩/休日/時間外、指揮命令者、派遣先/元責任者、苦情窓口、待遇方式、抵触日、教育訓練、安全衛生、保険、指示経路、再委託可否、検収方法、snapshot、versionを示している。公式4帳票との突合で、次をF1の欠落候補として明示する。

| 区分 | 欠落候補／確認事項 | 影響 | T060での扱い |
|---|---|---|---|
| 就業先snapshot | `m_workplace`の名称/住所/組織単位/電話をprofileへ固定するstructured snapshotまたはsnapshot JSON | マスタ変更で過去帳票が変わる | `workplace_id`だけでは不十分としてF1候補化 |
| 2種の制限日 | `workplace_limitation_date` と `organization_limitation_date` を分離 | 事業所単位と個人単位を混同すると期限通知が誤る | 現設計の単一 `limitation_date` は不十分と記録 |
| 責任の程度 | `responsibility_level` / `responsibility_detail` | 派遣契約・台帳・明示書の必須項目を表せない | 要追加候補 |
| 福利厚生 | `benefits_detail` | 待遇情報以外の便宜供与を表せない | 要追加候補 |
| 派遣人員 | `dispatch_headcount` とworker別snapshotの整合 | 複数人契約と個別通知が不整合になる | 要追加候補 |
| 協定/無期/60歳 | `agreement_worker_flag`、`worker_restriction_type` | 方式/制限の区分が失われる | `treatment_scheme`だけで足りるか要承認 |
| 苦情窓口・処理経過 | source/client窓口、苦情内容、処理/結果通知の反復history | profileの単一 `complaint_contact` だけでは台帳を再生成できない | `complaint_history`要追加候補 |
| 雇用安定措置 | 条件、希望、実施、回答の反復history | 契約条件と実績が混同される | `employment_stability_history`要追加候補 |
| 保険 | 健康/厚生年金/雇用の各 `status/reason/expected_date` | 無の場合の具体的理由/手続状況を失う | 3保険を独立fieldで要求 |
| worker-specific snapshot | worker名、性別、年齢区分、雇用期間、保険、明示差異 | 一つのcontract profileへ複数workerを詰めるとPIIと出力が混ざる | worker単位のsnapshot識別子要追加候補 |
| 月次就業実績 | 月次締め時の客先工数snapshotと雇用勤怠差異 | 現在値で過去台帳が変わる | S11/G6とのsource matrixを後続で固定 |
| 教育/キャリア | 研修・consultingの反復行 | 管理台帳の履歴性を満たせない | history table/JSONの選択をF1で決定 |
| 紹介予定派遣 | 予定労働条件、紹介時期、採否、理由 | 条件付き項目を常時表示/保存すると誤交付 | 条件付きsub-field/履歴を要求 |
| 派遣料金 | `selling_price` と法定帳票の派遣料金の意味差 | 金額口径を誤る | `dispatch_fee`の独立要否をowner確認 |
| 交付version | document type/version/effective period/snapshot hash | 2026-10-01版を旧版へ遡及する | B1のidempotency契約へ引継ぎ |
| 交付/受領 | `t_document_delivery`に交付日だけでなくversion・snapshot・recipient scopeが必要 | 同一snapshot再生成/受領未確認を区別できない | F1/B1の追加候補 |

## 5. 決定表の適用

### 5.1 時間・asOf

`customer-product-expansion-2026/platform-invariants.md` と `design.md` §5.1/§5.2をそのまま適用する。帳票は交付日時点のsnapshotを読む。履歴行がない場合と、履歴行が存在して明示NULLの場合を `COALESCE` で混同しない。`limitation_date` の未設定は「抵触日なし」ではなく `MISSING_LIMITATION_DATE` の候補である。期間の重なり、同日開始、未来開始、更新chain、組織単位変更、クーリングはT062/T065で具体化する。

### 5.2 主体 × 操作 × 可視母集団

`design.md` §5.3をそのまま適用する。画面、list/detail/count、CSV/Excel/PDF、download、notification、schedulerを同じscopeへ対応付ける。待遇、保険、性別、年齢、苦情詳細、キャリア内容などのsensitive fieldはfield単位でmaskし、export/PDFでmaskを解除しない。portal userは不可視、schedulerは帳票生成に必要な最小fieldだけを使う。

### 5.3 状態機械と競合

`design.md` §5.4をそのまま適用する。profile確定は `version` CAS、findingは `(contract_id, code, condition_fingerprint)` のDB UNIQUE + upsert、帳票再生成は `(contract_id, document_type, template_version, snapshot_hash)` の業務一意キーとする。mapping自体の承認状態は `COMPLIANCE_RESPONSIBLE` roleのruntime assignmentと監査eventで管理し、開発baselineをT061以降へ渡す場合も、本番の確認済化・法定帳票交付は必要なassignment/資格/根拠が揃わなければfail-closedとする。

## 6. 未決gate（role assignment / 後続実装・本番gate）

| gate ID | 未決事項 | owner / 承認対象 | 影響 | 状態 |
|---|---|---|---|---|
| GATE-T060-ROLE | `COMPLIANCE_RESPONSIBLE` のrole code、承認可能操作、`未確認/要確認/確認済`、監査項目、runtime指名・交代、未指名時fail-closed | 管理者がruntimeでassignmentを作成・終了する。自然人の氏名/user IDをT060の成果物へ事前固定しない | T061以降の認可・承認event・本番確認済化 | **T060定義済み／M・本番assignment gate** |
| GATE-T060-2026-10 | 2026-10-01施行分の待遇差説明を求める権利の正確な文言、対象、適用境界、旧版非遡及 | `COMPLIANCE_RESPONSIBLE` roleのruntime approval。外部社労士/弁護士照合はT066/本番gate | B1 template version、2026-10交付 | **OPEN（後続・本番gate）** |
| GATE-T060-RETENTION | 個別契約書・就業条件明示書・派遣先通知書のarchive category/保存起算点。台帳R3Y以外を推測しない | 管理者/法務がrole assignment経由で保持category、tax category、legal holdを確認 | B1 retention/deletion | **OPEN（T061/B1具体化gate）** |
| GATE-T060-COOLING | クーリング期間の日数、組織単位変更を同一実体とみなす確認基準 | `COMPLIANCE_RESPONSIBLE` roleが `m_system_config` 値と運用基準を承認 | T062/T065の抵触日算定 | **OPEN（T062/T065具体化gate）** |
| GATE-T060-EXTERNAL | 外部社労士/弁護士による照合 | 発注者がT066 M / 本番release gateとして管理 | 本番法定帳票交付、S10最終PASS | **RELEASE GATE（T060起草は非block）** |

承認event発生時は、runtimeの実actorについて `actor_user_id`、`actor_display_name_snapshot`、`actor_role_code`、承認権限、操作、承認日時、対象commit hash、mapping version/hash、根拠資料URL/版、コメントを監査履歴へ保存する。actorは事前に書き死にさせず、管理者が指名・交代できる。法定の派遣元責任者・派遣先責任者は別の事業所/契約assignmentとして有効期間を持ち、帳票生成時に氏名・役職・連絡先をsnapshotする。

## 7. T060 L0 検証項目

- [x] 公式掲載ページ、個別契約書、就業条件明示書、派遣先通知書、派遣元管理台帳をsource registryへ登録した。
- [x] 各sourceへ版、確認日、施行期間/versionを記載した。
- [x] 4帳票を別表に分け、反復項目と条件付き項目（差異通知、期間制限例外、紹介予定派遣、苦情/教育/キャリア/雇用安定履歴）を分解した。
- [x] 事業所snapshot、組織snapshot、2種抵触日、責任の程度、福利厚生、派遣人員、協定/無期/60歳制限、苦情、雇用安定措置、保険3種の状態/理由/予定日、worker-specific snapshotをmappingまたは欠落候補へ記載した。
- [x] permission、retention、snapshot/asOf、未決gate列を全mapping表へ付与した。
- [x] `2026-10-01`版を別versionとし、旧版へ待遇差説明を求める権利の通知項目を遡及しない方針を明記した。
- [x] `COMPLIANCE_RESPONSIBLE` role code、承認可能操作、3状態、監査項目、runtime assignment/交代、未指名時fail-closedを定義した。
- [x] 法定の派遣元責任者・派遣先責任者を内部mapping承認roleと分離し、runtime有効期間と帳票生成時snapshotを定義した。
- [x] 特定の自然人名またはuser IDを事前固定せず、承認event時のactor ID・表示名snapshot・role・日時・mapping version/hash・根拠資料を保存する規則を定義した。
- [x] `git diff --check` はreview-ledger更新後に実行する。
