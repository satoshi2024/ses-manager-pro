# Requirements — 要員フォロー・定着リスク管理（FR-11）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-11）。

`engineer-sales-commission` で要員×担当営業の関連はあるが、フォロー活動・満足度・定着リスクの記録が無い。特にBench中・新人の離職は採用コスト再発に直結する。本機能は1on1/面談記録を残し、定着リスクを可視化してフォロー漏れを防ぐ。

### 確定済みの設計判断
- フォロー記録は新テーブル `t_engineer_followup`。
- 定着リスクは簡易スコア（長期Bench・低満足度・フォロー間隔超過）。
- フォロー期日超過は担当営業へ通知（`NotificationService`）。

## Requirements

### Requirement 1: フォロー記録
1. THE システム SHALL 要員に対しフォロー記録（実施者・日付・種別〔1on1/面談/連絡〕・満足度・トピック・次回予定日）を登録・一覧できる。
2. THE 要員詳細 SHALL フォロー履歴カードと次回フォロー期日を表示する。
3. THE 記録 SHALL `DataScopeService`（担当営業スコープ）に従う。

### Requirement 2: 定着リスク
1. THE システム SHALL 要員ごとに定着リスク指標を算出する（長期Bench・直近満足度低・フォロー間隔超過などの合成）。
2. THE 一覧 SHALL リスク高の要員を絞り込み・強調表示できる。

### Requirement 3: フォロー漏れ通知
1. WHEN 次回フォロー期日を超過した場合、THE システム SHALL 担当営業へ通知する（`NotificationService`、dedupe）。

## Out of Scope
- 人事評価・給与査定との連動。匿名アンケート基盤。
</content>
