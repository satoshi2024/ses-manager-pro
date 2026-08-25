# Production Acceptance Baseline: SES Manager Pro

| 項目 | 記録値 |
|---|---|
| **Git 凍結 Commit (Full SHA)** | `54edfd2b08f5fd61095b3a94c33bd5b981935c28` |
| **Git 凍結 Commit (Short SHA)** | `54edfd2b` |
| **Commit メッセージ** | `Merge pull request #83 from satoshi2024/fix/light-theme-content-contrast` |
| **Commit 日時 (JST)** | 2026-08-24 10:50:03 +0900 |
| **验收基线记录日時 (JST)** | 2026-08-25 00:35:00 +0900 |
| **验收负责人** | Production Acceptance Lead (Read-Only) |
| **代码只读保证** | `src/`, `pom.xml`, `.github/`, `scripts/`, `ops/`, `.kiro/specs/` 全量未修改 |

---

## 1. ワーキングツリー状態 (`git status --short`)

```
 M .kiro/specs/accounting-payment-integration/evidence/browser/
 M .kiro/specs/attendance-leave-overtime-compliance/evidence/browser-m/
 M .kiro/specs/dispatch-outsourcing-compliance-ledger/evidence/browser-g2/
 M .kiro/specs/order-acceptance-workflow/evidence/browser-r8/
 M .kiro/specs/staffing-capacity-planning/evidence/browser-m/
?? .kiro/reviews/production-acceptance/54edfd2b/
```

- **状態確認**: 変更ファイルは過去のブラウザテスト実行時に上書きされた `.kiro/specs/` 配下の証跡画像/ログのみであり、`src/` 配下のプロダクションコード・テストコード・SQL マイグレーション・設定ファイルは**完全に HEAD (54edfd2b) と一致・未変更**であることを確認。

---

## 2. 実行環境バージョン

| ツール / コンポーネント | バージョン | 備考 / CI 比較 |
|---|---|---|
| **OS** | Windows 11 (10.0.26200) | x86_64 |
| **Java (JDK)** | OpenJDK Temurin **17.0.20+8** | 本番バイトコードターゲット Java 17（CI は Temurin 21 実行） |
| **Maven** | Apache Maven **3.9.6** | 同梱バイナリ (`apache-maven-3.9.6/`) |
| **Node.js** | **v24.19.0** | `JsSyntaxCheckTest` 構文検査に使用 |
| **Docker** | **29.7.2** (Docker Desktop, API 1.55) | 稼働中（Testcontainers および Backup PITR 演習に利用） |
| **Google Chrome** | **151.0.7922.172** | `C:\Program Files\Google\Chrome\Application\chrome.exe` |
| **Git Bash** | `C:\Program Files\Git\bin\bash.exe` | Backup スクリプト実行に使用 |
| **タイムゾーン / ロケール** | `Asia/Tokyo` / `ja_JP` / `UTF-8` | JVM 引数にて固定化 |

---

## 3. Flyway マイグレーション資産一覧

- **マイグレーションファイル総数**: **112** ファイル (`src/main/resources/db/migration/V*.sql`)
- **ベースラインスキーマ**: `V1__create_tables.sql`（V1〜V9 の構造を包含する統合ベースライン）
- **No-Op 互換ファイル**: `V3`, `V8`（重複 ADD COLUMN による起動失敗を防止するため `SELECT 1;` で維持）
- **最新マイグレーション**: `V108_3__digital_invoice_send_unique.sql`
- **サブバージョン一覧**:
  - `V66_1__close_security_review_boundaries.sql`
  - `V74_1__crm_review_forward_fix.sql`
  - `V74_2__crm_source_cost_and_lead_search_keys.sql`
  - `V79_1__approval_route_decision_sources.sql`
  - `V102_1__reviewer_verification_events.sql` 〜 `V102_4__freee_company_boundary.sql`
  - `V103_1__contract_document_cloudsign_dispatch.sql`
  - `V104_1__portal_session.sql` 〜 `V104_4__portal_notification_preference.sql`
  - `V105_1__engineer_self_service_v2_forward_repair.sql` 〜 `V105_3__change_request_attachment_doc_type.sql`
  - `V106_1__accounting_integration_snapshot_and_slot.sql` 〜 `V106_2__accounting_company_boundary_forward_repair.sql`
  - `V107_1__jp_pint_digital_invoice_fixes.sql` 〜 `V107_3__jp_pint_digital_invoice_inbound_match.sql`
  - `V108_1__ai_feedback_proposal_trace_and_eval_menu.sql` 〜 `V108_3__digital_invoice_send_unique.sql`
- **設計上の永久欠番**: V19, V23, V41, V47, V59 (マルチテナント共有DB延期), V72, V82, V86〜V90, V92〜V97, V99〜V100

---

## 4. テストスイート構成と分類

| テスト分類 | クラス数 | 実行プロファイル / トリガー | 主な目的 |
|---|---|---|---|
| **Fast Suite (H2 / Unit / MVC)** | **422** | `mvn test` (既定) | 高速回帰フィードバック・ビジネスロジック検証 |
| **MySQL Suite (Testcontainers)** | **39** | `mvn test -Pmysql-tests` (`@Tag("mysql")`) | 実 MySQL 8.0 での Flyway マイグレーション・排他制御・並行性検証 |
| **Performance Regression** | **1** | `mvn test -Pperformance-tests` (`@Tag("performance")`) | JaCoCo なしでの 300人規模キャパシティ・レイテンシ・ヒープ計測 |
| **Browser Demo (Chrome CDP)** | **3** | `mvn test -Pbrowser-tests` (`@Tag("browser")`) | 実 Chrome ヘッドレスでの UI 描画・操作・証跡キャプチャ |
| **Backup Unit Suite** | **12** スクリプト (464 tests) | `ops/backup/tests/run-unit-tests.sh` | ピン留めコンテナ内でのバックアップ・リストア単体ロジック検証 |
| **Backup Integration Suite** | **1** (全行程 E2E) | `ops/backup/tests/run-integration.sh` | 5 コンテナ実トポロジでの MySQL PITR・二者承認・RPO/RTO 実測 |

---

## 5. 外部環境・署名受入ステータス

| 外部サービス / 機能 | 状態 | 理由 / 本番影響 |
|---|---|---|
| **freee 人事労務 API** | **BLOCKED** | 実 OAuth2 Sandbox / 開発者アカウント未提供（HFP-01 AC15 未達） |
| **CloudSign 電子契約 API** | **BLOCKED** | 実 Sandbox 認証情報・本番 Canary 運用承認未取得（HFP-02 G2/G5 未達） |
| **派遣元台帳 G2 法務受入** | **BLOCKED** | Phase B 人間・外部資格者レビュー証跡未取得（S10 / T066 未達） |
| **Peppol / JP PINT デジタルインボイス** | **BLOCKED** | Peppol サービスプロバイダー Sandbox 未接続（S16 PENDING_SANDBOX） |
| **AI プロバイダ外部送信** | **BLOCKED** | 外部 LLM プロバイダとの DPA (データ処理契約) 未締結（S17 G10 未達） |
| **本番 MySQL / 本番インフラ** | **BLOCKED** | 本番ホスト・認証情報へのアクセス権限なし（ステージング・ローカル検証のみ） |
