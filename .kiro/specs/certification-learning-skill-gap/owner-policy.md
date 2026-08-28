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

## 5. `APPROVED` 遷移（完了 — Task 0G）

DecisionId `DG-03-SCOPE-APPROVAL-20260828-01`（2026-08-28、OwnerRef=`PROJECT_OWNER`）により、中央 traceability NF-03 は `APPROVED` へ遷移済み。

| 項目 | 値 |
|---|---|
| approved scope | [approval-decision.md](approval-decision.md) |
| 承認 Base SHA | `76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| DG-03 実値 | DG-03-1〜6（経費締め **A**）— approval-decision.md |
| P1-01a | VERIFIED_CLOSED（`DG-03-DEV-20260828`） |
| P1-01b | VERIFIED_CLOSED（本 Decision） |

F1 着手には Gate 0 Head での **PLAN Review PASS** が追加条件。

## 6. Review 判定履歴

| Review | Head | PLAN | 備考 |
|---|---|---|---|
| R5 | `34f20724` | FAIL | P1-01b OPEN のみ |
| R6 | `03545127` | PASS（実装側自己判定・参考） | Task 0G |
| R7 | `4e171f19` | **PASS**（独立 Plan Review） | 正本。F1 許可 |
