# SES Manager Pro 機能拡張ロードマップ（2026H2）— FR-01〜FR-11

- 作成日: 2026-07-24
- 位置づけ: 発注者が全採用した11案の**総括ロードマップ**。各案の「狙い／現状課題／対応方法（どう作るか）／期待効果／再利用資産／概算規模・優先度／依存」を1枚に整理する。個々の案は着手時に `.kiro/specs/<name>/`（requirements/design/tasks）へ展開する。コードは書かない（実装は別担当）。
- 方針: **既存資産を最大限再利用**し、新規基盤を最小化する。特に直近の `skillsheet-ingestion`（取込基盤）と `AiTextService`（AI基盤）を横展開の土台にする。

## 0. 全体像

| グループ | 狙い | 案 |
|---|---|---|
| A. 稼がせる | Bench削減・提案勝率 | FR-01 案件メール取込 / FR-02 AI提案生成＋実マッチング / FR-03 重複提案ガード / FR-04 スキルシート匿名化・多形式 |
| B. 取りこぼさない | 更新・現金流 | FR-05 資金繰り予測 / FR-06 契約更新カレンダー / FR-07 将来稼働率・Bench予測 |
| C. 自動化 | 手入力削減 | FR-08 要員空き状況メール取込 / FR-09 入金消込の半自動化 |
| D. 守る | 合規・定着 | FR-10 偽装請負・多重派遣チェック / FR-11 要員フォロー・定着管理 |

### 横断的な先行投資（強く推奨）: 「取込基盤」の一般化

FR-01・FR-08・既存 `skillsheet-ingestion` は**同一パターン**（受信/アップロード → テキスト抽出 → AIでJSON構造化 → 人が1回レビュー → エンティティ生成）である。`skillsheet-ingestion` の実装（`ResumeTextExtractor`／`AiTextService`／`FileReferenceProvider`／取込ジョブ状態機械 `取込待ち→抽出中→要確認→確定済/却下/失敗`）を、**取込種別をパラメータ化した汎用基盤**（`IngestionJob` + `IngestionParser<T>`）へ小さくリファクタしておくと、FR-01/FR-08 が「パーサ＋レビュー画面だけ」で載る。先にこれを行うと後続3案のコストが半減する。

### 推奨フェーズ

- **Phase 1（即効・営業／基盤再利用）**: FR-01, FR-02, FR-03, FR-04
- **Phase 2（経営・現金）**: FR-05, FR-06, FR-07
- **Phase 3（自動化・守り）**: FR-08, FR-09, FR-10, FR-11

### 依存関係

- FR-01 / FR-08 → 「取込基盤」（`skillsheet-ingestion` 由来）を再利用。先に一般化推奨。
- FR-02 → `AiTextService`（`ai.provider=gemini` の実接続。※現状mock）＋ 既存 `MatchScoreCalculator`。実AI接続で価値が出る（A8-01の基盤が前提）。
- FR-05 / FR-09 → 既存 billing サービス（`MonthlyRevenueCalcService` 等）＋ `FreeeIntegrationService`。
- FR-06 / FR-11 → 既存 `NotificationService` / `WebhookNotifier`。

---

## A. 稼がせる（Bench削減・提案勝率）

### FR-01 【最優先】案件メール自動取込

- 狙い: パートナーMLから日々大量に届く案件メールを、手入力ゼロで `t_project` 起票→Benchへ即マッチ提案。営業の最大の時間泥棒を消す。
- 現状課題: 案件情報はすべて人が案件登録画面へ転記。取りこぼし・入力遅延で提案が遅れる。
- 対応方法:
  - 入力経路は2段構え。(a) MVP: メール本文の**貼り付け／.emlアップロード**、(b) 拡張: IMAP/メール受信の定期取込（別ジョブ）。
  - 「取込基盤」を再利用。新パーサ `ProjectParseService`（`AiTextService` で案件JSON抽出：案件名・必要スキル・単価幅・勤務地・リモート可否・開始/終了・商流・募集人数・元請/エンド）＋ mock パーサ（正規表現）。
  - 取込ジョブ `t_project_ingestion`（`skillsheet-ingestion` の `t_resume_ingestion` と同型：status・parsed_json・converted_project_id）。レビュー画面で確認・修正 → 確定で `Project` 生成。確定時に既存 `AiMatchingService`（FR-02後は実AI）で**Bench要員へのマッチ候補**を即提示。
  - 抽出テキストは既存 `ResumeTextExtractor` を一般化して流用。孤児清理は `FileReferenceProvider` を1つ追加。
- 期待効果: 案件登録の工数を「1件数分の手入力」→「レビュー数十秒」に。案件母集団が増え、マッチング機会が構造的に増える。
- 再利用: `skillsheet-ingestion` 一式、`AiTextService`、`Project` 既存CRUD。
- 規模/優先度: 中／★★★（Phase 1・基盤再利用で低コスト）。

### FR-02 AI提案文生成 ＋ 実マッチング接続

- 狙い: 要員×案件のマッチ理由・セールスポイント・**提案メール下書き**をAIで自動生成。営業の「書く時間」を消す。
- 現状課題: `AiMatchingServiceImpl` はモック（"大手金融…"固定）。`Proposal` には `aiMatchScore`／`matchReason`／`proposalEmailText` の**受け皿カラムが既にあるのに未活用**。
- 対応方法:
  - `AiMatchingService` の実装を `ai.provider=gemini` 経路で本接続（既存 `MatchScoreCalculator` のルールスコアをAIプロンプトの根拠として渡し、理由文とセールスポイントを生成）。
  - 提案生成API（例 `/api/ai/proposal-draft`）: engineerId×projectId から `AiTextService` で提案メール下書き＋マッチ理由を生成し、`Proposal.proposalEmailText`／`matchReason`／`aiMatchScore` に保存。カンバン（`proposal-kanban.js`）から起動。
  - 単価単位・金額は A7-24/A8-01 の共通フォーマッタに従う（プロンプトの単位ズレを再発させない）。PII保護（氏名はイニシャル＝`Engineer.initialName` を渡す）。
- 期待効果: 提案作成時間を大幅短縮。属人的だった提案品質が平準化。既存の空きカラムが活き、"AI搭載SES"として差別化。
- 再利用: `AiTextService`（A8-01基盤）、`MatchScoreCalculator`、`Proposal` 既存カラム、既存カンバンUI。
- 規模/優先度: 中／★★★（Phase 1。AI基盤の実接続が前提）。

### FR-03 重複提案ガード（提案先ダブり防止）

- 狙い: 同一要員を同一客先へ二重提案する事故（SESの信用問題）を防ぐ。
- 現状課題: `Proposal` に engineerId×projectId はあるが、「この要員は◯◯社に提案済み」の横串チェック・可視化が無い。
- 対応方法:
  - 提案作成時に「同一 engineer × 同一 customer（案件の customer 経由）で、`status` がアクティブ（提案中/面談等、成約/見送り以外）の提案」を検出し警告（原則ブロックはせず確認モーダル）。
  - 要員詳細に「提案履歴（提案先・時期・結果）」タブを追加（`Proposal.proposedAt/closedAt/status` を集計）。
  - 判定ロジックは1メソッドに集約（一覧フィルタ・提案作成・要員詳細で共用）。
- 期待効果: 客先での重複提案による失注・信用毀損を回避。営業間の情報分断を解消。
- 再利用: `Proposal` エンティティ、要員詳細画面。
- 規模/優先度: 小／★★（Phase 1。安価だが効果大）。

### FR-04 スキルシート匿名化 ＋ 提案用の多形式出力

- 狙い: 客先へ出す提案用スキルシートを、個人情報を伏せて（イニシャル化）客先指定形式で出力。
- 現状課題: `SkillSheetGenerator` は PDF＋Excel 生成済みだが、氏名がそのまま。提案時に匿名化・様式選択ができない。
- 対応方法:
  - 生成オプションに「匿名化」を追加（`Engineer.initialName`〔既存〕を用い氏名/生年月日/連絡先を伏字化）。
  - 様式テンプレートの選択（自社標準／簡易／客先様式）を `m_system_config` またはテンプレートテーブルで管理。Excel様式は既存 `generateExcel` を拡張。
  - 提案（FR-02）・カンバンから「匿名スキルシートを出力」を起動し、`Proposal.skillSheetPath` に保存。
- 期待効果: 提案のたびの手作業（氏名消し・様式変換）を廃し、情報漏えいリスクを下げる。
- 再利用: `SkillSheetGenerator`（PDF/Excel）、`Engineer.initialName`、`Proposal.skillSheetPath`。
- 規模/優先度: 小〜中／★★（Phase 1）。

---

## B. 取りこぼさない（更新・現金流）

### FR-05 【最優先】資金繰り予測（キャッシュフロー）

- 狙い: 「入金（客先請求）」と「支払（BP支払＋給与）」のタイミング差を月次で可視化し、資金ショート月を先に警告。SES特有の「払うのが先・貰うのが後」構造への必須装備。
- 現状課題: `revenue-forecast` は売上予測はあるが、**支払サイド・入出金タイミングのCFは無い**。
- 対応方法:
  - 新サービス `CashFlowForecastService`：
    - 入: `t_invoice`（`due_date`／`total`／`status`）を入金予定として集計（未入金は due_date 月へ）。
    - 出: `t_bp_payment`（BP支払予定）＋ 給与（`FreeeEmployeeLink`／payroll）＋ 既知の固定費（`m_system_config` で月額登録）。
    - 月次で「入金−支払＝ネット」「累計残高見込み」を算出。共通口径は既存 `MonthlyRevenueCalcService` の売上判定に寄せる。
  - 画面: ダッシュボードに CF タブ（Chart.js の棒＋折れ線）。マイナス月・残高警戒ラインを強調。CSV出力。
  - 通知: 残高が閾値を割る見込み月を `NotificationService` で管理者へ。
- 期待効果: 経営者が最も欲しい「いつ資金が足りなくなるか」を既存データだけで提示。黒字倒産リスクの早期検知。
- 再利用: `t_invoice`／`t_bp_payment`／payroll、`MonthlyRevenueCalcService`、Dashboard＋Chart.js、`NotificationService`。
- 規模/優先度: 中／★★★（Phase 2。新規テーブル不要）。

### FR-06 契約更新カレンダー ＋ エスカレーション

- 狙い: 全契約の終了/更新期限を俯瞰し、未アクションを上長へエスカレーションして**更新漏れ＝失注**を物理的に防ぐ。
- 現状課題: 自動更新ドラフト生成はあるが、期限の**俯瞰ビュー**と未対応のエスカレーションが無い。
- 対応方法:
  - `Contract.endDate`／`autoRenew`／`status` を月/週カレンダー表示（既存ガント資産の考え方を流用、期間フィルタ付き＝A7-22の恒久対応と整合）。
  - エスカレーション: 期限 N日前（`m_system_config` で段階設定）に未対応（更新ドラフト未確定/未連絡）なら担当営業→上長の順で `NotificationService`／`WebhookNotifier` 通知。
- 期待効果: 契約切れの見逃しゼロ化。更新交渉の前倒し。
- 再利用: `Contract`、`ContractRenewalService`、`NotificationService`、カレンダーUI。
- 規模/優先度: 中／★★（Phase 2）。

### FR-07 将来稼働率・Bench予測

- 狙い: 契約終了日から1〜3ヶ月先の稼働率／Bench発生を予測し、「9月に5名ロールオフ→今から動け」を先出し。
- 現状課題: ダッシュボードは当月の稼働率・Bench数はあるが、**将来のロールオフ予測**が無い。
- 対応方法:
  - `DashboardServiceImpl` の稼働率算定を再利用し、`Contract.endDate` を用いて先月次の「稼働見込み／Bench見込み」を投影（自動更新の見込みは `autoRenew` を考慮）。
  - 画面: ダッシュボードに「稼働率予測（3ヶ月）」と「ロールオフ予定要員一覧」。FR-06 と相互リンク。
- 期待効果: 空きの発生を先取りして提案を前倒し、Bench期間（＝損失）を短縮。
- 再利用: `DashboardServiceImpl`、`Contract`、`engineer-availability-visualization`。
- 規模/優先度: 中／★★（Phase 2）。

---

## C. 自動化（手入力削減）

### FR-08 要員空き状況メールの取込

- 狙い: パートナーからの「この要員空いてます」メールをAI取込し、提案可能な**外部要員の在庫**をDB化。FR-01（案件）と対でマッチング母集団を両輪で太らせる。
- 現状課題: 外部要員の空き情報はメール散在で、案件が来ても即当てられない。
- 対応方法:
  - 「取込基盤」を再利用。パーサ `BpAvailabilityParseService`（氏名イニシャル・スキル・単価・稼働開始可能日・所属BPを抽出）。
  - 格納先は用途で選択: (a) 軽量な「外部要員在庫 `t_bp_availability`」、または (b) 既存 `t_candidate`/`t_engineer(employmentType=BP)` へ寄せる。**設計判断を先に**（推奨: まず (a) の在庫テーブルで軽く始め、確度が上がったら要員化）。
  - 確定で在庫化 → FR-01/FR-02 のマッチングが自社要員＋外部在庫を横断。
- 期待効果: 「案件は来たが当てる人がいない」を減らし、成約機会を増やす。
- 再利用: 取込基盤（`skillsheet-ingestion`）、`AiTextService`、マッチング。
- 規模/優先度: 中／★★（Phase 3。FR-01と実装共通部が多い）。

### FR-09 入金消込の半自動化

- 狙い: 銀行入金と請求を突合して消込を半自動化し、経理の手作業を削減。
- 現状課題: freee連携はあるが、入金と `t_invoice`／`t_invoice_payment` の突合は手動。
- 対応方法:
  - `FreeeIntegrationService` で銀行明細（入金）を取得 → 金額・振込名義・時期で `t_invoice` 候補を自動マッチ（完全一致は自動消込、曖昧は候補提示して人が確定）。
  - 消込結果を `t_invoice_payment` に記録し、`recalcPaymentStatus`（既存）でステータス更新。差額・過入金は保留キューへ。
- 期待効果: 月末の入金確認・消込作業を大幅短縮。滞留debtorの早期把握（AR管理と連動）。
- 再利用: `FreeeIntegrationService`、`Invoice`/`InvoicePayment`、`ar-management`。
- 規模/優先度: 中〜大／★★（Phase 3。外部API依存で検証コスト高め）。

---

## D. 守る（合規・定着）

### FR-10 偽装請負・多重派遣リスクチェック

- 狙い: 契約構造が日本のSES法務リスク（偽装請負・多重派遣・多重下請け）に触れそうな時に警告するガードレール。
- 現状課題: `multi-tier-bp-management` で多層は扱えるが、**リスク判定・警告**の仕組みが無い。
- 対応方法:
  - ルールチェック（設定可能）: 多重下請けの段数上限、準委任/請負と勤務実態の整合、指揮命令フラグ、二重派遣の兆候等を `Contract`／BP階層／`WorkRecord` から検査。
  - 契約登録・更新時／月次締めチェックリスト（既存 `monthly-closing-checklist`）に「リスク項目」を追加し、該当時に警告と記録（`t_audit_log`）。
- 期待効果: 法令違反リスクの早期検知。監査・調査時のトレーサビリティ確保。
- 再利用: `multi-tier-bp-management`、`Contract`、`monthly-closing-checklist`、`AuditLog`。
- 規模/優先度: 中／★★（Phase 3。ルール定義の合意が必要）。

### FR-11 要員フォロー・定着リスク管理

- 狙い: 営業×要員の1on1／面談記録を残し、特にBench中・新人の**定着リスク**を可視化。離職＝採用コスト再発を抑える。
- 現状課題: `engineer-sales-commission` で担当関係はあるが、フォロー活動・満足度・定着リスクの記録が無い。
- 対応方法:
  - 新テーブル `t_engineer_followup`（engineer_id・実施者・日付・種別〔1on1/面談/連絡〕・満足度・トピック・次回予定）。要員詳細にフォロー履歴カード＋次回フォロー期日。
  - 定着リスクフラグ: 長期Bench・低満足度・フォロー間隔超過などから簡易スコア。期日超過は担当営業へ `NotificationService` 通知。
- 期待効果: フォロー漏れによる離職を減らし、稼働継続率を上げる。
- 再利用: `engineer-sales`（担当関係）、要員詳細、`NotificationService`。
- 規模/優先度: 中／★★（Phase 3）。

---

## 5. 次アクション

1. **「取込基盤」の一般化**（`skillsheet-ingestion` → `IngestionJob`＋`IngestionParser<T>`）を先行。FR-01/FR-08 の実装コストを下げる。
2. **AI基盤の実接続**（`ai.provider=gemini` を実キーで運用可能に）。FR-02 の前提。R8-03 の修正で gemini 起動不能は解消済み。
3. Phase 1（FR-01〜04）から着手。各案は着手時に `.kiro/specs/<name>/` へ requirements/design/tasks を展開（`skillsheet-ingestion` と同形式）。
4. 各案の「※設計判断」（FR-08 の格納先、FR-10 のルール範囲、FR-01 のメール受信方式）は spec 化前に発注者確認。

## 6. スペック展開時の名前（提案）

`project-email-ingestion`(FR-01) / `ai-proposal-generation`(FR-02) / `duplicate-proposal-guard`(FR-03) / `skillsheet-anonymized-export`(FR-04) / `cashflow-forecast`(FR-05) / `contract-renewal-calendar`(FR-06) / `utilization-forecast`(FR-07) / `bp-availability-ingestion`(FR-08) / `payment-reconciliation`(FR-09) / `labor-compliance-check`(FR-10) / `engineer-followup-retention`(FR-11)
</content>
