# 現行経路インベントリ台帳 — カスタマーサクセス・サービスデスク (NF-02 / DG-02)

- 調査日: 2026-08-27
- 調査対象: `origin/main` (`3c0190429b9113e1a7e7d91baafc57c76bb21de7`) の現行境界。本branchの先行WIPは §8。
- 目的: 新Customer master / 新portal認証 / 第二通知基盤 / 独自file path / 契約更新の自動変更を作らず、既存正本へ接続する。

## 1. ドメイン境界（origin/main 正本）

| ドメイン | 正本テーブル | 主要クラス | 現行の操作経路 | NF-02での接続 | 所有してはいけないもの |
|---|---|---|---|---|---|
| Customer | `m_customer` | `Customer`, `CustomerService`, `CustomerApiController` | `/customer/**`, `/api/customers`。`DataScopeService.assertAllowedCustomer` | `t_service_request.customer_id` FK。ヘルス算定の顧客母集団 | 新Customer master、`trustLevel`の置換 |
| Contact | `t_customer_contact` | `CustomerContact`, `CustomerContactService`, `CrmScopeService` | 顧客detail contacts。役割JSON（決裁者/現場/調達/請求/契約）。`valid_from/to`、PII mask | 任意の `contact_id`。portal起票者はportal userでありcontactと同一視しない | contact自動merge、PIIのportal平文拡大 |
| Contract / Renewal | `t_contract`（`renewal_decision` V50） | `RenewalCalendarServiceImpl`, `RenewalEscalationServiceImpl` | `GET /api/contracts/renewal-calendar`、`/contract/renewal-calendar`。状態は導出＋明示フラグ | カレンダーDTOへヘルス/未解決P0P1/直近CSATを**表示専用**で載せる | `renewal_decision` / 更新ドラフトの自動WRITE |
| Portal identity | `m_portal_organization`, `t_portal_user`, `t_portal_session` | `PortalLoginUser`, `PortalAuthorizationService`, `PortalSecurityConfig` | `/portal/**`, `/api/portal/**` 専用chain。cookie `PORTAL_SESSION` / CSRF `XSRF-TOKEN-PORTAL`。内部`LoginUser`へ変換しない | 顧客orgのみ起票。BPは403/404。scopeは `portal_org.customer_id` | 内部session流用、第二portal認証 |
| Portal 既存「問い合わせ」 | `t_invoice.portal_inquiry` (V104_2) | `PortalInvoiceRegisterRequest.inquiry`, `PortalCustomerServiceImpl` | 請求の受領確認・支払予定日と**同一API**の1000文字メモ | **別物**。チケット化しない。service deskは別resource | invoice inquiryをticketへ暗黙変換 |
| 勤務カレンダー | `m_work_calendar`, `m_work_calendar_day` | `WorkCalendar`, `WorkCalendarDay`, `StaffingCapacityServiceImpl` | 法人/組織/**要員個人**の勤務日。勤怠FTE・休暇控除 | SLA休日の**候補源**だが、要員個人カレンダーを顧客SLAに使ってはならない | 勤怠計算の複製、`t_compliance_work_calendar`（派遣36協定・immutable）の流用 |
| 派遣コンプライアンス暦 | `t_compliance_work_calendar` | V84 | 契約単位の協定カレンダー。UPDATE/DELETE禁止trigger | **不使用** | SLA期限計算への流用 |
| 文書台帳 | `t_document`, `t_document_version`, `t_document_link` | `DocumentService`, `DocumentStorage` | `registerReceived`/`link`。版hash、quarantine/scan | 添付は `DocumentLink(target_type=SERVICE_REQUEST)` | 独自storage path列、未scan公開 |
| ファイルscope | — | `FileReferenceProvider`実装7件、`FileScopeValidationService` | 未登録fileは `error.file.unknownReference` で**deny**（§2.5の旧「allow」記述は現行コードと不一致） | 新provider登録＋link type `SERVICE_REQUEST` をFileScopeへ追加 | CSS非表示、取得後filterだけのdownload |
| 通知 | `t_notification`, `t_notification_read`, `t_notification_outbox` | `NotificationService.publish` / `publishToUser`, `NotificationLinks`, `NotificationOutboxService` | dedupe_key UNIQUE。宛先指定はorg scopeを重ねない（platform-invariants §2.4） | SLA breach/warningは `publishToUser` + `NotificationLinks` 定数 | 第二通知テーブル、第二outbox |
| AR / 請求延滞 | `t_invoice` | `InvoiceServiceImpl`（overdue日数・`INVOICE_OVERDUE`通知） | 督促・エイジング正本 | ヘルスのAR factorは既存overdue判定を**読むだけ** | 新ARエンジン、入金状態のportal更新 |
| CRM scope | — | `CrmScopeService` | 管理者全件、マネージャー=組織∩DataScope、営業=DataScope（組織で追加絞りしない） | 内部service deskの顧客母集団は **CrmScopeではなく DataScope + 組織既定** を使う（営業の担当顧客）。CRM商機画面とは別メニュー | 商機forecastへのSLA混入 |
| 承認エンジン | `t_approval_request` | `ApprovalEngineService` | 統一承認 | MVPでは問い合わせ解決に承認を必須としない | 第二承認エンジン |
| 監査 | `t_audit_log` | `ApiAuditFilter` | 更新系API | service-desk更新は既存filter対象 | 独自audit tableの代替 |

### FileReferenceProvider 現行実装（7件）

| クラス | 対象 |
|---|---|
| `BpAvailabilityFileReferenceProvider` | BP空き取込 |
| `EngineerFileReferenceProvider` | 要員写真等 |
| `ResumeIngestionFileReferenceProvider` | 履歴書取込 |
| `DocumentArchiveFileReferenceProvider` | 法定文書台帳 |
| `ProjectIngestionFileReferenceProvider` | 案件メール取込 |
| `ProposalFileReferenceProvider` | 提案 |
| `FileSecurityMetadataReferenceProvider` | スキャンメタデータ |

**欠落**: service request添付用providerは origin/main に無い。未登録のままuploadすると cleanup で削除され、downloadは unknownReference で拒否される。

### Portal 公開field inventory との関係

正本: `.kiro/specs/external-customer-bp-portal/field-inventory.md`

現行顧客portal画面は C-1〜C-8（login〜請求inquiryメモ〜署名遷移）。**問い合わせthread（C-9）は未登録**。portal design は `t_portal_message` を「問い合わせthreadが必須と確定した場合のみ」として延期している。NF-02がその確定候補。G8文書allow-listへ service-desk添付を足す場合は **field-inventoryへ明示追加**し、原価・内部メモ・内部user ID・他社IDを構造的に排除する。

## 2. 認可母集団 consumer（着手前必須）

内部service deskを足す場合、同一resolverで揃えるconsumer:

| 経路 | 現行のscope入口 |
|---|---|
| list/page | `DataScopeService.allowedCustomerIds` / `isScoped`（空集合は0件。全件と混同しない） |
| detail | `assertAllowedCustomer` → 404 `error.scope.notFound`（存在推測防止） |
| count | 同じSQL条件 |
| export/CSV | 同じSQL。`PageUtils`/`max limit`。formula injection |
| download | `FileScopeValidationService` + document link + request membership |
| notification link | `NotificationLinks` 定数。クリック時に再認可 |
| scheduler | system principal。通知は `publishToUser`（宛先指定に組織条件を重ねない） |

portal側は **DataScopeを使わない**。`PortalAuthorizationService.assertCustomerScoped(user, customerId)` と SQL `customer_id = :portalCustomerId`。顧客A/B/BPの3組織matrixを list/detail/count/export/download/CSAT/comment の全methodでtestする。

HR/要員の内部画面は menu+action で到達不可（403）。portal BPは service desk 不可視。

## 3. 時刻・休日の現行事実

| 論点 | 現行 |
|---|---|
| アプリ既定TZ | `application.yml` `spring.jackson.time-zone: Asia/Tokyo`。`Clock` bean は `Clock.systemDefaultZone()` |
| tenant TZの読み方 | `StaffingClock` / `AccountingTimezoneResolver` / `attendance.sync.timezone` は設定参照。**直書き禁止が既定** |
| 休日 | `m_work_calendar_day.day_type`（通常/休日等）。土日はコード判定している箇所あり |
| Instant vs business datetime | SLA期限は業務ローカル日時。保存は DATETIME。asOfはClock |

SLA計算機が土日のみを休日とし祝日カレンダーを読まない実装は、requirements CS-R2 と矛盾する（WIPギャップ §8）。

## 4. Migration / 並行禁止

| 項目 | 値 |
|---|---|
| origin/main の本機能直前latest | **V109**（`engineer-lifecycle-workflow`、NF-01 PASS / PR #85） |
| 本機能の採番 | 現行統合migrationは **V147**。旧NF02のV110は既存開発DBのreset/repair fixtureとしてのみ扱い、通常のmigration番号へ戻さない。V144はdigital_invoice、V145/V146はmigration-dev |
| H2 | `sql/schema-service-desk-h2.sql` を新設し `application-test.yml` の schema-locations へ追加。MySQL DDLをH2 replayに足さない |
| V1 | 増分と重複ADDしない |
| `engineer-schema-h2.sql` | 要員列を足さない限り必須ではない。service deskテーブルは専用H2へ |
| 並行禁止 | `t_contract.renewal_decision` の意味変更、`NotificationService` のdedupe意味変更、`PortalSecurityConfig` のchain統合、`FileScopeValidationService` のfallthroughをallowへ戻すこと、公開済みmigration編集 |

欠番埋め禁止。V59永久欠番。

## 5. DG-02 提案（未承認。Owner記入まで CANDIDATE/DISCOVERY）

公式台帳 `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md` のDG-02は **未決定**。以下は実装許可ではなく **提案**。採用時にOwnerがAPPROVEDする。

### DG-02-A ポータル起票対象契約と利用者

| 選択肢 | 内容 | 推奨 |
|---|---|---|
| A1 | 顧客portal orgの全有効userが自社リクエストを起票。契約/案件/要員は任意だが同一customerに属することを検証 | **推奨** |
| A2 | 契約が必須。契約未選択の請求・品質問合せが起票できない | 非推奨（請求カテゴリが死ぬ） |
| A3 | BP portalからも自社関連顧客へ起票 | 非推奨（MVP範囲外、IDOR面が増える） |

- 利用者: `PortalLoginUser` かつ orgType=`CUSTOMER`。招待・MFA・規約は既存portal正本。
- 権限キー: 新 `service-desk.view` / `service-desk.create` を portal permission に追加し、invoice.view へ相乗りしない。
- 既存 `t_invoice.portal_inquiry` は請求メモのまま残し、自動ticket化しない。

### DG-02-B SLAの営業時間・休日calendar・停止・priority

| 項目 | 提案 |
|---|---|
| 既定営業時間 | 09:00–18:00（ポリシー列で上書き可）。1日あたり終業−始業の分数 |
| timezone | `Clock` + `spring.jackson.time-zone`（またはtenant resolver）。計算機へ `Asia/Tokyo` 文字列を直書きしない |
| 休日 | **法人既定**の `m_work_calendar`（`legal_entity_id` あり、`organization_id`/`engineer_id` NULL）の `day_type` ≠ 通常 を休日とする。未設定時は土日のみ＋ `missing_calendar` をSLAメタに記録。祝日masterを新規に作らない |
| 使わない暦 | 要員個人カレンダー、`t_compliance_work_calendar` |
| Pause | `WAITING_CUSTOMER` のみ。停止分数は**営業分数**で期限を延長。過去roundの `response_breached`/`resolve_breached`/確定時刻は更新しない |
| Reopen | 新 `round_no`。UNIQUE(request_id, round_no)。旧roundは COMPLETED のまま |
| Priority 既定 | P0 応答1h/解決4h、P1 2h/8h、P2 4h/24h、P3 8h/48h。policyはversion付き。clockは適用した `policy_id` をsnapshot |
| 通知 | 期限1時間前warning、breach、継続breach。dedupe `SLA_{RESPONSE\|RESOLVE}_{WARNING\|BREACH}:{requestId}:{roundNo}` |

`UNIQUE(priority, status)` は INACTIVE版が衝突するため採用しない。ACTIVEはpriorityあたり1件を部分UNIQUEまたはCASで保証する。

### DG-02-C internal note と公開comment

| 規則 | 内容 |
|---|---|
| 列 | `t_service_comment.visibility` ∈ {`INTERNAL`,`PORTAL_VISIBLE`}。添付も同列 |
| portal読取 | SQL `visibility='PORTAL_VISIBLE'`。Java側filter禁止 |
| portal DTO | `INTERNAL`本文、内部user ID、owner_user_id、原価、監査actor内部IDを**クラスに持たない** |
| 書込 | portalは常に `PORTAL_VISIBLE`。visibilityパラメータを受け入れない |
| 誤公開防止 | allow-list contract test + 顧客A/B matrix。CSS/DOM非表示は受入に使わない |

### DG-02-D health score

| 項目 | 提案 |
|---|---|
| モデル | 100点減点。未解決P0は-30点/件、P1は-15点/件、直近30日SLA違反は-10点/件、直近90日平均CSATは3.0未満-15点・3.0以上4.0未満-5点、AR延滞-25点、60日QBRなし-10点。factorは型付き列 |
| 欠損 | CSAT未回答・QBR無し・請求データ無しは missing input。欠損を「普通点」で埋めない |
| 表示 | 合計＋factor＋期間＋算定時刻＋missing |
| 更新カレンダー | 読取専用バッジ。`RenewalCalendarService` は `Contract.renewal_decision` を変更しないことをtestで固定 |
| snapshot | customer+date+versionの一意なINSERTのみ。同一hashは冪等skip、内容変更は非空訂正理由付きの新版を追記し、最大versionを最新版として解決する。UPDATE/DELETEはDB triggerで拒否 |
| 自動契約操作 | **禁止** |

ステータス名は `HEALTHY` / `WARNING` / `CRITICAL`（80 / 50 境界）に統一する。WIPの `NEUTRAL`/`AT_RISK` や加点モデルは採用しない。

## 6. 既存画面・API（接続点）

| 種別 | パス | 接続 |
|---|---|---|
| 更新カレンダーUI | `/contract/renewal-calendar` | ヘルスバッジ列を追加（非破壊） |
| 更新カレンダーAPI | `/api/contracts/renewal-calendar` | DTO拡張。契約状態導出は既存 |
| 顧客portal | `/portal/customer` | 新タブ「問い合わせ」。既存invoice inquiryタブは残す |
| 通知ベル | `/api/notifications` | 既存publish。リンクは内部 `/service-desk/requests/{id}`。portal通知は別途portal user向けが無いためMVPは内部担当へ |
| 文書download | `/api/documents/**` 既存ACL | service desk専用downloadはmembership再確認 |

## 7. 非目標（再掲）

- 汎用ITSM全機能、チャットボット自動解決、感情分析による自動危険判定
- IMAP自動取込、匿名CSAT URL
- 契約更新/解約の自動確定
- 新Customer master、新portal identity、第二Notification/outbox

## 8. 本branchの先行WIPギャップ是正状況

`fix/nf02-main-integration-hardening` において、指摘事項（WIP-1〜11）に加えたP0/P1 hardeningの実装状況を記録する。

| 項目 | 是正後の状況 |
|---|---|
| 1. SLA休日・Clock | `ServiceSlaCalculator` に `Clock` DI および法人既定カレンダー（`m_work_calendar` / `m_work_calendar_day`）の所定休日・法定休日判定を反映。`ServiceRequestServiceImpl` も `Clock` 連動。 |
| 2. ヘルススコア | 100点減点モデル（未解決P0=-30/件、P1=-15/件、30日SLA違反=-10/件、90日CSAT低評価=-15/-5、AR延滞=-25、60日QBRなし=-10）へ整合。`HEALTHY`/`WARNING`/`CRITICAL`。欠損項目を `missing_inputs` に記録し、snapshotはappend-only revisionで保存。N+1バッチ取得対応。 |
| 3. 文書・添付 | `ServiceRequestFileReferenceProvider` 実装、`FileScopeValidationService` に `SERVICE_REQUEST` 登録、ポータル専用 download API 配線（自社スコープ・PORTAL_VISIBLE検証・RFC 5987 UTF-8 エンコード）。 |
| 4. ポータル画面・権限 | `templates/portal/customer/service-desk/list.html` 作成、ポータル起票・返信 DTO の完全分離（`PortalServiceRequestCreateRequest`, `PortalServiceCommentCreateRequest`）、他社ID改ざん防止検証、ポータル権限 seed 追加。 |
| 5. 通知リンク | `NotificationLinks.SERVICE_DESK_REQUESTS` / `serviceDeskDetail(id)` を定数化し、`NotificationLinkRouteTest` で検証。 |
| 6. ポリシー版管理 | `m_service_sla_policy` の UNIQUE 制約を INDEX に変更。 |
| 7. baseline スキーマ | `V1__create_tables.sql` から V110 由来の CREATE / DROP を完全削除し、baseline 規約に準拠。 |
| 8. 多言語整合 | 4言語（JA/EN/ZH/KO）のメッセージバンドル整合完了（`MessageBundleConsistencyTest` PASS）。 |

本対話ではこれらのproduction差分を**追加修正しない**（CANDIDATEのため spec まで）。
