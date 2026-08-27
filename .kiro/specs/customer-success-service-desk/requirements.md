# Requirements — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

## 1. 概要・目的

親: `.kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md` (NF-02)
要件・設計基線: `.kiro/roadmap/2026-08-27-post-acceptance-requirements-design.md` (§3 NF-02, CR-01〜CR-06)
決定台帳: `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md` (DG-02)
プラットフォーム不変条件: `.kiro/specs/customer-product-expansion-2026/platform-invariants.md`

契約更新の直前だけでなく、日常の問い合わせ、クレーム、定例会(QBR)、満足度(CSAT)、未解決課題を体系的に蓄積し、SLA遵守を管理する。
また、顧客ヘルスを「担当者の感覚」ではなく、未解決課題、SLA違反、請求滞納、満足度、更新意向から説明可能なルールベーススコアとして算出し、契約更新カレンダーと連携する。

---

## 2. 業務要件と受入基準 (Acceptance Criteria)

### CS-R1: 問い合わせ・課題管理 (Service Request)
1. **起票**: 内部利用者（営業、マネージャー、管理者）および顧客ポータル利用者は、顧客・契約・案件・要員に紐づく問い合わせ（Service Request）を起票できる。
2. **必須属性**: リクエスト番号（一意採番 `REQ-YYYYMM-XXXX`）、顧客ID、契約ID（任意）、案件ID（任意）、要員ID（任意）、カテゴリ（契約・請求・勤怠・品質・システム・その他）、優先度（P0:緊急、P1:高、P2:中、P3:低）、受付経路（ポータル、メール、電話、定例会、内部）、件名、本文、担当営業/CS担当者、ステータス。
3. **ステータス遷移**: `RECEIVED`(受付) → `IN_PROGRESS`(対応中) → `WAITING_CUSTOMER`(顧客確認待ち) → `RESOLVED`(解決) → `CLOSED`(終了)。
4. **再オープン**: 解決/終了後の問い合わせに対し、追加問い合わせや未解決の申し立てがあった場合、`REOPENED` → `IN_PROGRESS` へ再オープンできる。再オープン時は履歴と新しいSLAラウンドを作成し、過去のSLA結果を上書きしない。
5. **コメントと内部メモの完全分離**:
   - コメントは `PORTAL_VISIBLE`（ポータル公開）と `INTERNAL`（内部メモ）の可視性区分を持つ。
   - `INTERNAL` コメントは、DBクエリ境界およびDTOレベルでポータル利用者から完全に秘匿され、APIレスポンスに含まれない（CSS非表示等による隠蔽は禁止）。

### CS-R2: SLA管理と営業時間計算 (SLA Policy & Engine)
1. **SLAポリシー**: 優先度（P0〜P3）ごとに初回応答目標時間（Response Target Hours）と解決目標時間（Resolve Target Hours）を定義する。
2. **営業時間・休日計算**:
   - SLA期限（初回応答期限・解決期限）は、標準営業時間（09:00〜18:00）、土日祝日（`WorkCalendarDay` / 休日カレンダー）、タイムゾーン（`Asia/Tokyo`）を考慮して厳密に計算する（単純な24時間加算は禁止）。
3. **一時停止 (Pause) と再開 (Resume)**:
   - 問い合わせが `WAITING_CUSTOMER`（顧客確認待ち）状態の間はSLA時計を一時停止（Pause）し、対応再開時に停止期間（分単位）をSLA期限に繰り延べる（SLA Clockへ記録）。
4. **SLA超過監視と通知**:
   - SLA期限の事前予告（例: 期限1時間前）、期限超過（Breach）発生時、および未解決継続時に、担当者およびマネージャーへ通知（`t_notification`）を発行する。
   - 通知は重複抑止キー（Dedupe Key: `sla:request:{id}:round:{round}:type:{type}`）により二重通知を防ぐ。

### CS-R3: CSAT (顧客満足度調査) & 定例会/QBR記録
1. **CSAT回答**:
   - 問い合わせが解決（`RESOLVED` または `CLOSED`）した際、顧客ポータルから1回限り5段階評価スコアとフィードバックコメントを投稿できる。
   - 匿名公開URL方式ではなく、ポータル認証セッション＋自社リクエスト所属検証（Customer Scope）で認可する。
   - DB UNIQUE制約およびCASにより二重回答を防止する。
2. **定例会・QBR (Quarterly Business Review) 記録**:
   - 顧客ごとの定例会/QBRの日時、参加者、議題、討議内容、決定事項、次回日程を記録できる。
   - QBRに紐づくアクションアイテム（Action Item: タイトル、説明、担当者、期日、状態）を管理できる。

### CS-R4: 顧客ヘルススコア・要因分析 (Customer Health Score)
1. **説明可能なルールベーススコア**:
   - 顧客ヘルススコア（0〜100点）およびステータス（`HEALTHY`: 80点以上、`WARNING`: 50〜79点、`CRITICAL`: 49点以下）を算出する。
   - スコア算出要因（未解決P0/P1件数、過去30日SLA違反件数、平均CSATスコア、売掛金滞納フラグ、定期接触なし日数等）と各因子の配点、欠損入力（Missing Inputs）を透明に説明できる。
2. **契約更新カレンダー連携**:
   - 契約更新カレンダー（`/contracts/renewal-calendar`）上で、対象顧客のヘルスステータス、未解決P0/P1件数、直近CSATを表示し、更新交渉の事前判断材料を提供する。
   - **不変条件**: ヘルススコアは参考情報であり、契約更新ステータス（更新決定や解約）を自動確定・自動変更しない。

### CS-R5: 外部ポータル統合とスコープ安全 (Portal Scope & Security)
1. **顧客ポータル起票・閲覧・返信**:
   - 顧客ポータル利用者は、自社（`customerId`）に紐づく問い合わせのみ一覧表示・詳細閲覧・新規起票・返信コメント投稿・添付ファイルダウンロードができる。
2. **完全なマルチテナント/顧客スコープ分離 (IDOR拒否)**:
   - 顧客Aのポータル利用者が、顧客Bの問い合わせID、添付ファイルURL、コメント、CSAT、カウント、通知リンクへアクセスした場合、存在を推測させない一貫した拒否（404 Not Found または 403 Forbidden）を返す。
3. **添付ファイルセキュリティ**:
   - 添付ファイルのアップロード・ダウンロードは `DocumentService` / `DocumentStorage` と連携し、`FileScopeValidationService` および `FileReferenceProvider` に登録して正当な顧客スコープ内でのみダウンロード可能とする。

---

## 3. 非目標 (Out of Scope)
1. 汎用ITSMツール（Jira Service Management / Zendesk）の全機能の模倣。
2. AIチャットボットによる自動問い合わせ解決や自動クローズ。
3. AIの感情分析のみに基づく自動解約判定や顧客危険判定。
4. 外部メールサーバー（IMAP/POP3）からの自動チケットインポート（別連携フェーズ）。
5. 契約更新ステータスの自動更新・自動失注処理。
