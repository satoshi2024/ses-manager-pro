# NF-03 開発開始承認 Decision

> **Status:** `APPROVED`（中央 traceability NF-03 と同期）

## Decision evidence

| フィールド | 値 |
|---|---|
| DecisionId | `DG-03-SCOPE-APPROVAL-20260828-01` |
| 決定日 | `2026-08-28`（Asia/Tokyo） |
| OwnerRef | `PROJECT_OWNER` |
| OwnerType | `ROLE` |
| ApprovalMode | `ROLE_BASED_DEV` |
| 実名 | repository へ記録しない |
| Base branch | `origin/main` |
| Base commit | `76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| 旧 merge-base | `455fc92e` — **承認 Base として使用しない** |
| Base 取り込み merge commit | `5d4b5c2791e63297cc2f83d323d50a94db329646` |
| 承認 commit | `03545127dcd2aa6f6fae78af11544426ccfe7ad7`（Gate 0） |
| Owner 識別ポリシー | `DG-03-DEV-20260828`（別 Decision。本 Decision とは分離） |

## Approved scope（in scope）

- HR/admin が管理する資格 master
- 資格取得、更新、取消、訂正、期限、証憑
- training course、learning plan、enrollment
- 既存 `ExpenseRequest` を利用する研修費申請・承認
- 90/60/30 日期限通知
- effective-dated skill history、project/position demand、as-of skill gap
- rule-based gap と AI による course/skill 候補
- 本人申請、manager 提案、HR 確定、監査 event

## Approved scope（out of scope）

- 外部 LMS 自動連携
- AI 自動評価
- AI 自動配置
- AI による採否・昇格・給与・不利益判断

## DG-03-1 資格番号 PII

- 資格番号は restricted 個人情報
- plaintext を domain table へ保存せず、AES-256-GCM ciphertext または token reference として保存
- encryption key は DB/repository 外で管理し、key version を保持
- 本人および `certification.pii.view` を持つ HR/admin だけが full 値を参照可能。その他 role は mask
- list export、一般 export、notification、log、audit detail、AI prompt へ raw 値を出さない
- 開発/test fixture では実 PII を使用しない
- retention class は `CERTIFICATION_PII`
- NF-03 では自動削除を実行しない。production の保持年数と破棄は NF-07 の release gate で決定
- NF-07 未決定は NF-03 の開発を止めず、production 有効化だけを止める

## DG-03-2 証憑

- document type = `CERTIFICATION_EVIDENCE`
- target type = `CERTIFICATION_RECORD`
- typed resolver だけを認可根拠にする
- generic `ENGINEER` link を作らない
- mixed link でも restricted policy を優先
- admin bypass、empty-link allow、generic OR-union を禁止
- exact document version、SHA-256 hash、scan = CLEAN を download 時に再検証
- raw storage path と binary を list/export へ出さない

## DG-03-3 skill taxonomy

- `m_skill_tag` を唯一の canonical skill master とする
- alias の追加・変更・merge は HR/admin の human approval を必須
- 未知 skill を自動 master 登録しない
- unknown は raw/normalized 値とともに説明可能な gap として返す
- PROJECT/POSITION/COMBINED では承認済み precedence を使用する

## DG-03-4 as-of

- engineer skill、project skill、project position の append-only event を採用
- current projection と event を同一 transaction で更新
- feature 有効化日を履歴開始日とする
- 根拠のない過去データ backfill を行わない
- event がない過去期間は `historical_data_unavailable` を返す
- current 値を過去へ遡及適用しない
- 月次、export、再現用途では immutable skill-gap snapshot を使用
- source version、taxonomy version、as-of、result hash を保存

## DG-03-5 研修費用・月次締め（選択肢 A 採用）

- 経費締めは **選択肢 A**：`ExpenseRequestServiceImpl` の amount、expenseDate、relation、submit、accounting、paid 変更経路へ共通の月次締め検査を適用
- enrollment 側へ第二の支払 status を作らない
- planned cost は plan 承認時の snapshot
- actual cost、approval、accounting job、paid state は既存 `ExpenseRequest` を正本とする
- 0 円研修は `ZERO_COST_CONFIRMED` event だけを作り、ExpenseRequest を作成しない
- 正数費用は既存 Approval Engine を使用
- approval threshold は既存設定を使用し、NF-03 で金額を hard-code しない
- threshold 判定は inclusive
- 計画額超過は差額 approval または plan amendment を必須
- 締め済み月の金額・関連・支払状態変更を拒否

## DG-03-6 AI / human boundary

- rule-based skill gap を primary result とする
- AI は course/skill 候補と説明だけを返す
- AI 停止、timeout、error、低信頼時も rule-based gap を返す
- SELF、MANAGER、HR_FINAL を別 record として保存
- SELF と MANAGER は proposal であり、公式 skill へ直接反映しない
- HR_FINAL だけが human actor、reason、effective period 付きで公式 skill へ反映可能
- AI 候補 accept だけでは skill、配置、採否、昇格、給与を変更しない
- AI を sole source とする不利益判断を禁止
- AI payload へ資格番号、証憑 binary、storage path を渡さない

## 通知・利用者境界

| 項目 | 値 |
|---|---|
| timezone | `Asia/Tokyo` |
| 期限通知 | 90/60/30 日 |
| 退職者 | 通知対象外 |
| 休職者 | 本人通知を停止、HR 向け確認対象 |
| account 未 link | HR へ通知 |
| 重複防止 | DB unique/claim で multi-node 重複通知を防止 |
| 異議・訂正 | 本人の異議・訂正要求は HR 確認 workflow へ。本人が HR_FINAL を直接変更できない |

## F1 着手条件

- 本 Decision 記録と中央 traceability `APPROVED` 遷移
- `origin/main@76e45340` の feature branch 取り込み完了
- PLAN Review PASS（Gate 0 Head）

F1 migration は取り込み後の latest+1（`V114` 以降）から開始する。
