# Requirements — 案件メール自動取込（FR-01）

## Introduction

親ロードマップ: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-01）。

SESの営業はパートナーのメーリングリストから日々大量の案件メールを受け取り、`t_project` へ手入力している。転記の工数と取りこぼしで提案が遅れる。本機能は「案件メールを投入 → AIが内容を自動抽出 → 人が1回レビュー → 確定で案件（`Project`）を生成し、Bench要員へのマッチ候補を即提示」する取込フローを追加する。

既存の `skillsheet-ingestion`（履歴書取込）と**同一パターン**（受信→テキスト抽出→AIでJSON構造化→人レビュー→エンティティ生成）。その基盤を再利用する。

### 確定済みの設計判断（※要確認は発注者確認）

- 入力経路は2段構え。MVP=メール本文の**貼り付け／.emlアップロード**、拡張=IMAP定期取込（別フェーズ）。
- 取込ジョブは専用テーブル `t_project_ingestion`（`t_resume_ingestion` と同型・`BaseEntity`・論理削除）。
- AI無効（`ai.provider=mock`）でも mock パーサで「要確認」まで到達（機能停止しない）。
- 確定生成する `Project` の初期ステータスは既存の「募集中」相当（`StatusConstants` の案件初期値に合わせる）。
- メニュー権限は営業/マネージャー/管理者（既存 `project` メニューの api_prefix 流用も検討）。
- ※要確認: IMAP自動受信を本フェーズに含めるか（推奨: MVPは貼付/emlのみ）。
- ※要確認: 同一案件の重複取込検知（件名＋単価＋会社での近似重複警告）を含めるか。

## Requirements

### Requirement 1: 案件メールの投入とジョブ生成
1. THE システム SHALL 営業/マネージャー/管理者が「メール本文の貼り付け」または「.emlファイルのアップロード」で案件を投入できる画面/APIを提供する。
2. WHEN 投入された場合、THE システム SHALL `t_project_ingestion` にジョブを作成し初期状態「取込待ち」で記録し、非同期で抽出→AI解析を開始する（即応答）。
3. THE .emlアップロード SHALL 既存 `FileKind` に準じたサイズ・形式検証を通す（新種別 `PROJECT_EMAIL` を追加、eml/txt想定）。
4. THE 原本（貼付テキスト/emlファイル） SHALL 孤児清理の対象外になる（`FileReferenceProvider` を追加）。

### Requirement 2: 内容の自動抽出（AI構造化）
1. THE システム SHALL 案件メールから厳格JSONで案件項目を抽出する: 案件名・必要スキル（配列）・単価下限/上限（円）・勤務地・リモート可否・開始日/終了日・商流（元請/一次/二次…）・募集人数・エンド顧客名・備考。不明は null、値を捏造しない。
2. WHEN AI呼び出しが失敗/テキスト空の場合、THE システム SHALL ジョブを「失敗」にし、サニタイズ済み理由を `error_message` に記録する。
3. WHEN `ai.provider=mock` の場合、THE システム SHALL 正規表現ベースの mock 抽出で「要確認」まで到達する。
4. THE システム SHALL 「要確認/失敗」ジョブの再解析を手動起動できる。

### Requirement 3: レビューと確定
1. THE システム SHALL レビュー画面で抽出結果を編集フォームに展開し、原本（貼付テキスト/eml）を併置する。
2. THE 必要スキル名 SHALL `m_skill_tag` とオートコンプリート照合し、未登録名は「新規」表示（既存 `SkillTagResolver` 再利用は任意）。
3. WHEN 確定した場合、THE システム SHALL 1トランザクションで `Project`（＋案件スキル `t_project_skill`）を生成し、ジョブ状態を「確定済」・`converted_project_id` を記録する。
4. THE システム SHALL 確定済（`converted_project_id` 非NULL）の再確定を拒否する（CAS・409）。
5. THE システム SHALL 「却下」でジョブを却下状態にできる（確定済は却下不可・CAS）。

### Requirement 4: マッチング連携
1. WHEN 案件が確定生成された場合、THE システム SHALL 既存 `AiMatchingService.findMatchingEngineers(projectId)` を呼び、Bench要員のマッチ候補を確定完了画面に提示する。
2. THE マッチ候補 SHALL そのまま提案作成（既存フロー）へ渡せる。

### Requirement 5: 一覧・状態
1. THE ジョブ SHALL `取込待ち/抽出中/要確認/確定済/却下/失敗` を持ち、一覧で状態フィルタ・ページャ（`PageUtils.safePage`）を備える。
2. THE 一覧 SHALL 「抽出中」をポーリング更新する。

## Out of Scope
- IMAP/POP3の常時受信（本フェーズは貼付/eml。受信ジョブは次フェーズ）。
- 1メールに複数案件が混在する場合の自動分割（1メール=1案件を前提。複数はレビューで分ける運用）。
</content>
