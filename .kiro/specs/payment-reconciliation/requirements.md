# Requirements — 入金消込の半自動化（FR-09）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-09）。

freee連携（`FreeeIntegrationService`）はあるが、銀行入金と請求（`t_invoice`/`t_invoice_payment`）の突合は手動。本機能は銀行明細（入金）を取得して請求候補を自動マッチし、確実な一致は自動消込、曖昧は候補提示して人が確定する。

### 確定済みの設計判断
- 突合キー: 金額（残額一致）＋振込名義（顧客名の正規化一致）＋時期（due_date近傍）。
- 完全一致（金額＋名義＋期間）は自動消込、部分一致は候補提示（人が確定）。
- 過入金/差額は保留キューへ（自動確定しない）。

## Requirements

### Requirement 1: 入金取得と突合
1. THE システム SHALL freee経由で銀行入金明細を取得する（`FreeeIntegrationService` 拡張）。
2. THE システム SHALL 各入金に対し、金額（残額）・振込名義（顧客正規化）・時期で `t_invoice`（未入金/一部入金）候補をスコアリングする。
3. WHEN 高信頼一致（金額一致＋名義一致）の場合、THE システム SHALL 自動で `t_invoice_payment` を登録し `recalcPaymentStatus`（既存）でステータス更新する。
4. WHEN 曖昧な場合、THE システム SHALL 候補を提示し人が消込先を確定する。

### Requirement 2: 差額・保留
1. THE 過入金/不足/未マッチ入金 SHALL 保留キューに積み、自動確定しない。
2. THE 消込操作 SHALL 監査ログに記録する。

### Requirement 3: 画面・権限
1. THE 経理画面 SHALL 未消込入金・候補・保留を一覧し、ワンクリック消込・手動割当ができる。
2. THE 機能 SHALL 経理相当（管理者/マネージャー）に限定する。

## Out of Scope
- 銀行API直結（freee経由のみ）。BP支払（出金）側の自動消込（本フェーズは入金）。
</content>
