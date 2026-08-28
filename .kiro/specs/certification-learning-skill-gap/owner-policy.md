# 開発段階 Owner ポリシー（NF-03）

> **本書は開発段階の責任主体の記録方式を確定するものであり、NF-03 を `APPROVED` にするものではない。**
> `APPROVED` への遷移には、approved scope・Base SHA・DG-03 実値（6項目＋経費締め A/B）の承認が別途必要である。

## 1. 原則

| 原則 | 内容 |
|---|---|
| 実名非記録 | 個人の実名を repository、`.kiro`、commit、test fixture へ記録しない |
| 責任ロール | Owner は個人名ではなく、安定した責任ロールで管理する |
| 本番対応 | 本番移行時の実ユーザーとの対応は repository 外の組織管理・監査システムで解決する |

## 2. 開発段階の Owner 識別子（確定）

| 項目 | 値 |
|---|---|
| OwnerRef | `PROJECT_OWNER` |
| OwnerDisplayName | `プロジェクト責任者` |
| OwnerType | `ROLE` |
| ApprovalMode | `ROLE_BASED_DEV` |
| DecisionId | `DG-03-DEV-20260828` |
| 決定日 | `2026-08-28` |

## 3. 承認証跡（開発段階）

承認を追跡する際は、次のフィールドをセットで記録する。個人名は含めない。

| フィールド | 説明 |
|---|---|
| DecisionId | 例: `DG-03-DEV-20260828`（本ポリシー）または DG-03 実値承認時の decision ID |
| 決定日 | `YYYY-MM-DD`（Asia/Tokyo） |
| OwnerRef | `PROJECT_OWNER` |
| 対象 scope | approved scope の実値（未承認時は placeholder のまま） |
| Base SHA | 承認時点の merge-base または指定 base commit |
| 承認 commit | 台帳・spec を更新した commit SHA |

## 4. Gate 表現の統一

| 旧表現（使用しない） | 新表現 |
|---|---|
| Owner の実名が必要 | 責任主体を一意に識別できる **OwnerRef** が必要 |
| 決定者の実名を記録 | **OwnerRef**・DecisionId・決定日・承認 commit を記録 |
| `<OWNER>` 個人名 placeholder | `OwnerRef=PROJECT_OWNER`（開発段階） |

## 5. `APPROVED` への遷移条件（未達 — P1-01b OPEN）

次が揃った時点で、中央 traceability の NF-03 を `CANDIDATE` → `APPROVED` に更新し、Owner 列を `PROJECT_OWNER` とする。

1. approved scope の実値
2. **承認** Base branch / Base SHA の実値（技術比較用 merge-base とは別に記録）
3. DG-03 の6 decision 実値（資格 PII、証憑 enum、taxonomy、as-of、費用締め **A or B**、AI/human 境界）
4. 上記を DecisionId・決定日・OwnerRef・承認 commit で traceability へ記録

本ポリシー（`DG-03-DEV-20260828`、commit `34f20724`）の採用だけでは `APPROVED` にしない（P1-01a は閉じ、P1-01b は未達）。

## 6. Review 判定（Head `34f20724`）

| ルール | 内容 |
|---|---|
| 実名 | 開発段階では要求しない。欠如を PLAN FAIL 理由にしない |
| 責任主体 | 一意な OwnerRef（`PROJECT_OWNER`）で充足 |
| P1-01a | **VERIFIED_CLOSED**（本 Decision の証跡） |
| P1-01b | **OPEN**（approved scope・承認 Base SHA・DG-03 業務実値・`APPROVED`） |
