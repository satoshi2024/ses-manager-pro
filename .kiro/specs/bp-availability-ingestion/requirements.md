# Requirements — 要員空き状況メール取込（FR-08）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-08）。

パートナーから届く「この要員空いてます」メールをAI取込し、提案可能な**外部要員の在庫**をDB化する。FR-01（案件取込）と対で、マッチングの母集団（案件×要員）を両輪で太らせる。`skillsheet-ingestion`／FR-01 と**同一の取込パターン**を再利用。

### 確定済みの設計判断
- 入力経路は貼付／.emlアップロード（IMAPは次フェーズ）。
- 格納先は用途で選択。**推奨: まず軽量な外部要員在庫 `t_bp_availability` で始め、確度が上がったら要員化**（`t_engineer` employmentType=BP へ昇格）。※要確認: この方針でよいか。
- PII: 氏名はイニシャルで保持（`initialName` 相当）。

## Requirements

### Requirement 1: 投入と抽出
1. THE システム SHALL パートナーの要員メールを貼付/emlで投入し、非同期でAI抽出→要確認まで進める（`skillsheet-ingestion`/FR-01 と同型のジョブ）。
2. THE 抽出項目 SHALL イニシャル・スキル（配列）・単価・稼働開始可能日・所属BP会社・年齢/経験・備考。不明はnull。
3. WHEN AI無効の場合、THE システム SHALL mock抽出で要確認まで到達する。

### Requirement 2: 在庫化と要員化
1. WHEN 確定した場合、THE システム SHALL 外部要員在庫 `t_bp_availability` にレコードを生成する（または要員化。設計判断に従う）。
2. THE 在庫 SHALL 「提案可能状態」を持ち、稼働開始可能日で失効管理できる。
3. THE システム SHALL 在庫レコードを後から `t_engineer`（BP）へ昇格できる。

### Requirement 3: マッチング連携
1. THE マッチング（FR-01/FR-02） SHALL 自社Bench要員＋外部在庫を横断して候補提示する。

## Out of Scope
- IMAP常時受信（次フェーズ）。1メール複数要員の自動分割。
</content>
