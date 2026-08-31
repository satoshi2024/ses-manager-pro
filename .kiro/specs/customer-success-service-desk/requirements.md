# Requirements — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

## 前提・背景

親: `.kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md` (NF-02)
要件基線: `.kiro/roadmap/2026-08-27-post-acceptance-requirements-design.md` §3 および CR-01〜CR-06
決定台帳: `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md`（DG-02）
不変条件: `.kiro/specs/customer-product-expansion-2026/platform-invariants.md`
現行境界: `inventory.md`

契約更新カレンダー（FR-06）は終了期限の俯瞰とエスカレーションを既に持つ。本機能は日常の問い合わせ・SLA・CSAT・QBR・説明可能な顧客ヘルスを蓄積し、更新判断の**材料**だけをカレンダーへ載せる。ヘルスやSLAは契約更新ステータスを自動変更しない。

**承認状態**: 2026-08-27 時点で traceability は CANDIDATE/DISCOVERY。Owner・Approved scope・DG-02 は未APPROVED。本requirementsは提案であり、production変更の許可ではない。

再利用する正本: Customer/Contact、Contract/RenewalCalendar、portal security chain/DTO、`WorkCalendarDay`（法人既定のみ）、`DocumentService`、`NotificationService`。
所有するもの: service request / comment / SLA clock / CSAT / QBR / health snapshot。
所有しないもの: 新Customer master、新portal認証、法的自動判定、第二通知/outbox。

---

## CS-R1 問い合わせ・課題

1. THE 内部利用者（管理者、営業、マネージャー）SHALL 顧客に紐づく問い合わせを起票できる。契約・案件・要員・contactは任意とするが、指定した場合は同一顧客に属することを検証し、属さなければ拒否する。
2. THE 顧客portal利用者（orgType=`CUSTOMER`、有効、permission `service-desk.create`）SHALL 自組織 `customer_id` の問い合わせだけを起票できる。BP portal利用者は起票・閲覧できない。
3. THE request SHALL 一意番号（`REQ-YYYYMM-XXXX`）、顧客ID、任意の契約/案件/要員/contact、カテゴリ（`CONTRACT`/`BILLING`/`ATTENDANCE`/`QUALITY`/`SYSTEM`/`OTHER`）、優先度（P0〜P3）、経路（`PORTAL`/`EMAIL`/`PHONE`/`MEETING`/`INTERNAL`）、件名、本文、内部担当 `owner_user_id`、status、SLA round を持つ。
4. THE status SHALL `RECEIVED → IN_PROGRESS → WAITING_CUSTOMER → RESOLVED → CLOSED` を基本とする。`RESOLVED`/`CLOSED` からの再openは新SLA roundを作り、以前のSLA結果を上書きしない。永続statusに曖昧な `REOPENED` を残さず、遷移コマンド `REOPENED` は新round作成後 `IN_PROGRESS` へ進める。
5. THE comment SHALL `INTERNAL` と `PORTAL_VISIBLE` をDB列で分離する。portalの読取SQLは `PORTAL_VISIBLE` のみとし、DTOクラスにINTERNAL本文・内部user ID・原価を持たない。CSSやフロント非表示だけで隠してはならない。
6. THE 既存請求メモ `t_invoice.portal_inquiry` SHALL 本機能のrequestへ自動変換しない。両方を并存する。

---

## CS-R2 SLA

1. THE SLA policy SHALL 優先度別にversionを持ち、初回応答目標時間と解決目標時間、営業開始/終了時刻を保持する。requestの各roundは適用した `policy_id` をsnapshotする。
2. THE deadline SHALL tenant timezoneの業務日時で計算する。休日は法人既定 `m_work_calendar_day`（engineer/organization未指定）を用い、未設定時は土日除外と missing calendar を記録する。単純な24時間加算、要員個人カレンダー、派遣コンプライアンス暦は使わない。
3. WHEN statusが `WAITING_CUSTOMER` になる場合、THE システム SHALL SLA clockを PAUSED にし、pause理由と開始時刻を保持する。再開時は**営業分数**だけ期限を延長する。完了済みroundのbreachフラグと確定時刻は変更しない。
4. THE scheduler SHALL ShedLockで多重起動を1実行に畳み、期限前warning・breach・継続breachを `NotificationService.publishToUser` で通知する。dedupe keyは request×round×種別で一意とし、同一事象の再実行で通知を増やさない。
5. THE 通知link SHALL `NotificationLinks` に定数登録し、遷移時に内部scopeまたはportal membershipを再認可する。

---

## CS-R3 CSAT / QBR

1. WHEN requestが `RESOLVED` または `CLOSED` の場合、THE 当該顧客のportal user SHALL 5段階スコアと任意コメントを **1回だけ** 投稿できる。認可はportal session＋自社request membershipとする。匿名URLは使わない。
2. THE 二重投稿 SHALL DB `UNIQUE(service_request_id)` とCASで拒否し、409を返す。
3. THE 営業/マネージャー SHALL 顧客ごとの定例会/QBR（日時、参加者、議題、討議、決定、次回日）と action（owner、due、status）を保存できる。actionは検索と期限通知の対象になる。
4. THE QBR SHALL portalへ公開しない（内部記録）。

---

## CS-R4 Health

1. THE health SHALL 未解決P0件数、未解決P1件数、直近30日SLA違反件数、平均CSAT、AR延滞（既存Invoice overdueの読取）、最終QBR日を型付きfactorとして計算する。合計0–100と `HEALTHY`(≥80)/`WARNING`(50–79)/`CRITICAL`(≤49) を返す。
2. THE UI SHALL 合計だけでなく factor、対象期間、算定時刻、missing input を表示する。欠損を「普通点」で埋めない。
3. THE health SHALL `t_contract.renewal_decision` および更新ドラフトを自動作成・自動変更しない。
4. THE 日次snapshot SHALL customer×日付で一意とし、同一日の再計算で過去snapshotを黙ってdeleteしない（訂正は理由付き）。

---

## CS-R5 Portal scope / 添付 / export

1. THE 顧客Aのportal session SHALL 顧客Bのrequest ID、comment、CSAT、count、export、添付download、通知link先を取得できず、一貫した404（または存在を漏らさない拒否）を返す。
2. THE 添付 SHALL `DocumentService` に登録し、`FileReferenceProvider` と `FileScopeValidationService` の両方に `SERVICE_REQUEST` を登録する。未scan・未知fileはfail-closed。INTERNAL添付はportal download APIから構造的に除外する。
3. THE 内部list/detail/count/export/download/notification/scheduler SHALL 同じ顧客scope resolverを使う。営業はDataScope、マネージャーは組織∩DataScope、管理者は全件、HR/要員は不可。
4. THE portal DTO SHALL field-inventoryへ C-9（問い合わせthread）としてallow-listを追加した項目だけを含み、内部entityをserializeしない。

---

## CS-R6 非機能（CR-01〜CR-06の適用）

1. THE 更新API SHALL CSRF、menu/action permission、domain scopeを全て通過した場合だけ実行する。
2. THE 状態変更 SHALL `version` CASまたは状態条件付きUPDATEで防重し、失敗は409とする。
3. THE 外部I/O（通知outbox、file scan）SHALL DB transaction外で実行する。
4. THE 文言 SHALL 4 bundle（`messages`/`_en`/`_zh_CN`/`_ko`）へ同一keyを追加し、日本語を正として翻訳する。`messages_ja` は作らない。
5. THE UI SHALL desktopと390pxで起票・返信・CSAT・内部メモ切替を完了できる。二重click抑止はserver冪等の代替にしない。
6. THE 各acceptance criterion SHALL 定向自動testまたは明示Demoへtraceする。skipを成功としない。

---

## 非目標

1. 汎用ITSM製品の全機能。
2. AIチャットボットによる自動解決・自動クローズ。
3. 感情分析のみによる顧客危険判定・自動解約。
4. IMAP/POP3からの自動チケット化。
5. 契約更新ステータスの自動更新。
