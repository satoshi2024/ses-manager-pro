# Requirements — 資産・アカウント・ライセンス ライフサイクル管理 (`asset-account-license-lifecycle` / NF-09)

## 1. 前提・背景

SES企業において、PC・スマートフォン・セキュリティキー・検証端末等の情報資産、Google Workspace・GitHub・Slack・クラウド等のSaaS/IaaS外部アカウント、および各種有償ソフトウェアライセンスは、要員（正社員・契約社員・BP要員）の入社・配属・異動・休職・退社ライフサイクルと密接に連動して貸与・発行・回収・失効が行われる。
従来の手作業管理では以下の課題と内部統制・情報セキュリティリスクが存在していた:
1. **期間重複貸与・二重引渡しの発生**: 同一のPCや端末が、返却処理の未完了や台帳不整合により複数要員へ同時に貸与されるリスク。
2. **秘密情報の不正保存**: パスワード、APIトークン、リカバリーコード等が平文や管理台帳に保存され漏えいするリスク。
3. **退社時の回収・失効漏れ**: 退社後も外部SaaSアカウントや貸与端末が有効なまま放置され、不正アクセスやライセンス費用の無駄が生じるリスク。
4. **外部連携のタイムアウト誤認**: 外部IdP/MDM等のAPIがタイムアウトやエラーを起こしたにもかかわらず「失効完了」と誤認されるリスク。
5. **棚卸し差異の不透明性**: 理論在庫と実地棚卸しの差異、紛失端末のインシデント追跡が体系化されていないリスク。

本機能（NF-09 / `asset-account-license-lifecycle`）は、物理資産・外部アカウント参照・ライセンスの全ライフサイクルを統合管理し、期間重複の排他制御、秘密非保存の徹底、外部失効確認の厳格化、棚卸し差異追跡、および `engineer-lifecycle-workflow` (NF-01) 退社ゲートとの強固な連携を提供する。

---

## 2. 業務要件

### AS-R1 資産管理と貸与・返却・イベント台帳 (Asset & Assignment Lifecycle)

1. THE 管理者 SHALL 資産タグ（`asset_tag` / 全社一意）、シリアル番号（`serial_no`）、資産名称、資産区分（`category`: PC, MONITOR, SMARTPHONE, SECURITY_KEY, TABLET, OTHER）、所有法人（`owner_company_id`）、ステータス（`status`: `IN_STOCK: 保管中`, `ASSIGNED: 貸与中`, `UNDER_MAINTENANCE: 修理/保守中`, `LOST: 紛失`, `DISPOSED: 廃棄済`, `RESERVED: 予約済`）、保管場所（`location`）、取得日、保証期限、リース満了日、および備考を管理できる。
2. THE 貸与（Assignment） SHALL 対象資産、貸与先区分（`assignee_type`: `ENGINEER: 要員`, `USER: 内部ユーザー`）、貸与先ID（`assignee_id`）、貸与開始日（`start_date`）、返却予定日（`expected_return_date`）、実際の返却日（`actual_return_date`）、受渡し証跡文書ID（`handover_evidence_doc_id` / 既存 `DocumentLink` 連携）、返却証跡文書ID（`return_evidence_doc_id` / 既存 `DocumentLink` 連携）、および状態（`ACTIVE`, `RETURNED`, `OVERDUE`, `WAIVED`）を保持する。
3. **期間重複貸与の絶対拒否と返却直後再貸与**: THE システム SHALL 同一資産に対して期間が重複する貸与（`actual_return_date` が NULL のアクティブ貸与が存在する状態での新規貸与、または指定期間が既存貸与区間と重複する貸与）を、DB制約およびトランザクション境界（行ロック `FOR UPDATE` + 期間重複判定）で確実に拒否（Fail-Closed）する。返却処理の `actual_return_date` は `start_date <= actual_return_date <= 今日` を満たさなければならない。返却完了（`actual_return_date` 設定、ステータス `IN_STOCK` 復帰）直後の別要員への再貸与は正常に許可し、返却日と同日の再貸与も許可する（`actual_return_date` は次の貸与期間に対して排他的な境界とする）。並行リクエストに対してもCAS/行ロックにより1件のみを成功させ、重複貸与を成立させない。
4. **履歴の不変性 (Immutable Event History)**: THE システム SHALL 資産の登録、貸与、返却、移管、修理出入、紛失報告、リモートワイプ確認、廃棄等の全イベントを、改ざん不能な追記専用台帳（`t_asset_event`）へ記録する。過去のイベント履歴の上書き・物理削除は禁止する。履歴サービスは汎用 `IService` の更新・削除入口を公開せず、実MySQLではUPDATE/DELETE triggerでも保護する。
5. **論理削除の安全条件 (Soft Delete Invariants)**: THE システム SHALL 以下の安全条件を遵守する。
   - (a) **未返却貸与中の資産を論理削除してはならない**: `status IN ('ACTIVE', 'OVERDUE') AND actual_return_date IS NULL` の `t_asset_assignment` が存在する資産を `deleted_flag = 1` にすること（管理者操作を含む）は禁止する。論理削除前に必ず返却完了（`RETURNED`）または承認済みWAIVEDへの状態遷移が必要である。
   - (b) **外部アカウント参照履歴を論理削除してはならない**: `status IN ('ACTIVE', 'SUSPENDED', 'PENDING_CONFIRMATION', 'UNKNOWN') AND revoke_confirmed_at IS NULL` の行を `deleted_flag = 1` にすることは禁止する。さらに失効確認済み `REVOKED` 行を含む既存参照行も履歴台帳として保持し、`EXCEPTION_HOLD` は承認済み例外による退社gate判定であって削除認可には使用しない。
   - (c) **ライセンス割当履歴を論理削除してはならない**: `status = 'ACTIVE' OR released_date IS NULL` の行を `deleted_flag = 1` にすることは禁止する。ライセンス解放（`status = 'RELEASED'` かつ `released_date` 設定）後も既存割当行を履歴として保持する。
   - (d) **論理削除後も退社ブロッカー・期間排他・席数集計に影響しない**: MyBatis-Plus のグローバル論理削除フィルタ（`deleted_flag = 0`）により、上記 (a)〜(c) の条件違反によって論理削除された行が退社ゲート検査・期間重複チェック・`allocated_count` 計算から除外されることがないよう、論理削除前バリデーションで違反を事前に阻止する。
   - (e) **資産廃棄は削除ではなく DISPOSED 状態遷移である**: `m_asset` の `deleted_flag` は管理者の明示的な台帳整理のみに使用し、資産廃棄は必ず `status = DISPOSED` への状態遷移 + イベント記録で表現する。
   - (f) **貸与・account・licenseの歴史は終端状態で保持する**: `RETURNED`, `REVOKED`, `released_date IS NOT NULL` 等の終端状態に達した行は論理削除せず台帳上に残留させ、`t_asset_event` および `t_document_link` からの参照可能性を維持する。

---

### AS-R2 外部アカウント参照・秘密非保存・外部失効確認・ライセンス (Account & License Lifecycle)

1. THE 管理者 SHALL 外部システム（`m_external_account_system`: Google Workspace, GitHub, Slack, AWS, Microsoft 365, MDM等）の識別子、名称、システム種別、認可方式を管理できる。
2. THE 外部アカウント参照（`t_external_account_reference`） SHALL 外部システムID、外部アカウント識別子（メールアドレス、ユーザー名、または外部ID）、紐付け対象（要員IDまたはユーザーID）、権限レベル（`ADMIN`, `MEMBER`, `READONLY` 等）、ステータス（`ACTIVE: 有効`, `SUSPENDED: 停止中`, `REVOKED: 失効済`, `PENDING_CONFIRMATION: 失効確認待ち`, `UNKNOWN: 状態不明`, `EXCEPTION_HOLD: 例外保留`）、発行日、失効要求日時（`revoke_requested_at`）、失効確認日時（`revoke_confirmed_at`）、および確認者（`revoke_confirmed_by`）を保持する。
3. **秘密情報の絶対非保存 (Comprehensive No-Secrets Policy)**: THE システム SHALL 外部アカウントのパスワード、APIトークン、クライアントシークレット、リカバリーコード、暗号化キーを格納するテーブル列、DTOフィールド、ログ出力、例外メッセージ、監査payload、HTML/JS入力フォームを一切作成・受容・出力しない。外部認証はIdP/SSOおよびOAuth2 PKCE等の標準プロトコルに委ね、本システムはアカウントの「参照（Reference）」と「ライフサイクル状態（State）」のみを管理する。
4. **外部失効要求と確認の分離 & Recovery/Idempotency**: 外部プロバイダー（IdP/MDM/SaaS）に対する失効連携において、THE システム SHALL 失効要求（`REQUESTED`）と失効完了確認（`CONFIRMED`）を別個のタイムスタンプとステータスで管理する。失効要求時は冪等性キー（`idempotency_key`）を付与し、同一アカウントへの二重revokeおよび異なるkeyによる上書きを防止する。外部APIのタイムアウト、通信障害、5xx、または429は必ず `PENDING_CONFIRMATION` として永続化し、失効完了とみなさない。応答形式を分類できない場合だけ `UNKNOWN` として永続化する。いずれも指数バックオフによる定期ポーリング対象とし、`revoke_confirmed_at` が設定されるまで退社blockerを維持する。
5. **有償ライセンス席数管理 & CAS保護**: THE ライセンス管理（`m_license_plan`, `t_license_assignment`） SHALL プラン名、プロバイダー、総ライセンス数（`seat_limit`）、割当済数（`allocated_count`）、1席あたり費用、費用負担組織（`cost_center_id`）、および有効期限を管理する。割当時は `allocated_count < seat_limit` のCAS更新で原子保護し、上限 `-1 / = / +1` の境界を厳格に制御する。上限到達時の新規割当拒否、解放後の再割当成功、並行割当での席数完全性を保証する。

---

### AS-R3 棚卸し・紛失インシデント・退社ゲート連携 (Inventory, Incident & Resignation Gate)

1. THE 管理者 SHALL 定期（半期/年次）または臨時の棚卸し（`t_asset_inventory_run`）を開始できる。
2. THE 棚卸し明細（`t_asset_inventory_item`） SHALL 理論上の保管場所/貸与先（`expected`）と実地確認結果（`observed`）、確認者、確認日時、差異区分（`MATCH: 一致`, `DISCREPANCY: 差異あり`, `MISSING: 所在不明`, `UNREGISTERED: 未登録資産`）、差異理由、および是正措置を記録する。棚卸し確定（`COMPLETED`）後の明細更新および二重確定は厳格に拒否する。
3. **紛失インシデント追跡 (Lost Asset Incident)**: 資産の紛失が報告された場合、THE システム SHALL ステータスを `LOST` に変更し、インシデント起票日時、リモートワイプ実施/確認状態、警察届出番号、保険申請状況、および関連文書リンクを追跡可能にし、関係者へ緊急アラートを即時一斉配信する。
   - (a) `t_asset_lost_incident` を紛失資産ごとの正本台帳とし、起票日時・報告者・リモートワイプ（要求/実施/確認日時を含む）・警察届出番号・保険申請状態/日時を保持する。password/token/recovery code等の秘密は保持しない。
   - (b) `GET/PUT /api/assets/{assetId}/lost-incident` で認可済みの管理者/HR/マネージャーが対応状態を参照・更新できる。関連証跡は `DocumentLink.target_type = 'ASSET_LOST_INCIDENT'` で追記する。
   - (c) `LOST` 遷移、専用インシデント起票、緊急一斉通知のoutbox登録は同一トランザクションで完了し、同一インシデントの再送で重複通知・重複台帳を作らない。
4. **退社ゲート連携 (NF-01 Link Contract)**:
   - WHEN `engineer-lifecycle-workflow` (NF-01) の退社案件（`RESIGNATION`）が完了ゲート検証を行う場合、THE システム SHALL 対象要員に紐づく以下の **3大残存アイテム** を blocker として検出・報告する:
     - (a) 未返却貸与資産（`status = ACTIVE` または `actual_return_date IS NULL`）
     - (b) 未失効外部アカウント（`status IN ('ACTIVE', 'SUSPENDED', 'PENDING_CONFIRMATION', 'UNKNOWN')` かつ `revoke_confirmed_at IS NULL`）
     - (c) 未解放有償ライセンス（`status = 'ACTIVE'` または `released_date IS NULL`）
   - WHEN 上記の blocker が1件でも存在する場合、THE システム SHALL 退社ケースの通常完了を確実に阻止（Block）する。
   - WHEN 業務上の正当な理由で未返却/未失効/未解放のまま退社ケースを完了させる場合、THE システム SHALL 申請者単独の操作を禁止し、既存の承認エンジン（`ApprovalEngineService` / `RequestType = LIFECYCLE_EXCEPTION`）による例外申請（理由、是正期限、リスク所有者）の承認を必須とする。免除は対象の退社案件IDと `RESIGN_ASSET_RETURN` タスクIDに束縛して永続化し、承認アクションの実操作ユーザーを `approved_by` に記録する。要員単位の過去免除や申請者IDの代用は認めない。退社確定時は一括無効化トリガーを実行する。退社手続き中の担当変更・移管時も不変台帳へ排他記録する。

---

### AS-R4 認可スコープ・要員ポータル・通知・監査 (Scope, Portal, Notification & Audit)

1. **認可母集団 (Data Scope)**:
   - 管理者 (`ROLE_管理者`): 全法人・全部署の全資産・外部アカウント・ライセンス・棚卸しを管理・閲覧・更新・エクスポート可能。
   - マネージャー (`ROLE_マネージャー`): 管轄組織配下の要員に**現在貸与されている**資産および外部アカウントを閲覧可能。`owner_company_id` がNULLなら共有資産、非NULLなら管轄組織の `legal_entity_id` と一致する資産だけを許可する。未貸与資産は許可しない。
   - HR (`ROLE_HR`): 既存のHR role/DataScope（全要員の入退社関連データ）に準拠し、組織で追加制限をかけない。
   - 営業 (`ROLE_営業`): 既存 DataScope に準拠し、現任 `t_engineer_sales` の担当要員の**現在貸与資産**・アカウントを閲覧可能。担当要員が別法人所属でもowner法人による追加制限をかけない。担当要員が解決できない場合は0件（fail-closed）とする。未貸与資産は、owner_company_idがNULLの共有資産を含めて許可しない。
   - 要員本人 (`ROLE_要員`): `/my/assets` および `/api/my/assets/**` において、自分自身に現在貸与されている資産情報、返却期日、およびアカウント参照のみを閲覧可能。内部原価、他者の貸与情報、全社資産台帳を閲覧・推測できない。
2. **通知機能**: THE システム SHALL 資産返却期日の接近（7日前/3日前/当日）、返却期日超過（超過当日/毎週リマインド）、棚卸し期限接近、および外部アカウント失効未確認を、重複排除キー（Deduplication Key）を用いて対象者および管理担当者へ通知する。
3. **監査ログ**: THE システム SHALL 資産の新規登録、属性更新、貸与、返却、紛失、廃棄、アカウント発行/失効確認の全操作を `t_asset_event` および `t_audit_log` に記録する。

---

## 3. 非機能要件・受入基準 (Non-Functional Requirements & Acceptance Criteria)

1. **CR-01 認証・認可**: Spring Security の既存セッション/CSRF保護を適用し、画面・API・CSVエクスポート・通知・要員ポータルで同一のスコープ解決（`AssetScopeService`）を適用する。資産APIの一覧・詳細・イベント・貸与履歴・CSV、外部アカウント/ライセンスの担当者別参照、およびDocument APIの詳細・downloadは、取得後のJava filterではなくSQL母集団または対象IDの認可検査を先に行う。未認証・未紐付け・空の許可集合を全件扱いしない。
2. **CR-02 状態・競合・冪等性**:
   - 資産ステータス変更および貸与更新はバージョン楽観ロック（CAS）により保護する。
   - 同一資産への並行貸与リクエストに対して、マルチスレッド並行テストで確実に1件のみが成功し他方が409/業務例外で拒否されることを実証する。
   - 外部失効要求のclaimは `idempotency_key IS NULL` の一行更新で先着1件に限定し、同一keyの並行要求でprovider呼出しが1回だけになることをMySQLで実証する。確認poll中のprovider例外はアカウント単位で `PENDING_CONFIRMATION` とretry情報を永続化し、後続アカウントの処理を中断しない。
3. **CR-03 データ・マイグレーション**:
   - DDLは V1 baseline、Flyway増分マイグレーション（着手時点 latest+1、現行V129〜V133）、H2テストスキーマ（`sql/schema-asset-lifecycle-h2.sql`）、および Entity と完全に同期する。V132は退社案件・タスクFKと `t_asset_event` append-only triggerを含み、V133は紛失インシデント台帳のasset FK/uniqueを含む。MySQL smokeで列・unique/FK・trigger shapeを検証する。
   - 金額（取得価格、ライセンス単価等）は `BigDecimal`（円単位）で保持する。
4. **CR-04 監査・セキュリティ・PII**:
   - パスワード、トークン、秘密鍵等の秘密情報を一切永続化・ログ出力しない。
   - 静的コード解析およびログ出力テストにおいて、secret関連フィールドが存在しないことを自動検証する（Secret Scan Test）。
5. **CR-05 UI・レスポンシブ・国際化**:
   - 管理画面および要員ポータル画面はデスクトップおよび 390px モバイル表示に対応する。
   - メッセージ文言は `messages.properties`、`messages_en.properties`、`messages_zh_CN.properties`、`messages_ko.properties` の4バンドルすべてを完全に同期する。
6. **CR-06 テスト完全性**:
   - Fast テスト（H2）、MySQL実コンテナテスト、並行性テスト、および退社ゲート連携テストにおいてスキップ0件を達成する。
