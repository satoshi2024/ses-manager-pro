# Requirements — スキルシート匿名化＋提案用多形式出力（FR-04）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-04）。

客先へ出す提案用スキルシートは、個人情報を伏せ（イニシャル化）て客先指定形式で出す必要がある。既存 `SkillSheetGenerator` は PDF＋Excel 生成済みだが、氏名がそのまま・様式固定。本機能は匿名化オプションと様式選択を追加する。

### 確定済みの設計判断
- 匿名化は既存 `Engineer.initialName`（イニシャル）を用いる。氏名・生年月日（年齢は残す可）・連絡先・顔写真を伏せる。
- 様式は最低2種（自社標準／簡易）。客先様式テンプレートは拡張。
- 出力は PDF/Excel 両方（既存 `generatePDF`/`generateExcel` を拡張）。

## Requirements

### Requirement 1: 匿名化出力
1. THE スキルシート生成 SHALL 「匿名化」オプションを持ち、ONのとき氏名を `initialName`（無ければ自動生成イニシャル）に置換し、生年月日・連絡先・顔写真を伏せる（年齢/経験年数は残す）。
2. THE 匿名化 SHALL PDF/Excel 双方に適用される。

### Requirement 2: 様式選択
1. THE 生成 SHALL 様式（自社標準/簡易/客先様式）を選択できる。
2. THE 様式定義 SHALL `m_system_config` またはテンプレート管理で保持し、追加できる。

### Requirement 3: 提案連携
1. THE 提案（`Proposal`）/カンバン SHALL 「匿名スキルシートを出力」を起動でき、生成物を `Proposal.skillSheetPath` に保存できる。
2. THE 出力 SHALL 既存のファイル保存/ダウンロード（アクセス制御 A8-04 準拠）を通す。

## Out of Scope
- スキルシートの内容自動生成（AI文章生成は FR-02/別途）。本機能は既存データの体裁・匿名化に限定。
</content>
