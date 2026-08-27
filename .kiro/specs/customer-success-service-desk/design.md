# Design — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。現行接続は `inventory.md`、要件は `requirements.md`。

**承認状態**: DG-02は提案。APPROVED前にDDL/APIを本番へ入れてReviewしてはならない。

- 親機能: NF-02 `customer-success-service-desk`
- Migration: APPROVED着手時の merge済み latest+1（V109残存時は **V110**）。番号は予約ではなく再確認する。
- 再利用: `Customer` / `CustomerContact` / `Contract` / `RenewalCalendarService` / `PortalAuthorizationService` / `DocumentService` / `NotificationService` / `DataScopeService` / 法人既定 `WorkCalendarDay`
- 作らない: 新Customer master、新portal chain、第二outbox、`renewal_decision` writer

---

## 逸脱: platform-invariants §2.5 ファイルfallthrough

- 既定解（文書）: FileScope未登録時 allow
- 本specの解: **現行コードに従い deny**（`error.file.unknownReference`）
- 根拠: CLAUDE.md / `FileScopeValidationService` 実装。CS-R5
- 影響するconsumer: 添付download、cleanup
- 追加test: 未登録keyの403、provider登録後の自社のみ200

## 逸脱: portal 認可母集団（§2は内部sys_user向け）

portal userは DataScope/組織scope/menu を持たない。母集団は `portal_org.customer_id`。これは `external-customer-bp-portal` と同じ既知逸脱。内部service deskは§2既定（営業に組織を積集合しない）に従う。

## 逸脱: SLA休日カレンダー

- 既定解: 業務日付はtenant TZ。新規masterを安易に増やさない
- 本specの解: 休日は **法人既定** `m_work_calendar`（engineer_id/organization_id NULL）を読む。無い場合は土日＋missing_calendar。新規祝日masterは作らない
- 根拠: CS-R2.2、inventory §3。要員カレンダーは勤怠FTE正本であり顧客SLAに流用すると担当外エンジニアの休日が混入する
- 追加test: 月曜祝日を法人カレンダーへ入れたP2（4営業時間）が火曜まで延びること、個人カレンダー行を無視すること

---

## 1. データ（APPROVED後のDDL案）

候補名は衝突調査前。確定はF1着手時。

| テーブル | 役割 | 備考 |
|---|---|---|
| `m_service_sla_policy` | 優先度×versionのSLA | ACTIVEはpriorityあたり1件。`UNIQUE(priority,status)`は使わない |
| `m_service_sla_calendar_link` | policyまたはtenant→`m_work_calendar.id` | 任意。未設定は土日fallback |
| `t_service_request` | 問い合わせ | customer_id必須。FK `m_customer` |
| `t_service_sla_clock` | round別計時 | UNIQUE(request_id, round_no)。過去round不変 |
| `t_service_comment` | スレッド | visibility NOT NULL |
| `t_service_attachment_link` | document_id + visibility | 実体は台帳 |
| `t_service_state_event` | append-only遷移 | UPDATE/DELETE禁止（mapperで拒否） |
| `t_customer_csat` | 1 request 1回答 | UNIQUE(service_request_id) |
| `t_customer_qbr` / `t_customer_qbr_action` | 内部定例会 | portal非公開 |
| `t_customer_health_snapshot` | 日次snapshot | UNIQUE(customer_id, snapshot_date)。訂正は新行またはcorrection event |

同期対象（F1 DoD）: 増分Flyway、V1（重複ADD禁止）、`sql/schema-service-desk-h2.sql`、`application-test.yml` schema-locations、entity、MySQL smoke。

---

## 2. SLA計算機

入力: start Instant、targetHours、policy（営業開始/終了）、ZoneId、休日判定関数、pause営業分数。
出力: deadline Instant（DBはtenant local DATETIMEでもよいが zoneを明示）。

1. startを営業時間へalign（始業前→当日始業、終業後/休日→翌営業日始業）
2. 残分数を営業日の稼働枠から消費
3. pause延長は **pauseの営業分数** を deadline へ同じ算法で加算。壁時計Δtをそのまま足さない
4. reopenは新clock insertのみ。旧clockの breached/first_responded_at/resolved_at はUPDATEしない

Clockは注入する。`LocalDateTime.now()` 直呼びは禁止。

---

## 3. Health

減点モデル（CS-R4）:

| factor | 減点 | missing |
|---|---|---|
| 未解決P0 1件 | −30 | — |
| 未解決P1 1件 | −15 | — |
| 30日SLA違反（response or resolve、request単位で1カウント）1件 | −10 | clock無しはmissing |
| AR延滞（既存Invoice overdue>0が1件以上） | −25 | 請求0件はmissing（減点しない） |
| CSAT平均 <3.0 | −15、3.0–3.9は−5、≥4.0は0 | 回答0はmissing（減点しない） |
| 60日QBR/内部公開接触なし | −10 | QBR機能未使用期間はmissingとして表示し、運用開始前は減点しない |

`score = max(0, min(100, 100 + Σ減点))`。
RenewalCalendarは読取。`updateRenewalDecision` を呼ばないcontract testを持つ。

---

## 4. 3つの決定表

### 表1: 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| request状態 | `t_service_request.status` | `t_service_state_event` | — | 現在値 | — |
| SLA clock | 最大round_no | 過去round行 | 各roundが不変 | 指定round、無指定は最新 | 未計時 |
| breach | 当該roundのflag | 過去round | — | 当該round | 0=未超過（未評価ではない。未評価はstatus=RUNNINGかつnow<deadline） |
| CSAT | 1行 | — | 回答時刻 | 現在 | **未回答**（回答可） |
| health | 最新snapshot_date | 日次行 | snapshot | 指定日 | 未算定 |
| QBR action | status列 | 監査 | — | 現在 | owner/due未設定 |
| 休日 | 法人カレンダー当日 | カレンダー版 | — | asOf日付 | カレンダー未設定=土日fallback+missing |

### 表2: 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 内部 管理者 | 全顧客 | 全件 | 全SLA | SLA監視・日次health |
| 内部 マネージャー | 組織scope ∩ DataScope の顧客 | 同左 | 自組織担当 | — |
| 内部 営業 | DataScope顧客。**組織で追加制限しない** | 同左 | 自担当 | — |
| 内部 HR / 要員 | 不可（menu/action） | 不可 | — | — |
| portal 顧客 | 自社 `customer_id` かつ PORTAL_VISIBLE のみ | 自社 PORTAL_VISIBLE 添付のみ | MVPは内部担当へ（portal inboxは既存portal通知設定の範囲外なら出さない） | — |
| portal BP | 不可 | 不可 | — | — |
| scheduler | 全件 | — | `publishToUser`（組織条件なし） | ShedLock claim |

条件はSQLへ渡す。空許可集合は `id=-1` で0件。取得後filter禁止。

### 表3: 状態機械と競合

| 状態 | 許可遷移 | 防重 | competing writer | rollback |
|---|---|---|---|---|
| RECEIVED | IN_PROGRESS, WAITING_CUSTOMER, CLOSED | 状態CAS + version | 同時着手 | 409 |
| IN_PROGRESS | WAITING_CUSTOMER, RESOLVED, CLOSED | 同上 | 同時解決 | 409 |
| WAITING_CUSTOMER | IN_PROGRESS, RESOLVED, CLOSED | CAS + pause/resume | portal返信と内部操作 | 先着1 |
| RESOLVED | CLOSED, （コマンドREOPEN→IN_PROGRESS） | CAS + UNIQUE round | 顧客reopenと内部close | 先着1 |
| CLOSED | コマンドREOPEN→IN_PROGRESS | CAS + UNIQUE round | 二重reopen | 2件目失敗 |
| CSAT | 1回 | UNIQUE(request_id) | 二重POST | 409 |
| SLA scheduler | breach flag 0→1 | 条件付きUPDATE flag=0 | 二重ノード | ShedLock + dedupe_key |

`COMPLETED` clockのdeadline/breachをpauseや再計算で書き換えない。

---

## 5. API / UI（案）

### 内部

- Pages: `/service-desk/requests`, `/service-desk/requests/{id}`, `/service-desk/qbr`, `/service-desk/health`
- API: `/api/service-desk/requests` CRUD+status+comments、export、attachments download
- `/api/service-desk/qbr`, `/api/service-desk/health`
- menu_key `service-desk`。action は URI から機械生成（allow-list化しない）。HR/ENGINEERグループへ `service-desk.*` を付けない
- 一覧は `.card > .card-body > form#searchForm`

### portal

- `/portal/customer` に問い合わせタブ、詳細 `/portal/customer/service-desk/requests/{id}`
- API: `/api/portal/customer/service-desk/requests`（list/detail/create/comment/csat/download）
- permission: `service-desk.view` / `service-desk.create`
- 公開field（C-9案）: id, requestNo, category, priority, subject, description, status, firstResponseAt, resolvedAt, closedAt, createdAt, PORTAL_VISIBLE comments（author表示名のみ）, csatScore, csatAnswerable。非公開: ownerUserId, 内部user id, INTERNAL comment, 原価, SLA policy内部, health

`PortalLoginUser` を `LoginUser` へ変換する経路を作らない。

### 更新カレンダー

`RenewalCalendarItemDto` に healthStatus, openCriticalCount, latestCsat を追加。writerはhealth serviceのread。Contract更新APIは無変更。

---

## 6. 通知 / scheduler

- `ServiceSlaScheduler`: cron設定可、`@SchedulerLock`
- テストから `processSlaMonitoring(asOf)` を直接呼ぶ（test profileはscheduling無効）
- 通知type: `SERVICE_DESK_SLA_WARNING` / `SERVICE_DESK_SLA_BREACH`
- 宛先: owner_user_id。未設定時は顧客の主担当営業（`EngineerSales`ではなく契約 `sales_user_id` または顧客DataScopeの営業）。宛先0なら管理者へescalationし、黙って捨てない
- `NotificationLinks.SERVICE_DESK_REQUEST` を追加し `NotificationLinkRouteTest` 対象にする

---

## 7. ファイル

保存順は台帳既定: quarantine → scan → hash → DB metadata → promote。
`FileReferenceProvider` 新実装 `ServiceDeskFileReferenceProvider`。
`FileScopeValidationService` に `SERVICE_REQUEST` を追加し、内部はDataScope顧客、portalはPortalAuthorization＋visibility。

---

## 8. テスト最低セット

- 金〜月の祝日跨ぎSLA、営業時間外align、pause営業分数、reopen旧round不変
- scheduler二重起動（lock）とbreach通知dedupe、継続breachの別key
- 二重CSAT 409、portal A→B の request/comment/csat/count/export/download 404
- INTERNALがportal JSONに無い（フィールド名assert）
- healthがrenewal_decisionを変えない
- provider/通知障害でもrequest状態が中途半端にCLOSEDしない
- desktop/390px Demo

---

## 9. 先行WIPとの差分（実装再開時の是正リスト）

`inventory.md` §8。F1以降を完了扱いにする前に、計算機の休日/Clock、ヘルス減点モデル、添付provider、NotificationLinks、policy UNIQUE、portal listテンプレート、snapshot overwrite、配点の三文書一致を閉じる。
