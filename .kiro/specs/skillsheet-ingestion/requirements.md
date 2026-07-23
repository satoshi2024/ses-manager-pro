# Requirements Document — スキルシート取込（履歴書自動取込・要員化）

## Introduction

現状、新規要員（`t_engineer`）の登録は**全項目を人が手入力**する。候補者（`t_candidate`）からの
変換も `CandidateServiceImpl.getEngineerInitialDto` が返すのは氏名とスキル概要テキストだけで、
候補者・BP・エージェントから受領する職務経歴書（スキルシート／履歴書、以下「スキルシート」）に
書かれた経歴・保有スキル・希望単価は要員登録・スキル登録・経歴登録の各画面で打ち直しになる。
SES業務で最も頻度の高い作業が丸ごと手作業のため、1人あたり15〜30分の入力工数と転記ミス・
スキル表記ゆれが恒常的に発生している（第8回監査 A8-06）。

本機能は「**スキルシートを投入 → システムが内容を自動抽出 → 人が1度だけレビュー・修正 → 確定すると
要員（＋スキル＋経歴）が一括生成される**」という取込フローを追加する。抽出はAI（LLM）＋
テキスト抽出（PDF/Excel/Word）で行い、最終的な登録内容は必ず人のレビューを経る
（AIの誤抽出をそのまま要員マスタに入れない）。

### 前提となる基盤改修（本機能の依存先）

本機能は第8回監査で指摘したAI基盤・ファイル処理の欠陥を前提に成立しないため、以下を先行または
同時に改修する（詳細は各ID・`design.md`）。

- **A8-01**: `GeminiService` の設定値未使用・クライアント供給キー・タイムアウト無しを是正し、
  サーバー側キーで対話・バッチ双方から呼べる `AiTextService` 抽象を用意する。
- **A8-03**: 孤児ファイル清理の参照列allowlistに取込テーブルの `stored_file_name` を登録する
  （でないとアップロードした原本が翌週の清理バッチで消える）。
- **A8-07**: 「スキル名文字列 → `m_skill_tag.skill_id`」解決（正規化＋未登録名の自動作成）を用意する。
- **A8-04 / A8-05**: PIIを含むファイルのダウンロードアクセス制御と保持方針。

### 確定済みの設計判断（既定。発注者の確認事項は「※要確認」で明示）

- 取込ジョブは専用テーブル `t_resume_ingestion` で管理する（`BaseEntity` 準拠・論理削除）。
- 対応ファイル形式は既存 `FileKind.SKILL_SHEET`（**pdf / xlsx / docx、最大10MB**）を流用する。
  画像スキャンのみのPDF（テキストレイヤ無し）はOCR対象外とし、抽出0文字なら「失敗」にする。
- 抽出→AI解析は**非同期**で実行し、一覧の状態表示で進捗を見せる（アップロードは即応答）。
- AIが無効（`ai.enabled=false` / `ai.provider=mock`）でも機能は動く：mock解析器が
  ヒューリスティック抽出を返し、レビュー画面で人が入力する運用に degrade する。
- 確定時の要員生成は既存の書込サービス（要員・スキル・経歴）を**1トランザクション**で呼ぶ
  （3回のHTTP呼び出しに分割しない。原子性を保つ）。
- メニュー権限は既存の候補者管理（V16: 管理者/営業/HR）に倣い、**管理者/HR**（＋※要確認で営業）に配布する。
- 確定後の要員の初期ステータスは `Bench`（待機）とし、即マッチング・提案対象になる。
- ※要確認: 希望単価の単位（`Engineer.expectedUnitPrice` のJavadocは「万円/月」。A7-24で単位不整合が
  既知。取込・表示・AIプロンプトで単位を1つに揃える）。
- ※要確認: 候補者連携（`t_candidate` からの起動と確定時の `linkConvertedEngineer`）を本フェーズに含めるか。

## Requirements

### Requirement 1: スキルシートの投入（アップロードと取込ジョブ生成）

#### Acceptance Criteria
1. THE システム SHALL 管理者/HR ロールがスキルシートファイル（pdf/xlsx/docx、最大10MB）を
   アップロードできる画面/APIを提供する（既存 `FileKind.SKILL_SHEET` の検証を流用）。
2. WHEN ファイルがアップロードされた場合、THE システム SHALL `t_resume_ingestion` に取込ジョブを
   1件作成し、初期状態を「取込待ち」、`stored_file_name`/`original_file_name`/`file_ext`/`created_by` を記録する。
3. THE システム SHALL アップロード直後に非同期でテキスト抽出→AI解析を開始し、応答は即座に返す
   （UIはブロックしない）。
4. WHEN 同一氏名＋生年月日の要員が既に存在する場合、THE システム SHALL レビュー画面に
   「重複の可能性」警告を表示する（登録はブロックしない）。
5. THE アップロードされた原本ファイル SHALL 孤児清理バッチの削除対象から除外される
   （`FileCleanupServiceImpl` が取込テーブルの `stored_file_name` を参照集合に含める）。

### Requirement 2: 内容の自動抽出（テキスト抽出＋AI構造化）

#### Acceptance Criteria
1. THE システム SHALL アップロードされたファイルからプレーンテキストを抽出する
   （pdf=OpenPDF、docx/xlsx=Apache POI。いずれも既存依存で追加不要）。
2. WHEN 抽出テキストが空（画像のみPDF等）の場合、THE システム SHALL ジョブを「失敗」にし、
   `error_message` に「テキストを抽出できません（画像PDFはOCR未対応）」を記録する。
3. THE システム SHALL 抽出テキストをAIへ渡し、**厳格なJSONスキーマ**（要員基本情報＋スキル配列＋
   経歴配列）で構造化結果を受け取り、`parsed_json` に保存して状態を「要確認」にする。
4. THE AI解析結果 SHALL 要員項目（氏名・カナ・性別・生年月日・国籍・最寄駅・経験年数・日本語レベル・
   希望単価・スキル概要）、スキル（名称・習熟度・経験年数）、経歴（期間From/To・案件名・役割・
   使用技術・概要・チーム規模・業種）を含む。不明項目は null とし、値を捏造しない。
5. WHEN AI呼び出しが失敗した場合（設定不備/外部障害）、THE システム SHALL ジョブを「失敗」にし、
   サニタイズ済み要約を `error_message` に保存する（PII・APIキーをログ/メッセージに出さない）。
6. WHEN `ai.enabled=false` または `ai.provider=mock` の場合、THE システム SHALL mock解析器で
   最低限の抽出（氏名候補・スキル語の拾い上げ等）を返し、状態を「要確認」にする（機能は停止しない）。
7. THE システム SHALL 「要確認」または「失敗」のジョブに対して再解析（AI再実行）を手動起動できる。

### Requirement 3: 人による1回のレビューと修正

#### Acceptance Criteria
1. THE システム SHALL 取込ジョブのレビュー画面を提供し、`parsed_json` を**編集可能なフォーム**
   （要員基本情報＋スキル表＋経歴表）に展開する。
2. THE レビュー画面 SHALL 抽出元の原本ファイルを別タブで開くリンク（`/api/files/{storedName}`）を
   併置し、原本と照合しながら修正できる。
3. THE レビュー画面 SHALL スキル名を `m_skill_tag` の候補とオートコンプリート照合し、
   マスタ未登録の名称は「新規タグとして作成」を明示する。
4. THE レビュー保存 SHALL 要員登録と同じバリデーション（`EngineerSaveDto` の値域。A8-08で allowlist 補強）を
   適用し、不正値は確定前に弾く。
5. THE システム SHALL レビュー中の修正内容を `parsed_json`（または別カラム）へ随時保存でき、
   確定前に離脱・再開できる。

### Requirement 4: 確定による要員一括生成

#### Acceptance Criteria
1. WHEN レビュー担当が「確定」した場合、THE システム SHALL 1トランザクションで
   (a) `t_engineer` を作成（初期ステータス `Bench`）、(b) スキル名を `skill_id` へ解決して
   `t_engineer_skill` を登録（未登録名は `m_skill_tag` に `category='未分類'` で自動作成）、
   (c) `t_engineer_career` を登録する。
2. WHEN 確定が成功した場合、THE システム SHALL ジョブ状態を「確定済」にし、
   `converted_engineer_id` に生成した要員IDを記録する。
3. THE システム SHALL 既に「確定済」（`converted_engineer_id` が非NULL）のジョブの再確定を拒否する
   （状態のCAS。二重生成防止）。
4. IF 確定処理の途中で失敗した場合、THEN THE システム SHALL 要員・スキル・経歴のいずれも作成せず
   ロールバックし、ジョブ状態を変更しない。
5. THE システム SHALL 「却下」操作でジョブを「却下」状態にでき、却下ジョブは要員を生成しない。
6. ※要確認: WHEN 取込ジョブが候補者に紐づく場合、THE システム SHALL 確定時に
   `CandidateService.linkConvertedEngineer` を呼び、候補者→要員の変換を完了させる。

### Requirement 5: 権限・PII・監査

#### Acceptance Criteria
1. THE 取込メニュー/API SHALL `MenuPermissionFilter` と静的制約で管理者/HR（※要確認: 営業）に限定される。
2. THE 原本ファイルのダウンロード SHALL アクセス制御を通す（A8-04。担当外・無権限の取得を拒否）。
3. THE 取込に伴うPII（抽出テキスト・原本） SHALL 保持方針を持つ：確定/却下後の保持期間、
   却下ジョブの自動パージ（例: 却下後30日）を定義する（A8-05）。
4. THE 取込・確定・却下操作 SHALL 既存の監査ログ（`ApiAuditFilter`/`t_audit_log`）に記録される。

### Requirement 6: 状態モデルと一覧

#### Acceptance Criteria
1. THE 取込ジョブ SHALL 次の状態を持つ：`取込待ち`／`抽出中`／`要確認`／`確定済`／`却下`／`失敗`。
2. THE 一覧画面 SHALL 状態・ファイル名・登録者・取込日時・（確定済なら）要員リンクを表示し、
   状態で絞り込める。ページャは既存規約（A7-11 の `PageUtils.safePage`）に従う。
3. THE 一覧 SHALL 「抽出中」ジョブの状態をポーリング等で更新し、完了後に「要確認/失敗」へ切り替わる。

## Out of Scope（本フェーズ対象外）

- 画像スキャンPDF/紙のOCR（テキストレイヤの無いPDFは失敗扱い）。
- 複数人が1ファイルに混在するスキルシートの分割取込（1ファイル=1要員を前提）。
- AIによるスキルの自動採点・マッチングスコア付与（既存AIマッチングとは独立）。
- スキルシートの逆生成（要員→PDF）は既存 `SkillSheetGenerator` を使用し、本機能では触らない。
</content>
