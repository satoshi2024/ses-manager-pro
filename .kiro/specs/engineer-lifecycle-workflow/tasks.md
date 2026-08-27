# Implementation Plan — 要員ライフサイクルワークフロー (入社・配属・異動・休職・復職・退社)

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: 各TaskはL1〜L3の定向test・直接回帰、Task MでL4全量を実行する。
> UI Taskは対象browser/MVCを実施し、全画面回帰はMへ集約する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> 本人向け画面（`/my/lifecycle`）は `is_engineer_visible = 1` のタスクのみをSQL条件で絞り込み、内部タスクを一切露出しない。
>
> **Migration**: 本機能の正式migrationは **V109**。着手時にmerge済み`db/migration`の最新（`V108_3`）を確認し、衝突していれば後発を上へ繰り上げる。

---

- [x] 0. Discovery/Gate & Inventory
  - **Objective**: 現行の要員、ユーザー、セッション、組織、営業担当、文書台帳、承認エンジンの経路を網羅的にインベントリ化し、DG-01（対象者、退社強制ブロック対象、証跡種別、承認境界）の決定を確定する。
  - **実装ガイダンス**: `2026-08-27-post-acceptance-traceability.md` のDG-01を確定し、requirements/design/tasksの整合を確認する。
  - **テスト要件**: スコープ境界・非目標・契約インターフェースの整合性確認。
  - **Demo**: DG-01決定事項とインベントリ台帳の確認。

- [x] F1. テンプレート・案件・タスク・イベント DDLと状態競合
  - **Objective**: ライフサイクルテンプレート（`m_lifecycle_template`, `m_lifecycle_template_task`, `m_lifecycle_template_task_dep`）、案件（`t_lifecycle_case`）、タスク（`t_lifecycle_task`, `t_lifecycle_task_dep`）、証跡リンク（`t_lifecycle_evidence_link`）、およびイベント台帳（`t_lifecycle_event`）をDBへ構築し、版番号CASによる状態遷移排他制御を確立する。
  - **実装ガイダンス**: **V109** / `V1__create_tables.sql` / H2 (`sql/schema-lifecycle-workflow-h2.sql`) / MySQL smoke、MyBatis-Plusエンティティ・Mapper群。
    テンプレート版の不変性（進行中案件が改定版に影響されないこと）をDB/Entityレベルで担保。
  - **テスト要件**: L1〜L3。テーブル作成、CRUD、UNIQUE制約、`version` CASによる二重更新・状態遷移競合テスト、MySQL Flyway smoke test。
  - **Demo**: 空DBおよび既存DBでのV109マイグレーション成功、エンティティのCAS競合検知確認。

- [x] F2. ドメインサービス・担当解決・スコープ・退社ゲート
  - **Objective**: 案件・タスクのライフサイクル管理、担当者自動解決（`LifecycleAssigneeResolver`）、DAG循環検出（Cycle Detection）、認可スコープ解決（`LifecycleScopeService`）、および退社ゲート（`ResignationGateChecker`）を実装する。
  - **実装ガイダンス**: 担当解決不能時または循環依存時の原子的一括ロールバック（Fail-Closed）。
    退社ゲートにおけるアカウント無効化、セッション失効、組織閉鎖、営業担当引継ぎ、未精算チェックのシステム検証。
  - **テスト要件**: L1〜L3。6種ライフサイクルの起票・タスク生成、担当者解決（正常・異常系）、循環依存エラー、未完了阻害タスク存在時の案件完了ブロック、退社ゲート検証の全パス/ブロックテスト。
  - **Demo**: 入社および退社案件を起票し、阻害タスク未完了時に完了が拒否されること、全タスク完了後に正常完了できることをJUnit/Serviceで確認。

- [x] A1. 管理・HR UIおよび要員詳細カード
  - **Objective**: 管理者およびHR向けにライフサイクルテンプレート管理画面（`/lifecycle/templates`）、案件一覧・進捗詳細画面（`/lifecycle/cases`, `/lifecycle/cases/{id}`）、および要員詳細画面の「ライフサイクル」カードを実装する。
  - **実装ガイダンス**: 一覧検索・ステータス絞り込み、タスク着手/完了モーダル、証跡確認、手動担当変更、案件完了/保留/取消操作。
    390pxモバイル対応、二重送信防止。
  - **テスト要件**: L2〜L3。Controller MVCテスト、管理者/HR/マネージャー/営業の認可・スコープテスト（403/404検証）、CSRF保護、i18n 4バンドル一致。
  - **Demo**: ブラウザ上でテンプレート作成 → 案件起票 → タスク進捗更新 → 案件完了までの一連のフローを実演。

- [x] A2. 要員本人画面および公開境界制御（`/my/lifecycle`）
  - **Objective**: 要員ポータル向けに「マイライフサイクル」画面（`/my/lifecycle`）を構築し、自身に関連する公開タスクのみの表示・対応・完了報告（自己申告・提出物リンク）を可能にする。
  - **実装ガイダンス**: `is_engineer_visible = 0` の社内タスク（セキュリティ審査・口座確認・給与設定等）を本人から不可視にする（403/フィルタリング）。
    自己申告・提出物アップロード・完了報告（`SELF_DECLARATION`, `DOCUMENT_LINK`）。
  - **テスト要件**: L4〜L6。要員ロールでの自案件のみアクセス可・他要員拒否（403）、社内専用タスクの非公開確認、本人タスク完了テスト。
  - **Demo**: 要員アカウントでログインし、自身のタスクのみが表示され社内タスクが不可視であること、提出完了できることを確認。

- [x] B1. 通知・SLAスケジューラ・統一承認エンジン連携
  - **Objective**: タスク期日接近・超過・阻害・完了の重複抑止付き通知（`t_notification_outbox` / `NotificationService` 連携）、期日監視バッチ（`LifecycleSlaScheduler`）、および阻害タスクの例外免除申請アダプタ（`LifecycleExceptionApprovalAdapter`）の完全統合を実装する。
  - **実装ガイダンス**: 期日2日前・当日・超過時の通知生成、重複通知防止（同一タスク・同一状態での多重発行抑止）。
    `ApprovalEngine` の例外免除（`LIFECYCLE_EXCEPTION`）完了時の自動タスク免除（`waiveTask`）。
  - **テスト要件**: L1〜L4。期日超過検知、通知Outbox生成、ApprovalEngineとの連携ライフサイクル免除E2Eテスト。
  - **Demo**: 期日超過タスクの通知が生成され、例外申請承認によりタスクが免除され案件が完了へ進むことを確認。申請 → HR承認 → タスクがWAIVEDとなり案件が完了可能になる一連の動作を確認。

- [ ] B2. 証跡・文書台帳連携・運用手順・補償
  - **Objective**: タスク完了時の証跡として法定文書台帳（`DocumentService` / `t_document`）との原子的な紐付けを行い、案件取消時の補償処理および運用ランブックを整備する。
  - **実装ガイダンス**: 証跡ファイルのウイルススキャン状態検証（`FileScanner.CLEAN` 必須）、スキャン未完了時の fail-closed 制御。
  - **テスト要件**: L2〜L3。文書リンク登録・整合性検証、未スキャン文書の拒否、案件取消時のタスク状態補償。
  - **Demo**: 証跡文書を添付してタスク完了、文書台帳から該当文書の整合性を確認。

- [ ] M. 統合ゲート・退社障害訓練・独立Review引き渡し
  - **Objective**: fast test、MySQL実コンテナ（Flyway smoke）、および退社障害訓練（セッション失効・アカウントロック漏れ防止）を含む全テストゲートを実行し、スキップ0件を検証して独立Reviewへ引き渡す。
  - **テスト要件**: L4全量。`mvn test`、`mvn test -Pmysql-tests`、デスクトップ/390px画面表示、退社時アクセス遮断ストレステスト、`git diff --check`、P0/P1欠陥0件。
  - **Demo**: 全ゲート合格証拠、退社時アクセス遮断シミュレーションのログ、および変更ファイル一覧を報告。
