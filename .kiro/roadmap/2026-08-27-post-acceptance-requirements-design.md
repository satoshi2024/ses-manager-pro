# 受入後機能候補 — 要件・設計基線

- 親文書: `2026-08-27-post-acceptance-feature-backlog.md`
- 状態: **候補設計**。採用時に対象feature専用の`requirements.md`、`design.md`、`tasks.md`へ転記し、現行inventoryで更新する。
- 原則: 本書のtable/API名は衝突調査前の候補であり、確定名ではない。Migration番号は予約しない。

## 1. 共通要件

### CR-01 認証・認可

1. THE システム SHALL Spring Securityの既存session/portal security chainを正本として使用する。
2. THE システム SHALL page、API、export、download、notification、schedulerの可視母集団を同じscope resolverで決定する。
3. WHEN scope外IDが指定された場合、THE システム SHALL データの存在を推測できない一貫した拒否を返す。
4. THE 更新API SHALL CSRF、action permission、menu permission、domain scopeを全て通過した場合だけ実行する。

### CR-02 状態・競合・冪等

1. THE 各feature SHALL 許可状態遷移、terminal state、再open/取消/訂正をdesignの決定表で固定する。
2. WHEN 同じ状態変更が並行実行された場合、THE システム SHALL CAS/楽観ロックまたはDB unique制約で1件だけ成功させる。
3. WHEN clientまたは外部providerが同じrequest/eventを再送した場合、THE システム SHALL 同じ結果を返し、副作用を重複させない。
4. THE システム SHALL 外部I/OをDB transaction外で実行し、outbox/job/compensationで整合させる。

### CR-03 データ・Migration

1. WHEN schemaを変更する場合、THE 実装 SHALL V1、順方向Flyway、H2 replay、`engineer-schema-h2.sql`、entity、MySQL smoke assertを同期する。
2. THE 実装 SHALL 公開済みmigrationを変更せず、欠番を補填せず、開始時点latest+1を使用する。
3. THE 金額 SHALL 円単位で保存し、割合、税、丸め、NULL、0、確定/予測を区別する。
4. THE 日時 SHALL instantとbusiness date/timezoneを区別し、Asia/Tokyo境界、月末、閏日、DST対象zoneをtestする。

### CR-04 監査・PII・ファイル

1. THE 更新 SHALL actor、target、scope、correlation ID、結果、before/afterまたはsnapshot hashを監査可能にする。
2. THE システム SHALL password、token、secret、銀行、マイナンバー、未redact添付本文をlogへ出さない。
3. THE file SHALL size/type/content/path、virus scan状態、所有scopeを検証し、未知または検査失敗時はfail-closedとする。
4. THE export SHALL CSV injection、formula injection、path traversal、scope外データ混入を防ぐ。

### CR-05 UI・i18n・accessibility

1. THE UI SHALL desktopと390pxで主要操作を完了できる。
2. THE UI SHALL keyboard focus、label、aria、error summary、loading/empty/error/forbidden状態を持つ。
3. THE 文言 SHALL 現行message bundle全てで同じkeyを持ち、repositoryの日本語文体に合わせる。
4. THE 更新ボタン SHALL 二重clickを抑止するが、server側冪等性の代替にしない。

### CR-06 Test・Demo・運用

1. THE 各acceptance criterion SHALL 自動testまたは明示Demoへtraceされる。
2. THE test SHALL happy pathだけでなく403、CSRF、validation、競合、再送、外部timeout、0件、最大件数を含む。
3. THE scheduler/job SHALL multi-node claim、stale claim recovery、retry上限、dead-letter、手動再実行をtestする。
4. THE release SHALL feature flag、監視、runbook、rollback/compensation、backup/restoreの証拠を持つ。

## 2. NF-01 `engineer-lifecycle-workflow`

### 2.1 Requirements

#### LC-R1 テンプレート

1. THE 管理者/HR SHALL lifecycle種別ごとにversion付きテンプレートを作成できる。
2. THE template SHALL Task名、説明、relative due、担当解決rule、必須/任意、依存、証跡種別、完了条件を持つ。
3. WHEN active caseが存在するtemplateを変更する場合、THE システム SHALL 既存caseを暗黙更新せず、新versionを後続caseへ適用する。

#### LC-R2 Case生成と実行

1. WHEN lifecycle eventを開始する場合、THE システム SHALL engineer snapshotとtemplate versionからcase/taskを原子的に生成する。
2. THE Task担当 SHALL user、role、organization責任者、担当営業等のruleから生成時にsnapshot化される。
3. WHEN 担当不明または循環依存がある場合、THE システム SHALL case生成を失敗させ、部分Taskを残さない。
4. THE Task完了 SHALL comment、evidence、actor、timestampを保存する。

#### LC-R3 退社gate

1. THE 退社case SHALL user無効化、session失効、portal link、担当引継ぎ、未返却資産、未精算、保存文書を確認する。
2. WHEN block Taskが未完了の場合、THE システム SHALL caseを完了させない。
3. WHEN 例外承認で完了する場合、THE システム SHALL 既存ApprovalEngineへ申請し、理由、期限、risk ownerを保持する。

#### LC-R4 Scope・通知・監査

1. THE 本人 SHALL 自分に公開されたTaskだけ閲覧でき、内部security Taskを閲覧できない。
2. THE HR/管理者/担当者 SHALL 許可scope内caseだけ閲覧・更新できる。
3. THE システム SHALL 期限前、期限超過、blocker、完了をdedupe通知する。

### 2.2 Design candidate

#### Data

- `m_lifecycle_template`: type、name、version、status、effective period。
- `m_lifecycle_template_task`: template version、task code、relative due、assignee rule、evidence type、blocking flag。
- `t_lifecycle_case`: engineer、type、template version、anchor date、status、scope snapshot、version。
- `t_lifecycle_task`: case、task code、assignee snapshot、due、status、completed metadata、version。
- `t_lifecycle_task_dependency`: predecessor/successor。
- `t_lifecycle_evidence_link`: task→既存document/file/external verification。
- `t_lifecycle_event`: append-only state/audit event。

#### State

| Entity | Allowed state |
|---|---|
| Case | `DRAFT→ACTIVE→COMPLETED`、`DRAFT/ACTIVE→CANCELLED`、`ACTIVE→ON_HOLD→ACTIVE` |
| Task | `PENDING→IN_PROGRESS→COMPLETED`、`PENDING/IN_PROGRESS→ON_HOLD`、訂正は新event |

`COMPLETED`を直接再openせず、case amendmentまたはcorrection taskを生成する。

#### API/UI

- `GET/POST /api/lifecycle/templates`
- `GET/POST /api/lifecycle/cases`
- `GET /api/lifecycle/cases/{id}`
- `POST /api/lifecycle/cases/{id}/activate|hold|resume|complete|cancel`
- `POST /api/lifecycle/tasks/{id}/start|complete|correct`
- `/lifecycle/templates`、`/lifecycle/cases`、要員詳細のlifecycle card、`/my/lifecycle`

#### Test/Demo minimum

- template version固定、循環dependency拒否、担当解決不能rollback。
- 二重completeでevent/通知1件。
- 退社blocker、例外承認、scope外403、本人への内部Task非表示。
- 390pxで本人Task完了、証跡添付、offlineはNF-04まで対象外。

## 3. NF-02 `customer-success-service-desk`

### 3.1 Requirements

#### CS-R1 問い合わせ

1. THE 内部利用者/portal利用者 SHALL 顧客、契約、案件に紐づく問い合わせを起票できる。
2. THE request SHALL category、priority、channel、subject、description、owner、status、SLA、visibilityを持つ。
3. THE comment SHALL `INTERNAL`と`PORTAL_VISIBLE`をDB列とDTOで分離し、frontend表示だけで隠さない。

#### CS-R2 SLA

1. THE SLA SHALL priority/customer/contract別にversion付きruleを解決する。
2. THE deadline SHALL business calendarとtimezoneで計算し、休日/営業時間外を除外する。
3. WHEN owner待ち、顧客待ち等のpause stateへ入る場合、THE システム SHALL pause reasonと期間を保持する。
4. THE scheduler SHALL breach前/発生時/継続時にdedupe通知する。

#### CS-R3 CSAT/QBR

1. WHEN requestが解決した場合、THE portal SHALL 1回だけCSAT回答を受け付ける。
2. THE 営業/manager SHALL 定例会/QBRの議題、決定、action、次回日を保存できる。
3. THE action SHALL owner/due/statusを持ち、通知と検索に現れる。

#### CS-R4 Health

1. THE health SHALL open critical issue、SLA breach、CSAT、AR overdue、更新意向等の説明可能factorから計算する。
2. THE UI SHALL total scoreだけでなくfactor、期間、更新日時、missing inputを表示する。
3. THE health SHALL 契約更新を自動確定/終了させない。

### 3.2 Design candidate

#### Data

- `m_service_sla_policy`、`m_service_sla_calendar_link`。
- `t_service_request`、`t_service_comment`、`t_service_attachment_link`、`t_service_state_event`。
- `t_service_sla_clock`: response/resolve deadline、pause total、breached flags。
- `t_customer_csat`、`t_customer_qbr`、`t_customer_qbr_action`。
- `t_customer_health_snapshot`: factor JSONではなく主要factorを型付き列/child tableで保持する案を優先。

#### State

`RECEIVED→IN_PROGRESS→WAITING_CUSTOMER→RESOLVED→CLOSED`。`RESOLVED/CLOSED→REOPENED→IN_PROGRESS`は
新roundを作成し、以前のSLA結果を上書きしない。

#### Security

- portal DTO allow-list。内部メモ、内部担当user ID、原価、監査情報を公開しない。
- 添付downloadはrequest membershipとdocument/file scopeを再確認する。
- CSAT tokenの匿名URL方式は使わず、portal session＋request membershipで認可する。

#### Test/Demo minimum

- 日本の休日を跨ぐSLA、pause/resume、reopen round、scheduler重複。
- portal Aからcustomer B request、内部comment、添付、count/exportの漏えい拒否。
- 更新カレンダーにcritical issue/healthを表示し、状態を自動変更しない。

## 4. NF-03 `certification-learning-skill-gap`

### 4.1 Requirements

#### SK-R1 資格

1. THE 管理者/HR SHALL 資格masterと期限ruleを管理できる。
2. THE 要員 SHALL 資格取得、番号、取得日、有効期限、証憑を申請できる。
3. THE 承認 SHALL 証憑確認後に有効化し、変更/取消履歴を残す。
4. THE scheduler SHALL 90/60/30日等の設定日で本人/上長へ通知する。

#### SK-R2 研修

1. THE HR SHALL course、provider、費用、期間、対象skill、定員を管理できる。
2. THE 要員/上長 SHALL 学習計画を作成し、費用が閾値以上なら既存承認を使用する。
3. THE 実績 SHALL start/complete/cancel、score、certificate document、費用を保持する。

#### SK-R3 Skill gap

1. THE システム SHALL 案件/募集枠の必要skillとas-of時点の要員skillを比較する。
2. THE gap SHALL skill taxonomy、必要level、現在level、根拠案件数、対象期間を表示する。
3. THE AI SHALL 研修候補を提示できるが、評価や配置を自動確定しない。

### 4.2 Design candidate

- `m_certification`、`t_engineer_certification`、`t_certification_event`。
- `m_training_course`、`t_learning_plan`、`t_training_enrollment`。
- `t_skill_gap_snapshot`は計算コスト/説明性が必要な場合だけ導入し、正本は既存skill/demand。
- 証憑は`DocumentLink`。独自file path列を作らない。
- skill表記は既存`SkillTagResolver`/taxonomyへ寄せる。
- 資格番号はPII分類し、list/export/AI allow-listを制限する。

#### Test/Demo minimum

- 有効期限境界、取消、重複資格、証憑scope、費用承認。
- as-of skill履歴と案件期間の比較、未知skill、同義tag。
- AI provider失敗時もrule-based gapを表示する。

## 5. NF-04 `mobile-pwa-self-service`

### 5.1 Requirements

#### PW-R1 Install/cache

1. THE app SHALL manifest、icon、service worker version、update promptを提供する。
2. THE cache SHALL shell/static assetだけを既定とし、authenticated API responseとPIIをCache Storageへ保存しない。
3. WHEN logout/user switch/token失効が起きる場合、THE app SHALL user-scoped draft/queueを削除または再認証まで暗号化隔離する。

#### PW-R2 Draft/sync

1. THE app SHALL 勤怠、経費、変更申請の入力途中draftをuser/feature/periodごとに保存できる。
2. THE draft SHALL password、token、添付binary、銀行、給与明細を含まない。
3. THE queued command SHALL client request ID、payload hash、base versionを持つ。
4. WHEN online復帰する場合、THE app SHALL 同一commandを高々1回適用し、競合は人へ差分提示する。

#### PW-R3 UX

1. THE UI SHALL 390pxでhorizontal scrollなしに主要操作を完了する。
2. THE UI SHALL offline、同期中、同期済み、競合、失敗、再認証必要を区別する。
3. THE accessibility SHALL keyboard、screen reader label、focus restore、error summaryを満たす。

### 5.2 Design candidate

- frontend: `/manifest.webmanifest`、`/service-worker.js`、`pwa-sync.js`。
- server: 汎用command replay endpointではなく、各domain APIに`X-Client-Request-Id`とversionを追加する。
- idempotency storeはuser＋operation＋request IDのunique、response digest、expiryを持つ。
- IndexedDB storeはuser stable IDをplain keyにしないhash化を検討し、logoutでclearする。
- service workerは`/api/**`、`/portal/**`、document、payrollをnetwork-only/no-storeにする。

#### Test/Demo minimum

- offline入力→online復帰→1件登録、二重click/再送も1件。
- server更新後の古いdraftは409＋差分、上書きなし。
- user A logout→B loginでA draft/queue非表示・非送信。
- service worker cache inspectionでPII API response 0件。

## 6. NF-05 `integration-hub-public-api`

### 6.1 Requirements

#### IH-R1 Client/security

1. THE 管理者 SHALL API client、owner、scope、allowed operation、rate、IP、expiryを管理できる。
2. THE secret SHALL 平文再表示せず、hash/encryption/rotation/versionを既存secret基盤に従う。
3. THE request SHALL client、scope、correlation ID、rate decisionを監査する。

#### IH-R2 Contract

1. THE API SHALL `/external-api/v1/**`等のversion namespaceとOpenAPI契約を持つ。
2. THE response SHALL 外部専用DTO、cursor pagination、stable error codeを使用する。
3. THE command SHALL Idempotency-Keyとrequest digestを要求し、同key別payloadを拒否する。

#### IH-R3 Webhook

1. THE outbound SHALL event type、event ID、created time、schema version、payload、signatureを送信する。
2. THE receiver SHALL timestamp toleranceとevent IDでreplay/duplicateを拒否する。
3. THE delivery SHALL claim、timeout、exponential backoff、max attempt、DLQ、manual replayを持つ。
4. THE inbound SHALL provider signature、raw hash、provider event ID unique、processing resultを保持する。

### 6.2 Design candidate

- `m_api_client`、`m_api_client_scope`、`t_api_credential_version`。
- `m_webhook_subscription`、既存`t_notification_outbox`を汎用化できるかinventoryし、別outboxは必要性を証明してから。
- `t_inbound_event`、`t_api_idempotency_record`、`t_api_usage_bucket`。
- filter chainはportal/internalと分離し、client principalを既存userに偽装しない。
- external DTO mapperはallow-list testを持ち、entity serializationを禁止する。

#### Test/Demo minimum

- client A/B scope、revoked/expired credential、rotation overlap、rate boundary。
- duplicate idempotency、same key/different payload、cursor stability。
- signature改ざん、古いtimestamp、duplicate event、DLQ/manual replay。
- secret/PII log scan、外部DTO field inventory。

## 7. NF-06 `data-migration-import-center`

### 7.1 Requirements

#### IM-R1 Upload/mapping

1. THE 管理者 SHALL 対応entity、schema version、encodingを指定してCSV/XLSXをuploadできる。
2. THE システム SHALL header/sample/typeをpreviewし、source→canonical field mappingを保存できる。
3. THE mapping SHALL required、default、transform、lookup、date/amount formatをversion付きで持つ。

#### IM-R2 Validate

1. THE validate SHALL DBを変更せず、row、field、code、message、severity、candidate resolutionを返す。
2. THE validate SHALL cross-row duplicate、reference order、customer-project-contract整合、role/status、期間/金額をdomain serviceと同じruleで確認する。
3. THE error export SHALL 元row番号とsanitized reasonを含み、formula injectionを防ぐ。

#### IM-R3 Apply/recovery

1. THE apply SHALL READYかつ同じsource hash/mapping versionの場合だけ開始する。
2. THE job SHALL chunk/checkpoint、成功/失敗件数、business ID mapping、correlation IDを持つ。
3. WHEN processが中断する場合、THE system SHALL checkpointから安全に再開または補償できる。
4. THE rollback SHALL 自動可能/承認必要/不可を開始前に表示し、後続参照を無視してhard deleteしない。

#### IM-R4 Reconciliation

1. THE completion SHALL source/accepted/rejected/applied/updated/skipped件数を一致させる。
2. THE money-bearing import SHALL 金額合計、currency/円、期間別件数を照合する。
3. THE report SHALL source hash、mapping version、実行者、時刻、result hashを保存する。

### 7.2 Design candidate

- `t_import_job`、`t_import_mapping`、`t_import_row_result`、`t_import_checkpoint`、`t_import_id_map`。
- source fileは既存Document/File基盤へ保存し、retentionとscopeを付ける。
- parser→canonical DTO→domain serviceの順。mapper直insertは禁止。
- 大量row resultは全payload JSON永続化を避け、error/ID/hash中心にする。
- 既存Engineer CSVは互換維持し、NF-06 adapterへ段階統合する。

#### Test/Demo minimum

- UTF-8 BOM/Shift_JIS、quoted newline、formula、10,000行、duplicate、missing ref。
- validate no-write、apply二重実行、mid-chunk crash/restart、reconciliation。
- rollback可能case、後続参照で補償へ移るcase。

## 8. NF-07 `privacy-retention-dsar`

### 8.1 Requirements

#### PR-R1 Inventory/policy

1. THE privacy owner SHALL data element、location、subject type、purpose、owner、classificationを登録できる。
2. THE retention policy SHALL trigger、duration、action、legal basis、version、effective periodを持つ。
3. THE system SHALL owner/policy不明のPIIをdashboardで不足として表示する。

#### PR-R2 Hold/disposition

1. THE authorized user SHALL legal holdを対象/理由/期間/approver付きで設定できる。
2. THE disposition SHALL dry-runでcandidate、blocked、unknown、countを表示する。
3. WHEN hold、法定保存、監査、active contract等がある場合、THE system SHALL 削除/匿名化をblockする。
4. THE execution SHALL approval、batch claim、idempotency、result evidenceを持つ。

#### PR-R3 本人請求

1. THE authorized staff SHALL 開示/訂正/利用停止/削除等のcaseを登録し、本人確認を記録できる。
2. THE search SHALL subject resolutionとscopeを分離し、候補誤結合を人が確認する。
3. THE export SHALL security review済みfieldだけを含み、第三者情報をredactする。
4. THE case SHALL due、owner、decision、理由、delivery evidence、appeal/reopenを保持する。

### 8.2 Design candidate

- `m_pii_data_element`、`m_retention_policy`、`t_legal_hold`。
- `t_privacy_request`、`t_privacy_request_subject_link`、`t_privacy_action`。
- `t_disposition_job`、`t_disposition_item`（対象IDは必要最小限、sensitive value複製禁止）。
- `PrivacyDataProvider` interfaceでdomain別search/export/restrict/anonymize候補を提供する。
- deleteは論理削除だけで完了扱いにせず、目的に応じて匿名化/参照制限/物理処分を区別する。

#### Test/Demo minimum

- holdとretention競合、policy version/as-of、dry-run no-write。
- 同姓同名のsubject resolution、第三者redaction、scope外provider。
- batch再送、部分失敗、処分後のaudit/法定文書整合。

## 9. NF-08 `ai-management-copilot`

### 9.1 Requirements

#### AI-R1 Semantic catalog

1. THE data owner SHALL query ID、説明、parameter schema、allowed roles/scope、service method、output schemaをReviewできる。
2. THE runtime SHALL catalog外SQL、table名、column名をLLMから受け取って実行しない。
3. THE catalog change SHALL code reviewとcontract testを必須とする。

#### AI-R2 Answer/citation

1. THE answer SHALL value、unit、period、timezone、freshness、confirmed/forecast、source linkを示す。
2. THE LLM SHALL typed resultを要約するだけで、金額/件数を文字列から再計算しない。
3. WHEN dataが不足/曖昧な場合、THE system SHALL 質問を明確化または回答不能とし、推測値を確定値として返さない。

#### AI-R3 Safety/feedback

1. THE gateway SHALL PII allow-list/redaction、provider gate、timeout、cost、model/prompt versionを記録する。
2. THE user SHALL helpful/incorrect/unsafeとcommentをfeedbackできる。
3. THE system SHALL 回答から業務状態を自動更新しない。command提案は別の確認済みAPI/承認へ渡す。

### 9.2 Design candidate

- `m_ai_semantic_query`はcode catalogを正本にする場合不要。DBで管理するなら任意service bean名実行を禁止し、enum registryへ限定する。
- `t_ai_query_run`、`t_ai_answer_feedback`。既存AI run/item/feedbackへ統合可能性を先にinventoryする。
- pipeline: intent classification→catalog selection→typed parameter validation→authorization/scope→service call→typed result→LLM summary→citation validation。
- prompt injection対策: DB本文/顧客文書をinstructionとして扱わず、data delimiterとallow-listを使用する。

#### Test/Demo minimum

- scope A/B、catalog外質問、prompt injection、巨大result、0/NULL/forecast。
- 同じ集計serviceの画面値/export値/AI値一致。
- provider timeout/429/invalid response、PII log/egress scan。

## 10. NF-09 `asset-account-license-lifecycle`

### 10.1 Requirements

#### AS-R1 資産

1. THE 管理者 SHALL asset tag、serial、type、owner法人、状態、場所、期限を管理できる。
2. THE assignment SHALL engineer/user、期間、目的、受渡し/返却証跡を持つ。
3. THE system SHALL 同一assetの期間重複貸与をDB/transaction境界で拒否する。

#### AS-R2 Account/license

1. THE system SHALL external system/account reference、owner、権限分類、provision/revoke確認を保持する。
2. THE system SHALL password、token、recovery codeを保存しない。
3. THE license SHALL seat上限、割当、期限、費用centerを持ち、超過を拒否/警告する。

#### AS-R3 棚卸し・紛失・退社

1. THE inventory SHALL expected/observed、実施者、日時、差異、証跡を保持する。
2. THE lost asset SHALL incident、通知、remote wipe確認、警察/保険等の任意証跡を追跡する。
3. THE NF-01退社case SHALL 未返却asset/未失効accountをblockerとして取得できる。

### 10.2 Design candidate

- `m_asset`、`t_asset_assignment`、`t_asset_event`、`t_asset_inventory_run/item`。
- `m_external_account_system`、`t_external_account_reference`、`m_license_plan`、`t_license_assignment`。
- account external IDもPIIになり得るためlist/export/logを制限する。
- MDM/IdP連携はNF-05 provider adapter経由。外部結果はrequest/confirmation時刻を分ける。

#### Test/Demo minimum

- 重複貸与競合、返却後再貸与、棚卸し差異、license上限。
- 退社block、承認例外、外部revoke timeout/unknown result。
- secret field不存在/DTO/log scan。

## 11. NF-10 `scheduled-management-reporting`

### 11.1 Requirements

#### RP-R1 Template/schedule

1. THE 管理者/manager SHALL report type、section、period、timezone、format、scope、scheduleを管理できる。
2. THE template change SHALL version化され、過去runの表示を変更しない。
3. THE scheduler SHALL system principalと固定scopeで実行し、作成者session失効の影響を受けない。

#### RP-R2 Snapshot/generation

1. THE run SHALL cutoff、data freshness、confirmed/forecast、section result hashを保持する。
2. THE generator SHALL 既存service/DTOを使用し、独自SQLで集計式を複製しない。
3. THE document SHALL version、source run、hash、生成者を既存DocumentServiceへ登録する。

#### RP-R3 Delivery

1. THE owner SHALL recipient previewで氏名、組織、portal access、scopeを確認できる。
2. THE delivery SHALL attachmentを既定とせず、期限付き認可linkを通知する。
3. THE outbox SHALL retry、DLQ、手動再送、取消、監査を持つ。
4. THE recipient SHALL 自分のscopeを超えるreportを開けない。

### 11.2 Design candidate

- `m_report_template`、`m_report_template_version`、`m_report_schedule`。
- `t_report_run`、`t_report_section_snapshot`、`t_report_delivery`。
- scheduler claim/leaseは既存job patternを再利用する。
- PDF/XLSX generatorはworkspace外部ツールに依存せず、アプリruntimeで再現可能なlibraryだけを使用する。

#### Test/Demo minimum

- 月末cutoff、timezone、速報/確定、再生成version。
- scope付きrecipient preview、誤配布拒否、期限切れlink。
- scheduler二重起動、generation部分失敗、delivery retry/DLQ。

## 12. Spec化時の標準tasks

採用featureごとに次の粒度へ分解する。各TaskにはObjective、Requirements、実装ガイダンス、test、Demo、rollbackを記載する。

- [ ] **0. Discovery/Gate**: 現行inventory、利用者、KPI baseline、法務/security/外部判断、非目標。
- [ ] **F1. Data/State foundation**: decision table、DDL/entity/mapper、migration同期、state/concurrency tests。
- [ ] **F2. Domain service**: scope、validation、transaction、idempotency、audit、unit/integration tests。
- [ ] **A1. Internal API/UI**: page/API、i18n、403/CSRF、desktop/390px Demo。
- [ ] **A2. External/My UI**: portal/PWA/external DTO等。該当しない場合は削除し、空Taskを残さない。
- [ ] **B1. Scheduler/Notification/Integration**: claim、outbox、retry、DLQ、monitoring。
- [ ] **B2. Export/Recovery/Operation**: CSV/PDF、reconciliation、rollback、runbook。
- [ ] **M. Integrated gate**: fast/MySQL/performance、security、browser Demo、backup/restore、ledger、独立Review handoff。

## 13. 独立Reviewの判定基準

### PASS

- P0/P1が0件。
- requirements→implementation→test→Demoが全てtrace可能。
- 同じHeadで必要gateが成功し、skip 0。
- 未検証がrelease blockerではない、または別release gateで明確にblockされている。

### CONDITIONAL PASS

- code/spec上のP0/P1は0件。
- 実provider、本番IdP、法務承認、実データrestoreなどrepository外の証拠だけが未完。
- feature flag既定OFFで、未完gate、owner、期限、確認手順が明確。

### FAIL

- P0/P1が1件以上、対象diff不明、migration/rollback不明、scope/PII/金額の証拠不足、または重要test skipがある。
