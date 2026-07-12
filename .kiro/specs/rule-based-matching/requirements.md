# Requirements Document — ルールベースマッチング(P4)

## Introduction

`AiMatchingServiceImpl` は固定3件のモックを返すだけで、`t_ai_log` への記録も無い。
実 LLM 接続(`GeminiService` / `ai.provider: gemini`)の前段として、P1 で整備した
スキルデータ(`t_engineer_skill` × `t_project_skill`)による**ルールベース採点**を `ai.provider: rule` として実装する。
将来 LLM を接続した際のフォールバックにもなる。

**依存**: P1(engineer-skill-career)完了が前提。

## Requirements

### Requirement 1: ルールベース採点エンジン

#### Acceptance Criteria
1. THE システム SHALL 要員×案件のマッチスコア(0〜100)を以下の配点で算出する:
   - 必須スキル充足率 × 50点(必須が未定義なら50点満点扱い)
   - 尚可スキル充足率 × 20点(尚可が未定義なら20点満点扱い)
   - 単価適合 × 20点(希望単価が案件の単価レンジ内=20点、レンジ外は乖離1万円につき2点減点、下限0点)
   - 稼動可能日適合 × 10点(案件開始日までに稼動可能=10点、遅れ30日以内=5点、それ以上=0点)
2. THE システム SHALL スコアの根拠(充足した/不足した必須スキル名等)を日本語の `reason` として組み立てる。
3. IF 必須スキルの充足率が 50% 未満の場合、THEN THE 結果一覧 SHALL 当該案件を除外する。

### Requirement 2: 要員→案件マッチング(既存画面の実データ化)

#### Acceptance Criteria
1. WHEN `ai.provider=rule` で `/api/ai/matching/{engineerId}` を呼んだ時、THE システム SHALL 「募集中」案件を対象にスコア降順・上位10件を返す(モック固定3件の廃止)。
2. THE マッチング画面 SHALL 結果カードから提案作成モーダル(要員・案件・提案単価を事前入力)を開ける。
3. `ai.provider=mock` の場合は既存モックの挙動を維持する(プロファイル/設定切替のみで挙動が変わる)。

### Requirement 3: 案件→要員の逆方向推薦

#### Acceptance Criteria
1. THE システム SHALL `/api/ai/matching/project/{projectId}` で、Bench・提案中の要員を対象にスコア降順・上位10件を返す。
2. THE 案件一覧 SHALL 各行に「候補要員を探す」アクションを持ち、結果をモーダル表示する。

### Requirement 4: AI ログの記録

#### Acceptance Criteria
1. WHEN マッチングを実行した時、THE システム SHALL `t_ai_log` に request_type='マッチング'、request_params(JSON)、実行者 ID を記録する(rule 実行時は tokens_used=0, cost_jpy=0)。
2. 通知センター(P3)導入済みの場合、THE システム SHALL マッチング完了通知を `publish` する。
