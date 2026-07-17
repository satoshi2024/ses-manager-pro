# `.kiro/specs/` 一覧・複数AI並行着手ディスパッチ表

このファイルは、複数のAIセッションに`.kiro/specs/`配下のspecを同時並行で割り振るための一覧表。各行の「一言で着手」をそのままAIへの指示文として渡せば、`CLAUDE.md`の規約（spec駆動タスクワークフロー）に沿って着手できる。

**並行実行の原則**: 別々のspecディレクトリを別々のAIセッションに割り当てれば、担当ファイルが交差しないよう設計済みのため安全に同時実行できる。同一spec内でレーン(A/B/C/D等)が分かれているものは、レーン単位で別AIに割り振ることも可能（「一言で着手」列に個別レーンの例文も記載）。

## 0. 前提の訂正（既に実装済み・spec化済みの機能）

以前の分析で「未実装」と誤って挙げていたが、調査の結果**実装済み**と判明したもの。新規spec作成は不要。今後この領域を触るAIへは、下記の注意点を申し送りとして渡すこと。

| 機能 | 実装箇所 | 注意点 |
|---|---|---|
| 精算幅（清算幅）計価 | `entity/Contract.java`の`settlementHoursMin`/`settlementHoursMax` + `service/billing/SettlementCalculator.java` | 下限割れ/上限超過の按分計算は「万円単位の単価×時間」で端数切捨て。単価を`sellingPrice`(売上)と`costPrice`(原価)の両方に同一ロジックを適用しているため、**按分計算式を変更する場合は売上・原価の両方に影響する**ことを忘れないこと。`hoursMin`/`hoursMax`がnullまたは0以下の場合は固定額（按分なし）という分岐も見落としやすい。 |
| 契約更新アラート（契約終了30日前通知） | `.kiro/specs/notification-center`の`CONTRACT_END`通知（`NotificationGenerateService`、日次バッチ`NotificationScheduler`） | 判定条件は`status='稼動中'`かつ`end_date`が[今日, +30日]。**後続契約(`renewedFromContractId`)の有無チェック**は当初未実装だったが `lifecycle-status-consistency` S4 で**対応済み**（`renewed_from_contract_id`を持つ更新ドラフトが存在する契約は通知除外）。`engineer-availability-visualization`の「まもなく空き」判定も同一の`renewed_from_contract_id`基準を共有すること。 |
| インボイス制度対応（登録番号・税率区分表示） | `.kiro/specs/invoice-compliance`（全タスク完了済み） | 登録番号は`m_system_config`に未設定なら印字を省略する仕様（免税事業者対応）。新しい請求書関連機能を追加する際、この「未設定時は省略」という分岐を壊さないこと。 |
| 要員担当営業・営業成績/インセンティブ管理 | `.kiro/specs/engineer-sales-commission`（全レーン完了済み） | 営業成績は契約口径の成約件数と提案口径の成約率が並ぶため、合計や率の口径を混同しないこと。論理削除済み営業ユーザーの過去契約も表示対象に含める。 |

## 1. 新規追加spec（今回作成、実装未着手）

| spec | 状態 | レーン構成 | 担当ファイル範囲(概要) | 一言で着手 |
|---|---|---|---|---|
| `money-flow-consistency` | **実装完了（2026-07-16、全レーンA/C/B/D/M）** | A/C並行→B→D→M | 契約UI(`contract.js`等)・集計(`Dashboard`/`Export`/`SalesPerformance`共通口径`MonthlyRevenueCalcService`)・勤怠BP(`WorkRecord*`)・`InvoiceMapper`・請求税率保存(`V27`) | 完了。R1〜R8を実装（契約編集/状態遷移UI・解約日確定・集計口径統一・reopen手動BP保護・営業成績未帰属行・fraction_rule注記・請求税率固定）。`mvn test` 全緑（smoke testはDocker必要でskip）。 |
| `lifecycle-status-consistency` | **実装完了（2026-07-17、全レーンA/B/C＋M）** | A→C、B並行 | 営業/ユーザーライフサイクル(`UserApiController`/`EngineerSales*`)・通知(`NotificationGenerateService`/`NotificationLinks`)・要員状態/勤怠(`Engineer*`/`ContractServiceImpl.removeById`/`WorkRecordServiceImpl.saveHours`) | 完了。S1退職主担当フォールバック＋担当残存ガード・S2要員削除時の割当解放・S3通知リンク修正＋ルート整合テスト・S4更新ドラフト連動・S5要員ステータス編集ガード・S6契約削除時releaseIfIdle・S7工数の期間/状態検証。`mvn test` 全緑（486件、smoke test/PDFはskip）。 |
| `multi-tier-bp-management` | 未着手 | F→A/B→M | `BpPayment`関連のみ | `.kiro/specs/multi-tier-bp-management/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `skill-sheet-generation` | 未着手 | 逐次1〜4 | 新規`SkillSheetGenerator`等のみ、既存エンティティ非変更 | `.kiro/specs/skill-sheet-generation/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `engineer-availability-visualization` | 未着手 | 逐次1〜3 | 新規`AnalyticsApiController`エンドポイント追加のみ | `.kiro/specs/engineer-availability-visualization/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `recruiting-pipeline` | 未着手 | F→A/B→M1 | 完全新規テーブル(`t_candidate`等) | `.kiro/specs/recruiting-pipeline/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `webhook-notifications` | 未着手 | 逐次1〜3 | `WebhookNotifier`新規+既存への1行フック | `.kiro/specs/webhook-notifications/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `business-logic-integrity-hardening` | 対応中 | A/B/C/D→E | Flyway・金額口径・契約/提案/候補者状態・BP支払権限 | `.kiro/specs/business-logic-integrity-hardening/tasks.md に従い、割り当てられたレーンだけを実装してください。完了したタスクは - [x] にチェックしてください。` |
| `payroll-management` | **実装中（freee API連携）** | OAuth/連携→画面→テスト | `Freee*`・`payroll`関連 | `.kiro/specs/payroll-management/tasks.md に従ってfreee連携を実装し、完了タスクをチェックする。` |
| `contract-document-esign` | **実装中（CloudSign）** | テンプレート→PDF→署名連携 | 契約書テンプレート・文書状態 | `.kiro/specs/contract-document-esign/tasks.md に従って実装し、CloudSign adapterとテストを完了する。` |
| `database-backup-recovery` | **実装中（Docker/Linux）** | バックアップ→PITR→復元演習 | `ops/backup`・運用文書 | `.kiro/specs/database-backup-recovery/tasks.md に従って実装し、復元演習を完了する。` |

## 1.5 顧客機能ロードマップ（全7件採用・spec化済み・実装未着手）

**実行順・spec間競合・マイグレーション採番の正は `customer-feature-proposals/README.md` の
「実装全体計画」**（Wave 1: P2∥P4∥P5 → Wave 2: P3∥P1 → Wave 3: P6 → Wave 4: P7単独）。

| spec | 提案 | 規模 | 一言で着手 |
|---|---|---|---|
| `ar-management` | P2 債権管理（入金消込・エイジング・督促） | M | `.kiro/specs/ar-management/tasks.md に従い、割り当てられたレーンだけを実装してください。完了したタスクは - [x] にチェックしてください。` |
| `quotation-management` | P4 見積書発行 | S〜M | `.kiro/specs/quotation-management/tasks.md に従い、割り当てられたレーンだけを実装してください。完了したタスクは - [x] にチェックしてください。` |
| `revenue-forecast` | P5 売上着地予測 | S | `.kiro/specs/revenue-forecast/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `monthly-closing-checklist` | P3 月次締めチェックリスト（※ar-management レーンA 後） | S〜M | `.kiro/specs/monthly-closing-checklist/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `engineer-self-service-timesheet` | P1 作業報告書・要員セルフサービス勤怠 | L | `.kiro/specs/engineer-self-service-timesheet/tasks.md に従い、割り当てられたレーンだけを実装してください。完了したタスクは - [x] にチェックしてください。` |
| `contract-price-history` | P6 単価改定履歴（※P1 完了後） | M〜L | `.kiro/specs/contract-price-history/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `data-scope-permission` | P7 データスコープ権限（※最後尾・他specと並行禁止） | M | `.kiro/specs/data-scope-permission/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |

## 2. 既存の未完了spec（再開のみ、ドキュメント追加不要）

| spec | 状態 | 一言で着手 |
|---|---|---|
| `dashboard-improvements` | 0/12（損益分析API・財年セレクタ・印刷CSS未実装） | `.kiro/specs/dashboard-improvements/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |
| `rule-based-matching` | 0/5（`MatchScoreCalculator`等未実装。前提: `engineer-skill-career`完了が必要） | `.kiro/specs/rule-based-matching/tasks.md に従って実装してください。完了したタスクは - [x] にチェックしてください。` |

## 3. 同時並行の組み合わせ例（最大8体まで同時実行可能）

ファイル競合なしに同時に走らせられる組み合わせ:
1. `multi-tier-bp-management`
2. `skill-sheet-generation`
3. `engineer-availability-visualization`
4. `recruiting-pipeline`
5. `webhook-notifications`
6. `dashboard-improvements`
7. `rule-based-matching`（ただし`engineer-skill-career`が未完了なら前提確認が先）

## 4. 新規specどうしのファイル競合チェック（確認済み）

以下、5つの新規specの担当ファイルをクロスチェックした結果、重複なし:
- `multi-tier-bp-management`: `BpPayment`関連のみ
- `skill-sheet-generation`: `SkillSheetGenerator`等の新規ファイルのみ
- `engineer-availability-visualization`: `AnalyticsApiController`への追記のみ(既存メソッド非変更)
- `recruiting-pipeline`: `Candidate`関連の完全新規テーブル・ファイルのみ
- `webhook-notifications`: `WebhookNotifier`新規+`NotificationServiceImpl`への1行フックのみ

いずれも既存の完了済みspec（`notification-center`, `invoice-compliance`等）のコードには非破壊的な追記のみで、相互の担当ファイルも交差しない。
